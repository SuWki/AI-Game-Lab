package com.aigamelab.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.domain.AgentEvent;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.domain.TestPlan;
import com.aigamelab.gameenv.FakeGameEnvironment;
import com.aigamelab.repository.AgentEventRepository;
import com.aigamelab.repository.DesignDocumentRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.repository.TestPlanRepository;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T4-4 验收：Fake 全链路端到端（技术设计 §8 判据 1~5）。
 * 经真实 Runtime 接线（ToolRegistry + GameSessionService + Tester Tools + Services
 * + Repositories + 事件发布器），以 FakeGameEnvironment 为游戏内核，跑完整测试闭环：
 *
 * test_plan_get → game_start → game_observe/act → game_collect_events/metrics
 *   → finding_report → test_report_create，并验证 TEST_* 事件落库、报告归档。
 *
 * 确定性判据：注入 hp<0 的同一 seed 两次重放得到相同 violation；健康构建 0 Finding。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T4-4 Tester Fake 全链路 E2E")
class TesterEndToEndTest {

    @Autowired TestPlanGetTool testPlanGetTool;
    @Autowired GameStartTool gameStartTool;
    @Autowired GameObserveTool gameObserveTool;
    @Autowired GameActTool gameActTool;
    @Autowired GameCollectEventsTool gameCollectEventsTool;
    @Autowired GameCollectMetricsTool gameCollectMetricsTool;
    @Autowired FindingReportTool findingReportTool;
    @Autowired TestReportCreateTool testReportCreateTool;
    @Autowired TestPlanService testPlanService;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired TestPlanRepository planRepo;
    @Autowired AgentEventRepository eventRepo;
    @Autowired DesignDocumentRepository docRepo;
    @Autowired JdbcTemplate jdbc;

    static final ObjectMapper M = new ObjectMapper();

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-t44-", ".db");
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
    }

    /** 建一组 Task + ACTIVE TestPlan + Run（Tester Run 三件套）。 */
    private long setupTesterRun() {
        Task task = taskRepo.insert(new Task(null, "测试任务", "验证 game_start→observe 链路",
                "{\"configVersion\":\"v001\"}", 50, false, "ACTIVE", null));
        TestPlan plan = testPlanService.createDraft(task.id(), "v001", null);
        testPlanService.activate(plan.id());
        Run run = runRepo.insert(new Run(null, task.id(), "RUNNING", null,
                "deepseek-chat", 0, 50, 100000, 0, null, null, null, null, null));
        return run.id();
    }

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> eventTypesOf(long runId, AgentEventRepository repo) {
        return repo.findByRunIdAfter(runId, 0, 1000).stream().map(AgentEvent::type).toList();
    }

    private static List<String> violationsOf(ToolResult obsResult) {
        List<String> out = new ArrayList<>();
        JsonNode arr = json(obsResult.payloadJson()).get("invariantViolations");
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    /** 完整 8 工具链路；reportFinding=false 时跳过 finding_report（健康构建不应产出 Finding）。 */
    private ToolResult runFullChain(long runId, long taskId, String overridesJson, boolean reportFinding) {
        long step = 0;
        testPlanGetTool.execute(json("{\"taskId\":" + taskId + "}"),
                ToolContext.of(runId, ++step));
        ToolResult start = gameStartTool.execute(json(
                "{\"seed\":42,\"configVersion\":\"v001\",\"overrides\":" + overridesJson + "}"),
                ToolContext.of(runId, ++step));
        String sessionId = json(start.payloadJson()).get("sessionId").asText();
        gameObserveTool.execute(json("{\"sessionId\":\"" + sessionId + "\"}"),
                ToolContext.of(runId, ++step));
        gameActTool.execute(json("{\"sessionId\":\"" + sessionId
                + "\",\"actionType\":\"ATTACK\"}"), ToolContext.of(runId, ++step));
        gameCollectEventsTool.execute(json("{\"sessionId\":\"" + sessionId + "\"}"),
                ToolContext.of(runId, ++step));
        gameCollectMetricsTool.execute(json("{\"sessionId\":\"" + sessionId + "\"}"),
                ToolContext.of(runId, ++step));
        if (reportFinding) {
            findingReportTool.execute(json(
                    "{\"sessionId\":\"" + sessionId + "\",\"scenarioId\":\"sc1\",\"configVersion\":\"v001\","
                            + "\"severity\":\"HIGH\",\"category\":\"BALANCE_ANOMALY\","
                            + "\"title\":\"hp 越界\",\"expected\":\"HP>=0\",\"actual\":\"hp=999\","
                            + "\"reproSteps\":\"seed=42\"}"),
                    ToolContext.of(runId, ++step));
        }
        return testReportCreateTool.execute(json(
                "{\"taskId\":" + taskId + ",\"configVersion\":\"v001\",\"summary\":\"已完成\"}"),
                ToolContext.of(runId, ++step));
    }

    @Test
    void healthyBuildProducesZeroFindingsAndNoViolationEvent() {
        long runId = setupTesterRun();
        long taskId = runRepo.findById(runId).orElseThrow().taskId();

        // 健康构建：无故障注入，且不产出 Finding
        runFullChain(runId, taskId, "{}", false);

        // 健康构建 → 链路产生 TEST_REPORT_CREATED，但无 INVARIANT_VIOLATED / FINDING_CREATED
        var types = eventTypesOf(runId, eventRepo);
        assertThat(types).contains("GAME_SESSION_STARTED", "TEST_REPORT_CREATED");
        assertThat(types).doesNotContain("INVARIANT_VIOLATED", "FINDING_CREATED");
    }

    @Test
    void injectedBuildProducesFindingAndViolationEvent() {
        long runId = setupTesterRun();
        long taskId = runRepo.findById(runId).orElseThrow().taskId();

        // 注入 hp<0 故障，并产出 Finding
        runFullChain(runId, taskId,
                "{\"" + FakeGameEnvironment.INJ_HP_NEGATIVE_AT_TICK + "\":0}", true);

        var types = eventTypesOf(runId, eventRepo);
        assertThat(types).contains("GAME_SESSION_STARTED", "INVARIANT_VIOLATED",
                "FINDING_CREATED", "TEST_REPORT_CREATED");

        // Finding 落库
        Integer findings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM finding WHERE config_version='v001'", Integer.class);
        assertThat(findings).isGreaterThanOrEqualTo(1);
    }

    @Test
    void sameSeedReproducesSameViolation() {
        // 两次独立 Tester Run，相同 seed + 相同注入 → 相同 violation 文本
        long run1 = setupTesterRun();
        ToolResult r1 = gameStartTool.execute(json(
                "{\"seed\":42,\"configVersion\":\"v001\",\"overrides\":{\""
                        + FakeGameEnvironment.INJ_HP_NEGATIVE_AT_TICK + "\":0}}"),
                ToolContext.of(run1, 1L));
        String sid1 = json(r1.payloadJson()).get("sessionId").asText();
        gameActTool.execute(json("{\"sessionId\":\"" + sid1
                + "\",\"actionType\":\"ATTACK\"}"), ToolContext.of(run1, 2L));
        ToolResult o1 = gameObserveTool.execute(json("{\"sessionId\":\"" + sid1 + "\"}"),
                ToolContext.of(run1, 3L));
        List<String> v1 = violationsOf(o1);

        long run2 = setupTesterRun();
        ToolResult r2 = gameStartTool.execute(json(
                "{\"seed\":42,\"configVersion\":\"v001\",\"overrides\":{\""
                        + FakeGameEnvironment.INJ_HP_NEGATIVE_AT_TICK + "\":0}}"),
                ToolContext.of(run2, 1L));
        String sid2 = json(r2.payloadJson()).get("sessionId").asText();
        gameActTool.execute(json("{\"sessionId\":\"" + sid2
                + "\",\"actionType\":\"ATTACK\"}"), ToolContext.of(run2, 2L));
        ToolResult o2 = gameObserveTool.execute(json("{\"sessionId\":\"" + sid2 + "\"}"),
                ToolContext.of(run2, 3L));
        List<String> v2 = violationsOf(o2);

        assertThat(v1).isNotEmpty();
        assertThat(v2).containsExactlyElementsOf(v1); // 确定性：相同 seed 复现相同违规
    }

    @Test
    void testReportArchivedToDesignDocument() {
        long runId = setupTesterRun();
        long taskId = runRepo.findById(runId).orElseThrow().taskId();
        var result = runFullChain(runId, taskId,
                "{\"" + FakeGameEnvironment.INJ_HP_NEGATIVE_AT_TICK + "\":0}", true);

        long docId = json(result.payloadJson()).get("documentId").asLong();
        var doc = docRepo.findById(docId).orElseThrow();
        assertThat(doc.title()).contains("[TEST REPORT]");
    }
}
