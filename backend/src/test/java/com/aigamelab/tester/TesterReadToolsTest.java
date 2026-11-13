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

import com.aigamelab.domain.TestPlan;
import com.aigamelab.gameenv.GameAction;
import com.aigamelab.gameenv.GameObservation;
import com.aigamelab.repository.AbstractRepositoryTest;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolException;
import com.aigamelab.tool.ToolRegistry;
import com.aigamelab.tool.ToolResult;

/**
 * T3-2 验收测试：4 个读工具的合法/非法/边界参数；registry 含全部 Tester 工具
 * 且与 Designer 工具共存无重名；未知 sessionId 报错不致命（非 critical ToolException）。
 */
@DisplayName("T3-2 Tester Tools 读×4 + 注册共存")
class TesterReadToolsTest extends AbstractRepositoryTest {

    @Autowired TestPlanGetTool planGetTool;
    @Autowired GameStartTool gameStartTool;
    @Autowired GameObserveTool gameObserveTool;
    @Autowired GameCollectEventsTool collectEventsTool;
    @Autowired TestPlanService planService;
    @Autowired ToolRegistry registry;
    @Autowired JdbcTemplate jdbc;

    private long taskId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM test_plan");
        taskId = jdbc.queryForObject(
                "INSERT INTO task(title, goal) VALUES('t','g') RETURNING id", Long.class);
    }

    private ToolContext ctx() {
        return ToolContext.of(null, null); // 环境级验证会话：runId 可空
    }

    // ==================== 注册共存（任务树验收标准） ====================

    @Test
    void registryContainsAllTesterTools_withoutNameClash() {
        assertThat(registry.contains(TestPlanGetTool.NAME)).isTrue();
        assertThat(registry.contains(GameStartTool.NAME)).isTrue();
        assertThat(registry.contains(GameObserveTool.NAME)).isTrue();
        assertThat(registry.contains(GameCollectEventsTool.NAME)).isTrue();
        // schema 形状合法（OpenAI function 格式）
        for (String n : List.of(TestPlanGetTool.NAME, GameStartTool.NAME,
                GameObserveTool.NAME, GameCollectEventsTool.NAME)) {
            var s = registry.get(n).jsonSchema();
            assertThat(s).containsKeys("type", "function");
        }
    }

    @Test
    void allToolsNeedNoApproval_inMvp() {
        for (var t : registry.all()) {
            if (t instanceof AbstractTesterTool) {
                assertThat(t.requiresApproval()).as(t.name()).isFalse();
            }
        }
    }

    // ==================== test_plan_get ====================

    @Test
    void testPlanGet_returnsActivePlansWithScenarios() {
        TestPlan plan = planService.createDraft(taskId, "v001",
                List.of(Map.of("id", "s1")));
        planService.activate(plan.id());

        ToolResult r = planGetTool.execute(
                args(Map.of("taskId", taskId)), ctx());
        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.payloadJson()).contains("\"s1\"").contains("ACTIVE")
                .contains("HP>=0"); // scenarios 结构化内联且 invariant 白名单已补全

        // 无 ACTIVE 计划 → 友好空结果而非错误
        long other = jdbc.queryForObject(
                "INSERT INTO task(title, goal) VALUES('t2','g') RETURNING id", Long.class);
        ToolResult empty = planGetTool.execute(args(Map.of("taskId", other)), ctx());
        assertThat(empty.status()).isEqualTo("COMPLETED");
        assertThat(empty.summaryText()).contains("暂无 ACTIVE");
    }

    @Test
    void testPlanGet_missingTaskId_failsNonCritically() {
        assertThatThrownBy(() -> planGetTool.execute(args(Map.of()), ctx()))
                .isInstanceOf(ToolException.class)
                .satisfies(e -> assertThat(((ToolException) e).isCritical()).isFalse());
    }

    // ==================== game_start / game_observe / collect_events 链路 ====================

    private String startSession(long seed) {
        ToolResult r = gameStartTool.execute(args(Map.of(
                "seed", seed, "configVersion", "v001",
                "scenarioId", "smoke-1")), ctx());
        assertThat(r.status()).isEqualTo("COMPLETED");
        return extractSessionId(r);
    }

    private static String extractSessionId(ToolResult r) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    r.payloadJson());
            return node.get("sessionId").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void startObserveCollect_fullChain_isConsistentAndDeterministic() {
        String sid1 = startSession(42L);
        String sid2 = startSession(42L); // 同 seed 第二会话 → 状态逐字段一致

        GameObservation o1 = readObservation(sid1);
        GameObservation o2 = readObservation(sid2);
        assertThat(o1.tick()).isEqualTo(o2.tick());
        assertThat(o1.phase()).isEqualTo(o2.phase());
        assertThat(o1.hp()).isEqualTo(o2.hp());

        // observe 工具输出即快照 JSON，且 ≤4000 字符
        ToolResult obsR = gameObserveTool.execute(args(Map.of("sessionId", sid1)), ctx());
        assertThat(obsR.status()).isEqualTo("COMPLETED");
        assertThat(obsR.summaryText().length()).isLessThanOrEqualTo(4000);
        assertThat(obsR.summaryText()).contains("sessionId").contains("phase");

        // 初始 drain：PLAYER_SPAWNED 已可见
        ToolResult ev1 = collectEventsTool.execute(args(Map.of("sessionId", sid1)), ctx());
        assertThat(ev1.status()).isEqualTo("COMPLETED");
        assertThat(ev1.payloadJson()).contains("PLAYER_SPAWNED");
        // drain 语义：第二次为空
        ToolResult ev2 = collectEventsTool.execute(args(Map.of("sessionId", sid1)), ctx());
        assertThat(ev2.payloadJson()).isEqualTo("[]");

        // 动作推进后 tick 前进（确定性：同动作同结果）——game_act 属 T3-3，
        // 此处经 GameSessionService 直接推进等价验证链路
        GameObservation before = readObservation(sid1);
        sessionService.act(sid1, null, GameAction.of("WAIT", null, 10));
        GameObservation after = readObservation(sid1);
        assertThat(after.tick()).isEqualTo(before.tick() + 10);
    }

    @Test
    void overridesAreApplied_onStart_boundaryMaxHp1() {
        ToolResult r = gameStartTool.execute(args(Map.of(
                "seed", 43, "configVersion", "v001",
                "overrides", Map.of("player.maxHp", 1))), ctx());
        GameObservation obs = readObservation(extractSessionId(r));
        assertThat(obs.maxHp()).isEqualTo(1);
    }

    // ==================== 错误处理（任务树：未知 sessionId 报错不致命） ====================

    @Test
    void unknownSessionId_throwsNonCriticalToolException_notFatal() {
        for (AbstractTesterTool tool : List.of(gameObserveTool, collectEventsTool)) {
            assertThatThrownBy(() -> tool.execute(
                    args(Map.of("sessionId", "gs-no-such")), ctx()))
                    .isInstanceOf(ToolException.class)
                    .satisfies(e -> {
                        assertThat(((ToolException) e).isCritical()).isFalse();
                        assertThat(e.getMessage()).contains("gs-no-such");
                    });
        }
    }

    @Test
    void malformedParameters_failClearly() {
        // 缺 sessionId
        assertThatThrownBy(() -> gameObserveTool.execute(args(Map.of()), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("非法参数");
        // 缺 configVersion
        assertThatThrownBy(() -> gameStartTool.execute(
                args(Map.of("seed", 1)), ctx()))
                .isInstanceOf(ToolException.class);
        // 缺 seed（必填整数）
        assertThatThrownBy(() -> gameStartTool.execute(
                args(Map.of("configVersion", "v001")), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("非法参数");
        // taskId 类型错误
        assertThatThrownBy(() -> planGetTool.execute(
                args(Map.of("taskId", "abc")), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("整数");
    }

    // ------------------------------------------------------------------

    private GameObservation readObservation(String sessionId) {
        return sessionService.observe(sessionId, null);
    }

    @Autowired GameSessionService sessionService;

    /** 极简 JsonNode 参数构造。 */
    static com.fasterxml.jackson.databind.JsonNode args(Map<String, Object> m) {
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(m,
                com.fasterxml.jackson.databind.JsonNode.class);
    }
}
