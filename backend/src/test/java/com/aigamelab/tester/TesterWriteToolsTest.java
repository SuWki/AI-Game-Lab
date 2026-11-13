package com.aigamelab.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.gameenv.GameObservation;
import com.aigamelab.repository.AbstractRepositoryTest;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolException;
import com.aigamelab.tool.ToolRegistry;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T3-3 验收测试：写工具 ×4。game_act 后 Observation 更新且链路完整；
 * finding_report 落 finding 表（Evidence sessionId 主锚）；report 落
 * design_document（[TEST REPORT] 前缀）。完整生命周期：
 * start → observe → act → observe → collect。
 */
@DisplayName("T3-3 Tester Tools 写×4")
class TesterWriteToolsTest extends AbstractRepositoryTest {

    @Autowired GameStartTool gameStartTool;
    @Autowired GameActTool gameActTool;
    @Autowired GameObserveTool gameObserveTool;
    @Autowired GameCollectMetricsTool metricsTool;
    @Autowired FindingReportTool findingTool;
    @Autowired TestReportCreateTool reportTool;
    @Autowired ToolRegistry registry;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    private long taskId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM design_document");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM test_plan");
        taskId = jdbc.queryForObject(
                "INSERT INTO task(title, goal) VALUES('t','g') RETURNING id", Long.class);
    }

    private ToolContext ctx() {
        return ToolContext.of(null, null);
    }

    @Autowired RunRepository runRepo;

    /** 插入真实 run 行并回传其 id（finding/design_document 的 run_id FK 必需） */
    private long realRunId() {
        return runRepo.insert(new Run(null, taskId,
                RunStatus.RUNNING.name(), null, "deepseek-chat",
                0, 50, 100000, 0, null, null, null, null, null)).id();
    }

    /** 带 runId 的上下文（模拟 Agent Run 内调用，Evidence 关联验证用） */
    private ToolContext ctxWithRun() {
        return ToolContext.of(realRunId(), 101L);
    }

    /** 以指定 runId 构造上下文（finding 与 report 需共享同一 run 时） */
    private ToolContext ctxWith(long runId) {
        return ToolContext.of(runId, 101L);
    }

    private String startSession(long seed, Map<String, Object> extra) {
        var m = new java.util.HashMap<String, Object>();
        m.put("seed", seed);
        m.put("configVersion", "v001");
        m.putAll(extra);
        ToolResult r = gameStartTool.execute(args(m), ctx());
        assertThat(r.status()).isEqualTo("COMPLETED");
        return jsonField(r.payloadJson(), "sessionId");
    }

    /** 以指定真实 runId 登记会话归属（跨 run 隔离测试用） */
    private String startSessionOwnedBy(long runId, long seed, Map<String, Object> extra) {
        var m = new java.util.HashMap<String, Object>();
        m.put("seed", seed);
        m.put("configVersion", "v001");
        m.putAll(extra);
        ToolResult r = gameStartTool.execute(args(m), ToolContext.of(runId, 1L));
        assertThat(r.status()).isEqualTo("COMPLETED");
        return jsonField(r.payloadJson(), "sessionId");
    }

    // ==================== game_act ====================

    @Test
    void gameAct_updatesObservation_andIsDeterministic() {
        String s1 = startSession(42L, Map.of());
        String s2 = startSession(42L, Map.of());

        // 同动作序列双会话 → 逐字段一致的演化
        GameObservation a1 = act(s1, "ATTACK", 15);
        GameObservation b1 = act(s2, "ATTACK", 15);
        assertThat(a1.tick()).isEqualTo(b1.tick());
        assertThat(a1.hp()).isEqualTo(b1.hp());
        assertThat(a1.enemyCount()).isEqualTo(b1.enemyCount());
        assertThat(a1.score()).isEqualTo(b1.score());

        // tick 确实前进、metrics.actionsExecuted 递增
        assertThat(a1.tick()).isEqualTo(15);
        String metrics = metricsTool.execute(args(Map.of("sessionId", s1)), ctx())
                .payloadJson();
        assertThat(metrics).contains("\"actionsExecuted\":1");
    }

    @Test
    void gameAct_invalidParams_failNonCritically() {
        String sid = startSession(42L, Map.of());
        // 缺 actionType
        assertThatThrownBy(() -> gameActTool.execute(
                args(Map.of("sessionId", sid)), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("actionType");
        // tickBudget 越界
        assertThatThrownBy(() -> gameActTool.execute(
                args(Map.of("sessionId", sid, "actionType", "WAIT",
                        "tickBudget", 99_999)), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("tickBudget");
        // 未知 sessionId
        assertThatThrownBy(() -> gameActTool.execute(
                args(Map.of("sessionId", "gs-ghost", "actionType", "WAIT")), ctx()))
                .isInstanceOf(ToolException.class)
                .satisfies(e -> assertThat(((ToolException) e).isCritical()).isFalse());
    }

    @Test
    void crossRunAccess_isRejected() {
        // 会话归属真实 run=5，另一 run=999 调用应被拒（防跨 run 操作他方会话）
        long ownerRun = runRepo.insert(new Run(null, taskId,
                RunStatus.RUNNING.name(), null, "deepseek-chat",
                0, 50, 100000, 0, null, null, null, null, null)).id();
        String sid = startSessionOwnedBy(ownerRun, 42L, Map.of());

        assertThatThrownBy(() -> sessionService().observe(sid, 999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于当前 Run");
    }

    // ==================== 完整生命周期链路 ====================

    @Test
    void fullLifecycle_startObserveActObserveCollect() {
        String sid = startSession(42L, Map.of());

        GameObservation initial = observe(sid);
        assertThat(initial.phase()).isEqualTo("LOBBY");

        GameObservation afterAct = act(sid, "ATTACK", 20);
        assertThat(afterAct.tick()).isGreaterThanOrEqualTo(initial.tick());

        String metricsJson = metricsTool.execute(args(Map.of("sessionId", sid)), ctx())
                .payloadJson();
        assertThat(metricsJson).contains("ticksElapsed").contains("uptimeMs");

        // RESTART 动作：重置回 LOBBY tick=0（场景间隔离，budget=0 不推进）
        GameObservation restarted = act(sid, "RESTART", 0);
        assertThat(restarted.tick()).isZero();
        assertThat(restarted.phase()).isEqualTo("LOBBY");
    }

    // ==================== finding_report ====================

    @Test
    void findingReport_persistsFinding_withSessionAnchoredEvidence() {
        String sid = startSession(42L, Map.of());

        var params = new java.util.LinkedHashMap<String, Object>();
        params.put("sessionId", sid);
        params.put("scenarioId", "smoke-1");
        params.put("configVersion", "v001");
        params.put("severity", "HIGH");
        params.put("category", "INVARIANT_VIOLATION");
        params.put("title", "HP 为负");
        params.put("expected", "HP>=0");
        params.put("actual", "hp=-3");
        params.put("reproSteps", "seed=42 → game_start → ATTACK×2 至 tick≥注入点");
        params.put("seed", 42L);
        params.put("tick", 512L);
        params.put("toolCallIds", List.of(901L, 902L));
        params.put("observationSnapshot", Map.of("hp", -3L, "phase", "BATTLE"));

        ToolResult r = findingTool.execute(args(params), ctxWithRun());

        assertThat(r.status()).isEqualTo("COMPLETED");
        long findingId = Long.parseLong(jsonField(r.payloadJson(), "findingId"));

        // 落库断言（真实数据而非仅返回值）
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM finding WHERE id=?", findingId);
        assertThat(row.get("severity")).isEqualTo("HIGH");
        assertThat(row.get("source")).isEqualTo("LLM"); // Agent 上报默认 LLM 来源
        String evidence = (String) row.get("evidence_json");
        assertThat(evidence).contains("\"sessionId\":\"" + sid + "\"");   // 主锚点
        assertThat(evidence).contains("\"runId\":");                        // Run 关联（动态 id）
        assertThat(evidence).contains("901");                              // toolCall 区间
        assertThat(evidence).contains("\"seed\":42");                      // 可重放参数
        assertThat(row.get("status")).isEqualTo("OPEN");
    }

    @Test
    void findingReport_illegalSeverity_rejectedViaBusinessRule() {
        String sid = startSession(42L, Map.of());
        assertThatThrownBy(() -> findingTool.execute(args(Map.of(
                "sessionId", sid, "scenarioId", "s", "configVersion", "v001",
                "severity", "CRITICAL", "category", "CRASH",
                "title", "t", "reproSteps", "r")), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("40001"); // BusinessException 错误码透传
    }

    // ==================== test_report_create ====================

    @Test
    void testReportCreate_archivesMarkdownToDesignDocument() {
        // 同一 run 内先登记一条 BLOCKER Finding，report 汇总时计入风险
        long runId = realRunId();
        String sid = startSession(42L, Map.of());
        findingTool.execute(args(Map.of(
                "sessionId", sid, "scenarioId", "s1", "configVersion", "v001",
                "severity", "BLOCKER", "category", "SOFT_LOCK",
                "title", "卡死", "expected", "可推进", "actual", "卡死",
                "reproSteps", "seed=42 → …")), ctxWith(runId));

        ToolResult r = reportTool.execute(args(Map.of(
                "taskId", taskId,
                "configVersion", "v001",
                "summary", "存在软锁风险，建议修复后再发布",
                "scenarioResults", List.of(
                        Map.of("scenarioId", "s1", "passed", false, "note", "软锁"),
                        Map.of("scenarioId", "s2", "passed", true)),
                "recommendations", "检查 stuckPhase 注入路径")),
                ctxWith(runId));

        assertThat(r.status()).isEqualTo("COMPLETED");
        long docId = Long.parseLong(jsonField(r.payloadJson(), "documentId"));

        Map<String, Object> doc = jdbc.queryForMap(
                "SELECT * FROM design_document WHERE id=?", docId);
        String title = (String) doc.get("title");
        assertThat(title).startsWith(TestReportCreateTool.TITLE_PREFIX)
                .contains("v001").contains("RISK-BLOCKER");
        String md = (String) doc.get("content_md");
        assertThat(md).contains("BLOCKER=1")
                .contains("`s1` → FAIL")
                .contains("`s2` → PASS")
                .contains("卡死")
                .contains("检查 stuckPhase 注入路径");
    }

    @Test
    void testReportCreate_zeroFindings_healthyBaseline() {
        ToolResult r = reportTool.execute(args(Map.of(
                "taskId", taskId, "configVersion", "v002",
                "summary", "全部通过")), ctx());
        long docId = Long.parseLong(jsonField(r.payloadJson(), "documentId"));
        String md = jdbc.queryForObject(
                "SELECT content_md FROM design_document WHERE id=?", String.class, docId);
        assertThat(md).contains("0 Finding").doesNotContain("RISK-BLOCKER");
    }

    @Test
    void registryNowContainsAllEightTesterTools_noClash() {
        for (String n : List.of(TestPlanGetTool.NAME, GameStartTool.NAME,
                GameObserveTool.NAME, GameCollectEventsTool.NAME,
                GameActTool.NAME, GameCollectMetricsTool.NAME,
                FindingReportTool.NAME, TestReportCreateTool.NAME)) {
            assertThat(registry.contains(n)).as(n).isTrue();
        }
    }

    // ------------------------------------------------------------------

    @Autowired GameSessionService sessionService;

    private GameSessionService sessionService() {
        return sessionService;
    }

    private GameObservation observe(String sessionId) {
        return sessionService.observe(sessionId, null);
    }

    private GameObservation act(String sessionId, String type, int ticks) {
        return sessionService.act(sessionId, null, GameAction(type, ticks));
    }

    private static com.aigamelab.gameenv.GameAction GameAction(
            String type, int ticks) {
        return com.aigamelab.gameenv.GameAction.of(type, null, ticks);
    }

    private static String jsonField(String json, String field) {
        try {
            return new ObjectMapper().readTree(json).get(field).asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode args(Map<String, Object> m) {
        return new ObjectMapper().convertValue(m, JsonNode.class);
    }
}
