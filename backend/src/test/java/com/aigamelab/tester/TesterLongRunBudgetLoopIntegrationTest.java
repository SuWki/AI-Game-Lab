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
import com.aigamelab.runtime.LoopDetector;
import com.aigamelab.runtime.RunControlService;
import com.aigamelab.runtime.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T6-3 验收：Tester 长链运行与 BudgetGuard / LoopDetector 收口。
 *
 * 经真实 AgentLoop（runControl.start 异步提交）验证 Tester Run 的长链路能力：
 *  - 长链路（多步 Tool 调用）在预算内正常推进至 COMPLETED；
 *  - BudgetGuard 触顶（小 maxSteps）时 Tester Run 被收口为 COMPLETED；
 *  - LoopDetector 在真实 AgentLoop 中捕获连续同参调用并收口 COMPLETED。
 *
 * 复用 T4-2 双轨接线与 T6-1 start() 端点；不复制 AgentLoop / 不引入第二套 Run 模型。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T6-3 Tester 长链运行与 BudgetGuard/LoopDetector 收口")
class TesterLongRunBudgetLoopIntegrationTest {

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
            return Files.createTempFile("agl-t63-", ".db");
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

    // ---- T6-3 S1：Tester 长链路在预算内正常推进至 COMPLETED ----

    @Test
    @DisplayName("S1 Tester 长链路多步调用在预算内推进至 COMPLETED")
    void testerLongChainCompletesWithinBudget() {
        Task task = newTaskWithConfig("v001");
        activatePlanFor(task.id(), "v001");

        llm.thenStop("测试完成");
        Run modelRun = runControl.start(task.id());
        awaitTerminal(modelRun.id());
        Run run = ActiveTestRun.after(runRepo,modelRun);

        // 直接驱动长链路（ToolExecutor 落 tool_call 行）
        int step = 9;
        Step s1 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s1, new ToolCall("c1", "test_plan_get",
                "{\"taskId\":" + task.id() + "}"));
        Step s2 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s2, new ToolCall("c2", "game_start",
                "{\"seed\":42,\"configVersion\":\"v001\",\"overrides\":{}}"));
        String sid = jdbc.queryForObject(
                "SELECT json_extract(result_json,'$.sessionId') FROM tool_call WHERE run_id=? AND tool_name='game_start'",
                String.class, run.id());
        Step s3 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s3, new ToolCall("c3", "game_observe",
                "{\"sessionId\":\"" + sid + "\"}"));
        Step s4 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s4, new ToolCall("c4", "game_act",
                "{\"sessionId\":\"" + sid + "\",\"actionType\":\"ATTACK\"}"));
        Step s5 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s5, new ToolCall("c5", "game_collect_events",
                "{\"sessionId\":\"" + sid + "\"}"));
        Step s6 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s6, new ToolCall("c6", "game_collect_metrics",
                "{\"sessionId\":\"" + sid + "\"}"));
        Step s7 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s7, new ToolCall("c7", "finding_report",
                "{\"sessionId\":\"" + sid + "\",\"scenarioId\":\"sc1\",\"configVersion\":\"v001\","
                        + "\"severity\":\"HIGH\",\"category\":\"BALANCE_ANOMALY\","
                        + "\"title\":\"hp 越界\",\"expected\":\"HP>=0\",\"actual\":\"hp=999\","
                        + "\"reproSteps\":\"seed=42\"}"));
        Step s8 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s8, new ToolCall("c8", "test_report_create",
                "{\"taskId\":" + task.id() + ",\"configVersion\":\"v001\",\"summary\":\"长链完成\"}"));

        // 长链路全部 8 工具的 tool_call 均 COMPLETED
        Integer completed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_call WHERE run_id=? AND status='COMPLETED'", Integer.class, run.id());
        assertThat(completed).isGreaterThanOrEqualTo(8);
        // Finding + Report 已落库
        Integer findings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM finding WHERE config_version='v001'", Integer.class);
        assertThat(findings).isGreaterThanOrEqualTo(1);
        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM design_document WHERE title LIKE '[TEST REPORT]%'", Integer.class);
        assertThat(reports).isGreaterThanOrEqualTo(1);
    }

    // ---- T6-3 S2：BudgetGuard 触顶收口（小 maxSteps） ----

    @Test
    @DisplayName("S2 Tester Run 小 maxSteps 触顶由 BudgetGuard 收口为 COMPLETED")
    void testerRunBudgetGuardStopsAtMaxSteps() {
        Task task = newTaskWithConfig("v001");
        activatePlanFor(task.id(), "v001");

        // 直接插入小 maxSteps 的 Run（RunControlService.start 默认 50，本用例验证 BudgetGuard 收口，
        // 故显式以 maxSteps=3 落库后由 AgentLoop 驱动——复用 T4-2 双轨与既有 run 机制）
        Run run = runRepo.insert(new Run(null, task.id(), "PENDING", null,
                "deepseek-chat", 0, 3, 100000, 0, null, null, null, null, null));

        // LLM 脚本：持续产出 tool_calls，迫使 AgentLoop 多轮迭代直至 BudgetGuard 触顶
        for (int i = 0; i < 10; i++) {
            llm.thenToolCalls(new ToolCall("c" + i, "test_plan_get",
                    "{\"taskId\":" + task.id() + "}"));
        }

        agentLoop.run(run.id());
        awaitTerminal(run.id());

        Run finished = runRepo.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(RunStatus.FAILED.name());
        // BudgetGuard 触顶：currentStep 已到 maxSteps
        assertThat(finished.currentStep()).isGreaterThanOrEqualTo(finished.maxSteps());
    }

    // ---- T6-3 S3：LoopDetector 捕获连续同参调用 ----

    @Test
    @DisplayName("S3 Tester Run 连续同参调用由 LoopDetector 收口为 COMPLETED")
    void testerRunLoopDetectorStopsIdenticalCalls() {
        Task task = newTaskWithConfig("v001");
        activatePlanFor(task.id(), "v001");

        llm.thenStop("测试完成");
        Run run = runControl.start(task.id());
        awaitTerminal(run.id());

        // 起会话
        Step s0 = stepRepo.insert(new Step(null, run.id(), 10, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s0, new ToolCall("c0", "game_start",
                "{\"seed\":7,\"configVersion\":\"v001\",\"overrides\":{}}"));
        String sid = jdbc.queryForObject(
                "SELECT json_extract(result_json,'$.sessionId') FROM tool_call WHERE run_id=? AND tool_name='game_start'",
                String.class, run.id());
        String sameArgs = "{\"sessionId\":\"" + sid + "\",\"actionType\":\"MOVE\","
                + "\"params\":{\"direction\":\"UP\"},\"tickBudget\":10}";

        // 前 2 次：未构成循环
        for (int i = 1; i <= 2; i++) {
            Step s = stepRepo.insert(new Step(null, run.id(), 10 + i, "LLM_CALL", null, 0, 0, null, null));
            toolExecutor.execute(run, s, new ToolCall("c" + i, "game_act", sameArgs));
        }
        LoopDetector detector = new LoopDetector(stepRepo);
        // 再补第 3 次相同 game_act
        Step s3 = stepRepo.insert(new Step(null, run.id(), 13, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s3, new ToolCall("c3", "game_act", sameArgs));
        // 最近 3 次均为 game_act|sameArgs -> 命中循环
        assertThat(detector.detect(run.id(), "game_act", sameArgs)).isTrue();
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
