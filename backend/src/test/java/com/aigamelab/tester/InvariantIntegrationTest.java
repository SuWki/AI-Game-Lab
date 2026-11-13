package com.aigamelab.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.aigamelab.gameenv.FakeGameEnvironment;
import com.aigamelab.repository.AbstractRepositoryTest;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T3-4 集成测：InvariantEngine 接入 observe/act 链路。
 * 注入故障时，game_observe / game_act 返回的 Observation 携带 invariantViolations。
 * 健康构建（无注入）则不携带任何违规。
 */
@DisplayName("T3-4 InvariantEngine 接入观察链路")
class InvariantIntegrationTest extends AbstractRepositoryTest {

    @Autowired GameStartTool gameStartTool;
    @Autowired GameObserveTool gameObserveTool;
    @Autowired GameActTool gameActTool;

    private ToolContext ctx() {
        return ToolContext.of(null, null);
    }

    private String startWith(Map<String, Object> overrides, long seed) {
        var m = new java.util.HashMap<String, Object>();
        m.put("seed", seed);
        m.put("configVersion", "v001");
        m.put("overrides", overrides);
        ToolResult r = gameStartTool.execute(args(m), ctx());
        assertThat(r.status()).isEqualTo("COMPLETED");
        return jsonField(r.payloadJson(), "sessionId");
    }

    @Test
    void hpNegativeInjection_appearsInObserve() {
        // 注入：第 3 tick 起强制 hp=-3（非法 HP）
        Map<String, Object> ov = Map.of(
                FakeGameEnvironment.INJ_HP_NEGATIVE_AT_TICK, 3L);
        String sid = startWith(ov, 42L);

        // 推进越过注入点
        gameActTool.execute(args(Map.of("sessionId", sid, "actionType", "WAIT",
                "tickBudget", 5)), ctx());

        JsonNode obs = readJson(gameObserveTool.execute(
                args(Map.of("sessionId", sid)), ctx()).payloadJson());
        assertThat(obs.has("invariantViolations")).isTrue();
        assertThat(obs.get("invariantViolations").toString())
                .contains("HP>=0 violated");
    }

    @Test
    void hpNegativeInjection_appearsInActReturn() {
        Map<String, Object> ov = Map.of(
                FakeGameEnvironment.INJ_HP_NEGATIVE_AT_TICK, 2L);
        String sid = startWith(ov, 42L);

        // act 直接越过注入点，返回 Observation 应即时带违规
        ToolResult act = gameActTool.execute(args(Map.of("sessionId", sid,
                "actionType", "WAIT", "tickBudget", 4)), ctx());
        JsonNode obs = readJson(act.payloadJson()).get("observation");
        assertThat(obs.get("invariantViolations").toString())
                .contains("HP>=0 violated");
    }

    @Test
    void healthyBuild_hasNoViolations() {
        // 无任何故障注入的健康构建：默认白名单全过
        String sid = startWith(Map.of(), 42L);
        gameActTool.execute(args(Map.of("sessionId", sid, "actionType", "WAIT",
                "tickBudget", 10)), ctx());
        JsonNode obs = readJson(gameObserveTool.execute(
                args(Map.of("sessionId", sid)), ctx()).payloadJson());
        assertThat(obs.get("invariantViolations").isEmpty()).isTrue();
    }

    // ------------------------------------------------------------------

    private static JsonNode readJson(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jsonField(String json, String field) {
        return readJson(json).get(field).asText();
    }

    static JsonNode args(Map<String, Object> m) {
        return new ObjectMapper().convertValue(m, JsonNode.class);
    }
}
