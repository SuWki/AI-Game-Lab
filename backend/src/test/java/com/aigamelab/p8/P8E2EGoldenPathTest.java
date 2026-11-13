package com.aigamelab.p8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

import com.aigamelab.config.ApprovalService;
import com.aigamelab.config.ConfigVersionService;
import com.aigamelab.domain.AgentEvent;
import com.aigamelab.domain.ConfigVersion;
import com.aigamelab.domain.Experiment;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.domain.Task;
import com.aigamelab.experiment.ExperimentService;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.AgentEventRepository;
import com.aigamelab.repository.ExperimentRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.RunControlService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P8-2 Golden Path E2E（§2 / §3 / §4 / §5）。
 *
 * 验证 PROJECT ORBITAL 端到端链路：
 * Task → Run → AgentLoop（FakeLlmGateway 剧本）→ modify_game_config → Proposal(PENDING)
 *   → APPROVAL_REQUIRED → Run=WAITING_APPROVAL
 *   → ApprovalService.approve() → ConfigVersion v002 → RUN_RESUMED
 *   → AgentLoop 续跑 → stop → Run=COMPLETED
 *   → ExperimentService.create() → Experiment=DONE + resultJson
 *
 * 全部通过真实 Spring Context + 真实 SQLite + 真实 Service/Repository/Controller 接线验证。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P8-2 Golden Path E2E")
class P8E2EGoldenPathTest {

    @Autowired RunControlService runControl;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired AgentEventRepository eventRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApprovalService approval;
    @Autowired ConfigVersionService configService;
    @Autowired ExperimentService experimentService;
    @Autowired FakeLlmGateway llm;

    static final ObjectMapper M = new ObjectMapper();

    // modify_game_config args: patch nova_sniper.critMultiplier = 2.5
    private static final String MODIFY_ARGS =
            "{\"patch\":{\"weapons\":[{\"id\":\"nova_sniper\",\"critMultiplier\":2.5}]},"
            + "\"reason\":\"平衡三 Build 强度，降低 crit 倍率\"}";

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p8-gp-", ".db");
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
        @Bean FakeLlmGateway fakeLlmGateway() {
            return new FakeLlmGateway();
        }
    }

    @BeforeEach
    void clean() {
        llm.reset();
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM change_proposal");
        jdbc.update("DELETE FROM game_config_version");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
        // 种子 v001
        jdbc.update(
                "INSERT INTO game_config_version(version, config_json, parent_version, change_proposal_id, comment) "
                + "VALUES(?,?,?,?,?)",
                "v001", readSeed(), null, null, "initial seed");
    }

    private String readSeed() {
        try (var in = new org.springframework.core.io.ClassPathResource("config/v001.json").getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** LLM 剧本：Step 1 = 调 modify_game_config；Step 2（resume 后） = stop */
    private void configureLlm() {
        llm.thenToolCalls(new ToolCall("c1", "modify_game_config", MODIFY_ARGS))
                .thenStop("配置修改已完成，实验任务结束");
    }

    private Task newTask(int maxSteps) {
        return taskRepo.insert(new Task(
                null, "平衡三 Build", "降低 crit 倍率缩小强度差距",
                "{}", maxSteps, false, "ACTIVE", null));
    }

    private long latestProposalRunId() {
        // 取最新 PENDING 提案关联的 run_id
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT run_id FROM change_proposal ORDER BY id DESC LIMIT 1");
        Object v = rows.isEmpty() ? null : rows.get(0).get("run_id");
        return v == null ? 0L : ((Number) v).longValue();
    }

    private int eventCount(long runId, String type) {
        String rid = String.valueOf(runId);
        return (int) eventRepo.findRecent(10000).stream()
                .filter(e -> rid.equals(e.runId()) && e.type().equals(type))
                .count();
    }

    /** 轮询等待 Run 进入指定状态。 */
    private void awaitStatus(long runId, String expected, int maxAttempts) {
        for (int i = 0; i < maxAttempts; i++) {
            Run r = runRepo.findById(runId).orElseThrow();
            if (expected.equals(r.status())) return;
            if ("FAILED".equals(r.status())) {
                System.err.println("[DIAG] Run " + runId + " FAILED early. error="
                        + r.errorMessage() + " finalResult=" + r.finalResult()
                        + " step=" + r.currentStep());
                break;
            }
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        Run r = runRepo.findById(runId).orElseThrow();
        assertThat(r.status()).as("Run %d 未达到状态 %s (actual=%s, error=%s)",
                runId, expected, r.status(), r.errorMessage()).isEqualTo(expected);
    }

    /** 轮询等待 Run 进入终态。 */
    private void awaitTerminal(long runId) {
        awaitStatus(runId, RunStatus.COMPLETED.name(), 300);
    }

    // ==================== Golden Path 全链路 ====================

    /**
     * §2 + §3 + §4 + §5：完整 Golden Path。
     * Task → Run → modify_game_config → PENDING → approve → v002 → Resume → stop → COMPLETED → Experiment DONE。
     */
    @Test
    void fullDesignerGoldenPath() {
        // ---- Step 1: 初始化 ----
        Task task = newTask(10);
        configureLlm();

        // ---- Step 2-3: 创建 Run ----
        Run run = runControl.start(task.id());

        // ---- Step 4: 等待 WAITING_APPROVAL（modify_game_config 触发） ----
        awaitStatus(run.id(), "WAITING_APPROVAL", 200);

        // 验证 §5：Proposal 已创建，状态 = PENDING
        Map<String, Object> proposal = jdbc.queryForMap(
                "SELECT * FROM change_proposal WHERE run_id=? ORDER BY id DESC LIMIT 1",
                run.id());
        assertThat(proposal.get("status")).isEqualTo("PENDING");
        String afterConfig = (String) proposal.get("after_config_json");
        assertThat(afterConfig).isNotNull().contains("2.5");
        String diffJson = (String) proposal.get("diff_json");
        assertThat(diffJson).isNotNull().contains("critMultiplier");
        assertThat(((String) proposal.get("base_version"))).isEqualTo("v001");

        // 验证 §5 事件
        assertThat(eventCount(run.id(), "PROPOSAL_CREATED")).isEqualTo(1);
        assertThat(eventCount(run.id(), "APPROVAL_REQUIRED")).isEqualTo(1);

        // 验证 Run 状态 = WAITING_APPROVAL
        assertThat(runRepo.findById(run.id()).orElseThrow().status()).isEqualTo("WAITING_APPROVAL");

        // ---- Step 6: 批准 ----
        long proposalId = ((Number) proposal.get("id")).longValue();
        ConfigVersion created = approval.approve(proposalId, "E2E 批准");

        // 验证 §3 ConfigVersion：新版本创建
        assertThat(created.version()).isNotEqualTo("v001");
        assertThat(configService.findLatest().orElseThrow().version()).isEqualTo(created.version());
        // 历史版本未修改
        ConfigVersion v001 = configService.findByVersion("v001").orElseThrow();
        assertThat(v001.version()).isEqualTo("v001");

        // 验证 §3 事件：CONFIG_VERSION_CREATED 已落库
        assertThat(eventCount(run.id(), "CONFIG_VERSION_CREATED")).isEqualTo(1);

        // 验证 Proposal → APPROVED + targetVersion 回填
        Map<String, Object> approved = jdbc.queryForMap(
                "SELECT status, target_version FROM change_proposal WHERE id=?", proposalId);
        assertThat(approved.get("status")).isEqualTo("APPROVED");
        assertThat(approved.get("target_version")).isEqualTo(created.version());

        // ---- Step 7: 等待 Resume → COMPLETED ----
        // resume 是异步的（executor.submit agentLoop.run），轮询等终态
        awaitTerminal(run.id());

        Run finished = runRepo.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(RunStatus.COMPLETED.name());

        // Agent Loop 确实续跑（RESUMED 事件 + 第二次 STEP_STARTED）
        assertThat(eventCount(run.id(), "RUN_RESUMED")).isGreaterThanOrEqualTo(1);
        assertThat(eventCount(run.id(), "STEP_STARTED")).isGreaterThanOrEqualTo(2);

        // ---- Step 8: Experiment ----
        ExperimentService.ExperimentResult result = experimentService.create(
                created.version(), 12345L, 100, "ALL_BUILDS", run.id());
        assertThat(result.experiment().status()).isEqualTo("DONE");
        assertThat(result.experiment().resultJson()).isNotNull();
        assertThat(result.experiment().configVersion()).isEqualTo(created.version());

        // 验证 §5 Experiment 数据完整
        Experiment exp = result.experiment();
        assertThat(exp.simulationCount()).isEqualTo(100);
        assertThat(exp.seed()).isEqualTo(12345L);
        assertThat(exp.scenario()).isEqualTo("ALL_BUILDS");
    }
}
