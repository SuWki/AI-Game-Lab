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
import com.aigamelab.domain.Task;
import com.aigamelab.domain.TestPlan;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.domain.Step;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.StepRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.repository.TestPlanRepository;
import com.aigamelab.runtime.AgentLoop;
import com.aigamelab.runtime.RunControlService;
import com.aigamelab.runtime.ToolExecutor;
import com.aigamelab.tool.StubTool;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolRegistry;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T6-1 验收：Experiment / Task → Tester Run 对接。
 *
 * 验证"从 Task 启动 Tester Run"的完整链路：
 * Task（含 constraints_json 的 configVersion）+ ACTIVE TestPlan
 *   → RunControlService.start(taskId) 创建 PENDING Run 并异步提交 AgentLoop
 *   → AgentLoop.isTesterRun 判定为 Tester Run（tester_p001 + 8-tool 白名单）。
 *
 * 不新增第二套 Run 模型、不复制 AgentLoop，复用既有的 T4-2 双轨接线。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T6-1 Experiment/Task → Tester Run 对接")
class TesterExperimentIntegrationTest {

    @Autowired RunControlService runControl;
    @Autowired AgentLoop agentLoop;
    @Autowired RunRepository runRepo;
    @Autowired StepRepository stepRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired TestPlanRepository planRepo;
    @Autowired ToolRegistry registryBean;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;
    @Autowired GameStartTool gameStartTool;
    @Autowired GameObserveTool gameObserveTool;
    @Autowired GameActTool gameActTool;
    @Autowired GameCollectEventsTool gameCollectEventsTool;
    @Autowired GameCollectMetricsTool gameCollectMetricsTool;
    @Autowired FindingReportTool findingReportTool;
    @Autowired TestReportCreateTool testReportCreateTool;
    @Autowired TestPlanGetTool testPlanGetTool;
    @Autowired ToolExecutor toolExecutor;

    static final ObjectMapper M = new ObjectMapper();

    static final Set<String> EXPECTED_TESTER_TOOLS = Set.of(
            "test_plan_get", "game_start", "game_observe", "game_act",
            "game_collect_events", "game_collect_metrics", "finding_report", "test_report_create");

    /** R0-8：Designer 显式白名单（能力隔离）+ R2-1 additive 研究消费工具 */
    static final Set<String> EXPECTED_DESIGNER_TOOLS = Set.of(
            "get_game_config", "modify_game_config", "simulate_game",
            "analyze_experiment", "compare_experiments",
            "get_game_design", "create_design_document", "create_change_proposal",
            "get_research_context", "get_research_claims");

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-t61-", ".db");
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

    private static String sessionOf(ToolResult r) {
        return json(r.payloadJson()).get("sessionId").asText();
    }

    @Test
    void taskWithActivePlanStartsTesterRun() {
        Task task = newTaskWithConfig("v001");
        activatePlanFor(task.id(), "v001");

        StubTool stray = new StubTool("stub_isolated_x");
        registryBean.register(stray);

        // 剧本：测试循环 → 结束
        llm.thenStop("测试完成");

        Run run = runControl.start(task.id());

        // Run 创建并异步执行（等待其终态）
        awaitTerminal(run.id());

        // 验证：Tester prompt 被使用
        assertThat(llm.recordedRequests()).isNotEmpty();
        String firstPrompt = llm.recordedRequests().get(0).messages().get(0).content();
        assertThat(firstPrompt).contains("AI Game Tester");
        assertThat(firstPrompt).doesNotContain("AGL game designer agent");

        // 验证：工具白名单隔离
        Set<String> names = toolNamesOf(0);
        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_TESTER_TOOLS);
        assertThat(names).doesNotContain("stub_isolated_x");

        // 验证：系 tester Run 终态为 COMPLETED
        Run finished = runRepo.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(RunStatus.COMPLETED.name());
    }

    @Test
    void taskWithoutActivePlanStartsDesignerRun() {
        Task task = newTaskWithConfig("v001");
        // 无 ACTIVE TestPlan（纯 Designer Run）

        StubTool stray = new StubTool("stub_designer_y");
        registryBean.register(stray);

        llm.thenStop("设计完成");

        Run run = runControl.start(task.id());
        awaitTerminal(run.id());

        // Designer prompt
        String firstPrompt = llm.recordedRequests().get(0).messages().get(0).content();
        assertThat(firstPrompt).isEqualTo("You are AGL game designer agent.");

        // R0-8：Designer 工具子集 = 显式 8 个；测试桩与 Tester/Research 工具均被隔离
        Set<String> names = toolNamesOf(0);
        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_DESIGNER_TOOLS);
        assertThat(names).doesNotContain("stub_designer_y");
        for (String t : EXPECTED_TESTER_TOOLS) {
            assertThat(names).doesNotContain(t);
        }
        assertThat(names).doesNotContain("web_search", "fetch_page", "save_source",
                "extract_claims", "compare_sources", "generate_report");
    }

    @Test
    void draftPlanDoesNotTriggerTesterMode() {
        Task task = newTaskWithConfig("v001");
        // 仅 DRAFT（非 ACTIVE）→ 仍 Designer
        planRepo.insert(new TestPlan(null, task.id(), "v001",
                TestPlan.STATUS_DRAFT, "[]", null));

        llm.thenStop("设计完成");
        Run run = runControl.start(task.id());
        awaitTerminal(run.id());

        assertThat(llm.recordedRequests().get(0).messages().get(0).content())
                .isEqualTo("You are AGL game designer agent.");
    }

    @Test
    void testerRunUsesOnlyEightTesterToolsEndToEnd() {
        // 先经 AgentLoop 跑一次（建立 Tester Run 上下文），再通过 ToolExecutor 直接驱动 8 工具
        // 验证 tool_call 表仅含 8 个 Tester Tool，且 Finding / Test Report 落库。
        Task task = newTaskWithConfig("v001");
        activatePlanFor(task.id(), "v001");

        llm.thenStop("测试完成");
        Run completed = runControl.start(task.id());
        awaitTerminal(completed.id());
        Run run = ActiveTestRun.after(runRepo,completed);

        // 通过 ToolExecutor 驱动完整 8 工具链路（ToolExecutor 会落 tool_call 行）
        // 从 seq=10 开始避免与 AgentLoop 已创建的步骤冲突（UNIQUE constraint: run_id+seq）
        int step = 9;
        // 1. test_plan_get
        Step s1 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s1, new ToolCall("c1", "test_plan_get",
                "{\"taskId\":" + task.id() + "}"));
        // 2. game_start
        Step s2 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s2, new ToolCall("c2", "game_start",
                "{\"seed\":42,\"configVersion\":\"v001\",\"overrides\":{}}"));
        // 从 tool_call.result_json 提取真实 sessionId（game_start 创建随机会话）
        String sid = jdbc.queryForObject(
                "SELECT json_extract(result_json,'$.sessionId') FROM tool_call WHERE run_id=? AND tool_name='game_start'",
                String.class, run.id());
        // 3. game_observe
        Step s3 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s3, new ToolCall("c3", "game_observe",
                "{\"sessionId\":\"" + sid + "\"}"));
        // 4. game_act
        Step s4 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s4, new ToolCall("c4", "game_act",
                "{\"sessionId\":\"" + sid + "\",\"actionType\":\"ATTACK\"}"));
        // 5. game_collect_events
        Step s5 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s5, new ToolCall("c5", "game_collect_events",
                "{\"sessionId\":\"" + sid + "\"}"));
        // 6. game_collect_metrics
        Step s6 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s6, new ToolCall("c6", "game_collect_metrics",
                "{\"sessionId\":\"" + sid + "\"}"));
        // 7. finding_report
        Step s7 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s7, new ToolCall("c7", "finding_report",
                "{\"sessionId\":\"" + sid + "\",\"scenarioId\":\"sc1\",\"configVersion\":\"v001\","
                        + "\"severity\":\"HIGH\",\"category\":\"BALANCE_ANOMALY\","
                        + "\"title\":\"hp 越界\",\"expected\":\"HP>=0\",\"actual\":\"hp=999\","
                        + "\"reproSteps\":\"seed=42\"}"));
        // 8. test_report_create
        Step s8 = stepRepo.insert(new Step(null, run.id(), ++step, "LLM_CALL", null, 0, 0, null, null));
        toolExecutor.execute(run, s8, new ToolCall("c8", "test_report_create",
                "{\"taskId\":" + task.id() + ",\"configVersion\":\"v001\",\"summary\":\"已完成\"}"));

        // 所有 tool_call 名称应仅属于 8 个 Tester Tool
        List<String> executedTools = jdbc.queryForList(
                "SELECT DISTINCT tool_name FROM tool_call WHERE run_id=?", run.id())
                .stream().map(m -> (String) m.get("tool_name")).toList();
        assertThat(executedTools).containsExactlyInAnyOrderElementsOf(EXPECTED_TESTER_TOOLS);

        // 验证 Finding 与 Test Report 已落库
        Integer findings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM finding WHERE config_version='v001'", Integer.class);
        assertThat(findings).isGreaterThanOrEqualTo(1);
        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM design_document WHERE title LIKE '[TEST REPORT]%'",
                Integer.class);
        assertThat(reports).isGreaterThanOrEqualTo(1);
    }

    // ---- helpers ----

    private Set<String> toolNamesOf(int requestIndex) {
        var req = llm.recordedRequests().get(requestIndex);
        var specs = req.tools();
        return specs == null ? Set.of()
                : Set.copyOf(specs.stream().map(com.aigamelab.llm.dto.LlmToolSpec::name).toList());
    }

    /** 轮询等待 Run 进入终态（异步执行由 executor 提交）。 */
    private void awaitTerminal(long runId) {
        for (int i = 0; i < 200; i++) {
            Run r = runRepo.findById(runId).orElseThrow();
            if (RunStatus.COMPLETED.name().equals(r.status())
                    || RunStatus.FAILED.name().equals(r.status())
                    || RunStatus.CANCELLED.name().equals(r.status())
                    || RunStatus.TIMEOUT.name().equals(r.status())) {
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
