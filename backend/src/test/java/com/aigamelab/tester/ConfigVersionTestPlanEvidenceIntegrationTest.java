package com.aigamelab.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.domain.Step;
import com.aigamelab.domain.Task;
import com.aigamelab.domain.TestPlan;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.StepRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.repository.TestPlanRepository;
import com.aigamelab.runtime.AgentLoop;
import com.aigamelab.runtime.RunControlService;
import com.aigamelab.runtime.ToolExecutor;
import com.aigamelab.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T6-2 验收：Config Version × TestPlan × Tester Evidence 集成验证。
 *
 * 验证配置版本在"被测对象"链路上的端到端一致性：
 *   Task.constraints_json.configVersion  →  TestPlan.configVersion  →  Tester Evidence(Finding.config_version)
 * 以及非法/不存在的 configVersion 在 Evidence 上报链路被拒绝（契约 1：被测对象必须是 ConfigVersion）。
 *
 * 不新增第二套 Run 模型、不复制 AgentLoop，复用 T4-2 双轨接线与 T6-1 的 start() 端点。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T6-2 ConfigVersion×TestPlan×Evidence 集成")
class ConfigVersionTestPlanEvidenceIntegrationTest {

    @Autowired RunControlService runControl;
    @Autowired AgentLoop agentLoop;
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
            return Files.createTempFile("agl-t62-", ".db");
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

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("§1 configVersion 贯穿 Task→TestPlan→Evidence 单一事实源")
    void configVersionPropagatesEndToEnd() {
        // 被测对象 = v001（已落库的 ConfigVersion）
        String cfg = "v001";
        Task task = newTaskWithConfig(cfg);
        TestPlan plan = activatePlanFor(task.id(), cfg);

        llm.thenStop("测试完成");
        Run completed = runControl.start(task.id());
        awaitTerminal(completed.id());
        Run run = ActiveTestRun.after(runRepo,completed);

        // 经 ToolExecutor 驱动完整 8 工具链路（configVersion 透传）
        int step = 9;
        Step s1 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s1, new ToolCall("c1", "test_plan_get",
                "{\"taskId\":" + task.id() + "}"));
        Step s2 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s2, new ToolCall("c2", "game_start",
                "{\"seed\":42,\"configVersion\":\"" + cfg + "\",\"overrides\":{}}"));
        String sid = jdbc.queryForObject(
                "SELECT json_extract(result_json,'$.sessionId') FROM tool_call WHERE run_id=? AND tool_name='game_start'",
                String.class, run.id());

        // finding_report 上报 Evidence，configVersion 须与 Plan/Task 一致
        Step s7 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s7, new ToolCall("c7", "finding_report",
                "{\"sessionId\":\"" + sid + "\",\"scenarioId\":\"sc1\",\"configVersion\":\""
                        + cfg + "\",\"severity\":\"HIGH\",\"category\":\"BALANCE_ANOMALY\","
                        + "\"title\":\"hp 越界\",\"expected\":\"HP>=0\",\"actual\":\"hp=999\","
                        + "\"reproSteps\":\"seed=42\"}"));

        // Evidence 落库：config_version 与 Task/Plan 一致
        String findingCfg = jdbc.queryForObject(
                "SELECT config_version FROM finding WHERE config_version=?", String.class, cfg);
        assertThat(findingCfg).isEqualTo(cfg);

        // 单一事实源一致性：Task.constraints.configVersion == Plan.configVersion == Finding.configVersion
        String taskCfg = json(task.constraintsJson()).get("configVersion").asText();
        assertThat(taskCfg).isEqualTo(plan.configVersion());
        assertThat(plan.configVersion()).isEqualTo(findingCfg);

        // test_report_create 汇总该 configVersion 的 Finding
        Step s8 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s8, new ToolCall("c8", "test_report_create",
                "{\"taskId\":" + task.id() + ",\"configVersion\":\"" + cfg + "\",\"summary\":\"已完成\"}"));
        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM design_document WHERE title LIKE '[TEST REPORT]%'", Integer.class);
        assertThat(reports).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("§2 未知 configVersion 拒绝落库（PF-003 清偿 TD-T6-001：existence 校验）")
    void unknownConfigVersionRejectedByEvidence() {
        String cfg = "v001";
        Task task = newTaskWithConfig(cfg);
        activatePlanFor(task.id(), cfg);

        llm.thenStop("测试完成");
        Run completed = runControl.start(task.id());
        awaitTerminal(completed.id());
        Run run = ActiveTestRun.after(runRepo,completed);

        // 起一个真实会话（合法 v001）
        Step s2 = stepRepo.insert(new Step(null, run.id(), 10, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s2, new ToolCall("c2", "game_start",
                "{\"seed\":42,\"configVersion\":\"v001\",\"overrides\":{}}"));
        String sid = jdbc.queryForObject(
                "SELECT json_extract(result_json,'$.sessionId') FROM tool_call WHERE run_id=? AND tool_name='game_start'",
                String.class, run.id());

        // finding_report 传不存在的 configVersion=v999。
        // PF-003（TD-T6-001 清偿）：FindingService.report() 校验 configVersion 存在，
        // v999 → BusinessException NOT_FOUND → AbstractTesterTool 翻译为【非 critical】
        // ToolException → tool_call=FAILED、Run 继续、Finding 不落库。
        Step s7 = stepRepo.insert(new Step(null, run.id(), 11, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s7, new ToolCall("c7", "finding_report",
                "{\"sessionId\":\"" + sid + "\",\"scenarioId\":\"sc1\",\"configVersion\":\"v999\","
                        + "\"severity\":\"HIGH\",\"category\":\"BALANCE_ANOMALY\","
                        + "\"title\":\"hp 越界\",\"expected\":\"HP>=0\",\"actual\":\"hp=999\","
                        + "\"reproSteps\":\"seed=42\"}"));

        // 契约 1（对象必须是真实 ConfigVersion）强制：
        // 1) tool_call 记录为 FAILED（非 COMPLETED），Run 不因之 FAILED（非 critical）；
        Integer failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_call WHERE status='FAILED' AND tool_name='finding_report'"
                        + " AND error LIKE '%40401%'",
                Integer.class);
        assertThat(failed).isGreaterThanOrEqualTo(1);
        // 2) Finding 绝不落库 v999。
        Integer badCfg = jdbc.queryForObject(
                "SELECT COUNT(*) FROM finding WHERE config_version='v999'", Integer.class);
        assertThat(badCfg).isZero();
        // 3) Run 终态未因未知版本变 FAILED（非 critical 工具失败不中断）。
        assertThat(runRepo.findById(run.id()).orElseThrow().status())
                .isNotEqualTo("FAILED");
    }

    @Test
    @DisplayName("§3 test_plan_get 仅暴露 ACTIVE 计划且回显 configVersion")
    void testPlanGetEchoesConfigVersion() {
        String cfg = "v001";
        Task task = newTaskWithConfig(cfg);
        activatePlanFor(task.id(), cfg);

        llm.thenStop("测试完成");
        Run completed = runControl.start(task.id());
        awaitTerminal(completed.id());
        Run run = ActiveTestRun.after(runRepo,completed);

        Step s1 = stepRepo.insert(new Step(null, run.id(), 10, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s1, new ToolCall("c1", "test_plan_get",
                "{\"taskId\":" + task.id() + "}"));

        // 回显的 ACTIVE 计划携带 configVersion
        String cfgEcho = jdbc.queryForObject(
                "SELECT json_extract(result_json,'$.activePlans[0].configVersion') FROM tool_call WHERE run_id=? AND tool_name='test_plan_get'",
                String.class, run.id());
        assertThat(cfgEcho).isEqualTo(cfg);
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
