package com.aigamelab.p8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.LlmException;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.AgentEventRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.RunControlService;

/**
 * P8-2 Exception Path E2E（§6 / §7 / §8 / §9 / §10）。
 *
 * 用例：
 * §6  LlmException 50201 → Run=FAILED + no proposal
 * §7  Cancel  mid-run     → Run=CANCELLED
 * §8  Approval→Resume     → WAITING_APPROVAL→RUNNING→COMPLETED
 * §9  maxSteps 触顶       → Run=COMPLETED at step boundary
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P8-2 Exception Path E2E")
class P8E2EExceptionPathTest {

    @Autowired RunControlService runControl;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired AgentEventRepository eventRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApprovalService approval;
    @Autowired ConfigVersionService configService;
    @Autowired FakeLlmGateway llm;

    static Path tempDb() {
        try { return Files.createTempFile("agl-p8-ex-", ".db"); }
        catch (Exception e) { throw new IllegalStateException(e); }
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
        @Bean FakeLlmGateway fakeLlmGateway() { return new FakeLlmGateway(); }
    }

    private static final String MODIFY_ARGS =
            "{\"patch\":{\"weapons\":[{\"id\":\"nova_sniper\",\"critMultiplier\":2.5}]},"
            + "\"reason\":\"E2E exception test\"}";

    private static final String CREATE_PROPOSAL_ARGS =
            "{\"title\":\"E2E maxSteps test\",\"reason\":\"trigger tool without approval\",\"candidates\":[],\"evidence\":[]}";

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
        jdbc.update(
                "INSERT INTO game_config_version(version, config_json, parent_version, change_proposal_id, comment) "
                + "VALUES(?,?,?,?,?)",
                "v001", readSeed(), null, null, "initial seed");
    }

    private String readSeed() {
        try (var in = new org.springframework.core.io.ClassPathResource("config/v001.json").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private Task newTask(int maxSteps) {
        return taskRepo.insert(new Task(
                null, "E2E exception test", "P8-2 exception path",
                "{}", maxSteps, false, "ACTIVE", null));
    }

    private int eventCount(long runId, String type) {
        String rid = String.valueOf(runId);
        return (int) eventRepo.findRecent(10000).stream()
                .filter(e -> rid.equals(e.runId()) && e.type().equals(type)).count();
    }

    private int proposalCount(long runId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_proposal WHERE run_id=?", Integer.class, runId);
    }

    private void awaitStatus(long runId, String expected, int maxAttempts) {
        for (int i = 0; i < maxAttempts; i++) {
            Run r = runRepo.findById(runId).orElseThrow();
            if (expected.equals(r.status())) return;
            if ("FAILED".equals(r.status())) {
                System.err.println("[DIAG-EX] Run " + runId + " FAILED early. error="
                        + r.errorMessage() + " finalResult=" + r.finalResult()
                        + " step=" + r.currentStep());
                break;
            }
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        Run r = runRepo.findById(runId).orElseThrow();
        assertThat(r.status()).as("Run %d did not reach %s (actual=%s, error=%s)",
                runId, expected, r.status(), r.errorMessage()).isEqualTo(expected);
    }

    // ========== §6 LlmException → 50201 (AgentLoop path: Run=FAILED, no proposal) ==========

    /**
     * AgentLoop 收到 LlmException → 捕获 RuntimeException 分支 → Run=FAILED。
     * GlobalExceptionHandler 50201 映射仅作用于 Controller 边界，AgentLoop 内部不走 HTTP。
     */
    @Test
    void llmFailure_agentLoopRunFailed_noProposal() {
        Task task = newTask(5);
        llm.thenFailure(new LlmException(LlmException.Kind.NON_RETRYABLE, 502, "upstream timeout", false));

        Run run = runControl.start(task.id());
        // awaitTerminal 等 FAILED
        for (int i = 0; i < 200; i++) {
            String s = runRepo.findById(run.id()).orElseThrow().status();
            if ("FAILED".equals(s) || "COMPLETED".equals(s)) break;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // Run FAILED
        assertThat(runRepo.findById(run.id()).orElseThrow().status()).isEqualTo("FAILED");
        // 无 proposal 创建（FakeLlmGateway.thenFailure → 异常在 LLM 调用阶段抛出，ToolExecutor 未执行）
        assertThat(proposalCount(run.id())).isZero();
        // 无 APPROVAL_REQUIRED 事件
        assertThat(eventCount(run.id(), "APPROVAL_REQUIRED")).isZero();
    }

    // ========== §7 Cancel mid-run ==========

    /**
     * 正常启动 Run，LLM 正常工作，中途调 cancel → Run=CANCELLED。
     */
    @Test
    void cancelMidRun_setsCancelled() {
        Task task = newTask(50);
        // LLM 剧本：第一步 tool_call，后续都 stop（测试将中途 cancel）
        llm.thenToolCalls(new ToolCall("c1", "modify_game_config", MODIFY_ARGS))
                .thenStop("will not reach");

        Run run = runControl.start(task.id());
        // 等到 WAITING_APPROVAL（modify_game_config 触发后）
        awaitStatus(run.id(), "WAITING_APPROVAL", 200);

        // 执行 cancel
        runControl.cancel(run.id());

        // 等终态
        awaitStatus(run.id(), "CANCELLED", 200);

        Run finished = runRepo.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo("CANCELLED");
        // RUN_CANCELLED 事件已发布
        assertThat(eventCount(run.id(), "RUN_CANCELLED")).isGreaterThanOrEqualTo(1);
    }

    // ========== §8 Approval Resume → RUNNING → COMPLETED ==========

    /**
     * Golden Path 的 approve→resume 子链路显式验证：
     * WAITING_APPROVAL → approve → RUN_RESUMED → RUNNING → thenStop → COMPLETED。
     */
    @Test
    void approvalResume_setsRunningThenCompleted() {
        Task task = newTask(10);
        // LLM: Step1=tool_call → SUSPEND; Step2(after resume)=stop
        llm.thenToolCalls(new ToolCall("c1", "modify_game_config", MODIFY_ARGS))
                .thenStop("resumed and done");

        Run run = runControl.start(task.id());
        awaitStatus(run.id(), "WAITING_APPROVAL", 200);

        Map<String, Object> proposal = jdbc.queryForMap(
                "SELECT id FROM change_proposal WHERE run_id=? ORDER BY id DESC LIMIT 1",
                run.id());
        long proposalId = ((Number) proposal.get("id")).longValue();

        // approve
        approval.approve(proposalId, "resume test");

        // 等终态
        awaitStatus(run.id(), "COMPLETED", 300);

        Run finished = runRepo.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo("COMPLETED");

        // RUN_RESUMED 事件精确 1 次（resume 仅调用 1 次）
        assertThat(eventCount(run.id(), "RUN_RESUMED")).isEqualTo(1);
        // CONFIG_VERSION_CREATED 精确 1 次
        assertThat(eventCount(run.id(), "CONFIG_VERSION_CREATED")).isEqualTo(1);
        // STEP_STARTED 至少 2 次（suspend 前 + resume 后）
        assertThat(eventCount(run.id(), "STEP_STARTED")).isGreaterThanOrEqualTo(2);
        // PROPOSAL_CREATED 精确 1 次
        assertThat(eventCount(run.id(), "PROPOSAL_CREATED")).isEqualTo(1);
    }

    // ========== §9 maxSteps BudgetGuard 触顶 ==========

    /**
     * Task maxSteps=3，FakeLlmGateway 每次都返回 create_change_proposal（requiresApproval=false，
     * 不会挂起），AgentLoop 连续 step → BudgetGuard 在 step>=maxSteps 时判定 COMPLETED。
     */
    @Test
    void maxSteps_budgetGuardStopsAtBoundary() {
        Task task = newTask(3);
        // 连续 4 个 create_change_proposal（requiresApproval=false，不会挂起）
        // 第 4 个应永远不会被调用，因为 step=3 触顶
        llm.thenToolCalls(new ToolCall("c1", "create_change_proposal", CREATE_PROPOSAL_ARGS))
                .thenToolCalls(new ToolCall("c2", "create_change_proposal", CREATE_PROPOSAL_ARGS))
                .thenToolCalls(new ToolCall("c3", "create_change_proposal", CREATE_PROPOSAL_ARGS))
                .thenToolCalls(new ToolCall("c4", "create_change_proposal", CREATE_PROPOSAL_ARGS));

        Run run = runControl.start(task.id());

        // Budget/loop termination does not certify goal completion.
        awaitStatus(run.id(), "FAILED", 300);

        Run finished = runRepo.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo("FAILED");

        // currentStep >= maxSteps (3)
        assertThat(finished.currentStep()).isGreaterThanOrEqualTo(3);

        // STEP_STARTED 至少 3 次（每 step 一次）
        assertThat(eventCount(run.id(), "STEP_STARTED")).isGreaterThanOrEqualTo(3);
        // TOOL_CALLED 至少 3 次（每步 dispatch 一次）
        assertThat(eventCount(run.id(), "TOOL_CALLED")).isGreaterThanOrEqualTo(3);
    }
}
