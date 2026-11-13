package com.aigamelab.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.common.BusinessException;
import com.aigamelab.config.ConfigVersionService;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.domain.Step;
import com.aigamelab.domain.Task;
import com.aigamelab.domain.TestPlan;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.repository.ConfigVersionRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.StepRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.repository.TestPlanRepository;
import com.aigamelab.runtime.AgentLoop;
import com.aigamelab.runtime.RunControlService;
import com.aigamelab.runtime.ToolExecutor;
import com.aigamelab.sim.GameConfig;
import com.aigamelab.sim.GameConfigLoader;
import com.aigamelab.sim.model.Player;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T7-1 验收：Config Version → Tester Test Execution（显式解析，禁止 findLatest 漂移）。
 *
 * 验证 Tester Run 真正使用指定版本（而非偷偷使用 latest）：
 *  - Case 1：Run A → v001 / Run B → v002 解析到不同配置；
 *  - Case 2：创建 v002 后 Run(v001) 仍须使用 v001（禁止自动漂移到 v002）；
 *  - TD-T7-001：v999 不存在 → 40401；null → 40001。
 *
 * 最小必要接线：GameSessionService 经 ConfigVersionService.findByVersion 显式解析
 * （非 findLatest），并记录 sessionId→configVersion 供追踪/校验。不修改冻结的
 * FakeGameEnvironment / ConfigVersion append-only 语义。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T7-1 Config Version → Tester Test Execution")
class ConfigVersionTesterExecutionIntegrationTest {

    @Autowired RunControlService runControl;
    @Autowired AgentLoop agentLoop;
    @Autowired GameSessionService sessionService;
    @Autowired ConfigVersionService configService;
    @Autowired ConfigVersionRepository configRepo;
    @Autowired RunRepository runRepo;
    @Autowired StepRepository stepRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired TestPlanRepository planRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;
    @Autowired ToolExecutor toolExecutor;

    static final ObjectMapper M = new ObjectMapper();

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-t71-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Path DB = tempDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:"
                + DB.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON");
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        FakeLlmGateway fakeLlmGateway() {
            return new FakeLlmGateway();
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM design_document");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM task");
        llm.reset();
    }

    private Task newTaskWithConfig(String configVersion) {
        return taskRepo.insert(new Task(null, "Tester 任务", "测试配置 " + configVersion,
                "{\"configVersion\":\"" + configVersion + "\"}", 50, false, "ACTIVE", null));
    }

    private TestPlan activatePlanFor(long taskId, String configVersion) {
        TestPlan draft = planRepo.insert(new TestPlan(null, taskId, configVersion,
                TestPlan.STATUS_DRAFT, "[]", null));
        planRepo.updateStatus(draft.id(), TestPlan.STATUS_ACTIVE);
        return planRepo.findById(draft.id()).orElseThrow();
    }

    /** 插入 v002（与 v001 不同的快照，证明显式解析到不同版本而非 latest）。幂等：已存在则跳过。 */
    private void insertV002() {
        if (configService.findByVersion("v002").isPresent()) {
            return;
        }
        GameConfig v001 = GameConfigLoader.fromJson(
                configService.findByVersion("v001").orElseThrow().configJson());
        // 制造一个可区分的 v002 快照（数值与 v001 不同，仅用于版本解析校验）
        GameConfig v002 = new GameConfig("v002",
                new Player(50.0, 1.0, 5.0, 100.0, 1.0, 10.0, "crit"),
                v001.weapons(), v001.upgrades(), v001.enemy(), v001.wave(),
                v001.difficulty(), v001.targets(), v001.sim());
        configRepo.insert(new com.aigamelab.domain.ConfigVersion("v002",
                GameConfigLoader.toJson(v002), "v001", null, "T7-1 测试版本", null));
    }

    private String sessionOf(long runId) {
        return jdbc.queryForObject(
                "SELECT json_extract(result_json,'$.sessionId') FROM tool_call WHERE run_id=? AND tool_name='game_start'",
                String.class, runId);
    }

    // ---- T7-1 Case 1：Run A→v001 / Run B→v002 显式解析到不同配置 ----

    @Test
    @DisplayName("Case 1 显式版本解析：Run A→v001 与 Run B→v002 使用不同配置")
    void runBindsExplicitConfigVersionNotLatest() {
        insertV002();
        assertThat(configService.findByVersion("v001")).isPresent();
        assertThat(configService.findByVersion("v002")).isPresent();
        // 当前 latest = v002（按字典序），但 Run 必须显式用各自指定版本
        assertThat(configService.getCurrent().version()).isEqualTo("v002");

        // Run A 指定 v001
        Task taskA = newTaskWithConfig("v001");
        activatePlanFor(taskA.id(), "v001");
        llm.thenStop("测试完成");
        Run completedA = runControl.start(taskA.id());
        awaitTerminal(completedA.id());
        Run runA = ActiveTestRun.after(runRepo,completedA);
        Step sA = stepRepo.insert(new Step(null, runA.id(), 10, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(runA, sA, new com.aigamelab.llm.dto.ToolCall("cA", "game_start",
                "{\"seed\":42,\"configVersion\":\"v001\",\"overrides\":{}}"));
        String sidA = sessionOf(runA.id());
        assertThat(sessionService.configVersionOf(sidA)).isEqualTo("v001");

        // Run B 指定 v002
        Task taskB = newTaskWithConfig("v002");
        activatePlanFor(taskB.id(), "v002");
        llm.thenStop("测试完成");
        Run completedB = runControl.start(taskB.id());
        awaitTerminal(completedB.id());
        Run runB = ActiveTestRun.after(runRepo,completedB);
        Step sB = stepRepo.insert(new Step(null, runB.id(), 10, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(runB, sB, new com.aigamelab.llm.dto.ToolCall("cB", "game_start",
                "{\"seed\":42,\"configVersion\":\"v002\",\"overrides\":{}}"));
        String sidB = sessionOf(runB.id());
        assertThat(sessionService.configVersionOf(sidB)).isEqualTo("v002");

        // 两次解析到不同版本（显式指定，非 latest）
        assertThat(sessionService.configVersionOf(sidA)).isNotEqualTo(
                sessionService.configVersionOf(sidB));
        assertThat(sidA).isNotEqualTo(sidB);
    }

    // ---- T7-1 Case 2：创建 v002 后 Run(v001) 仍用 v001（禁止漂移） ----

    @Test
    @DisplayName("Case 2 禁止漂移：v002 创建后 Run(v001) 仍使用 v001")
    void runDoesNotDriftToLatestAfterNewVersion() {
        // 先 v001 Run
        Task taskA = newTaskWithConfig("v001");
        activatePlanFor(taskA.id(), "v001");
        llm.thenStop("测试完成");
        Run completedA = runControl.start(taskA.id());
        awaitTerminal(completedA.id());
        Run runA = ActiveTestRun.after(runRepo,completedA);
        Step sA = stepRepo.insert(new Step(null, runA.id(), 10, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(runA, sA, new com.aigamelab.llm.dto.ToolCall("cA", "game_start",
                "{\"seed\":42,\"configVersion\":\"v001\",\"overrides\":{}}"));
        String sidA = sessionOf(runA.id());
        assertThat(sessionService.configVersionOf(sidA)).isEqualTo("v001");

        // 创建 v002（latest 变成 v002）
        insertV002();
        assertThat(configService.getCurrent().version()).isEqualTo("v002");

        // 即便 latest=v002，新起 Run 显式 v001 仍绑定 v001（无漂移）
        Task taskB = newTaskWithConfig("v001");
        activatePlanFor(taskB.id(), "v001");
        llm.thenStop("测试完成");
        Run completedB = runControl.start(taskB.id());
        awaitTerminal(completedB.id());
        Run runB = ActiveTestRun.after(runRepo,completedB);
        Step sB = stepRepo.insert(new Step(null, runB.id(), 10, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(runB, sB, new com.aigamelab.llm.dto.ToolCall("cB", "game_start",
                "{\"seed\":42,\"configVersion\":\"v001\",\"overrides\":{}}"));
        String sidB = sessionOf(runB.id());
        // 关键断言：即便 latest 已是 v002，Run(v001) 仍绑 v001
        assertThat(sessionService.configVersionOf(sidB)).isEqualTo("v001");
        assertThat(sessionService.configVersionOf(sidB)).isEqualTo(
                sessionService.configVersionOf(sidA));
    }

    // ---- TD-T7-001：v999 → 40401；null → 40001（直接验 GameSessionService 显式解析） ----

    @Test
    @DisplayName("TD-T7-001 不存在的 configVersion 抛 40401")
    void unknownConfigVersionThrows404() {
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> sessionService.start(42L, "v999", java.util.Map.of(),
                        "SCRIPTED", null));
        // 40401 = NOT_FOUND（TD-T7-001 验收：v999 → 40401）
        assertThat(ex.getCode()).isEqualTo(40401);
    }

    @Test
    @DisplayName("TD-T7-001 空 configVersion 抛 40001")
    void nullConfigVersionThrows400() {
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> sessionService.start(42L, "", java.util.Map.of(),
                        "SCRIPTED", null));
        assertThat(ex.getCode()).isEqualTo(40001);
    }

    // ---- helpers ----

    private void awaitTerminal(long runId) {
        for (int i = 0; i < 200; i++) {
            Run r = runRepo.findById(runId).orElseThrow();
            if (Set.of(RunStatus.COMPLETED.name(), RunStatus.FAILED.name(),
                    RunStatus.CANCELLED.name(), RunStatus.TIMEOUT.name()).contains(r.status())) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
