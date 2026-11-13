package com.aigamelab.tester;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.gameenv.GameObservation;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * T3-2 Tool 3/8：game_observe —— 当前 Observation 快照（紧凑 JSON，≤4000 字符）。
 * 链路：Tool → GameSessionService（归属校验）→ GameAdapter.observe()。
 */
@Component
public class GameObserveTool extends AbstractTesterTool {

    static final String NAME = "game_observe";

    /** 蓝图规则：工具输出截断上限 */
    static final int MAX_SUMMARY = 4000;

    private final GameSessionService sessionService;
    private final AgentEventPublisher publisher;

    public GameObserveTool(GameSessionService sessionService, AgentEventPublisher publisher) {
        this.sessionService = sessionService;
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("获取游戏会话当前状态快照（tick/phase/hp/敌人/弹丸/得分/位置/"
                        + "升级/不变量违规列表）。",
                Map.of("sessionId", Map.of("type", "string",
                        "description", "game_start 返回的会话 id")),
                "sessionId");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        String sessionId = textArg(args, "sessionId", null);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: sessionId");
        }
        GameObservation obs = sessionService.observe(sessionId, ctx.runId());
        // T3-4：返回前以 Tester 默认白名单重算不变量，违规附回 invariantViolations
        obs = InvariantEngine.enrich(obs);
        // T4-3：存在违规时发 INVARIANT_VIOLATED 事件（中台实时可见）
        if (ctx.runId() != null && !obs.invariantViolations().isEmpty()) {
            ObjectNode ev = MAPPER.createObjectNode();
            ev.put("sessionId", sessionId);
            ev.put("tick", obs.tick());
            ev.set("violations", MAPPER.valueToTree(obs.invariantViolations()));
            publisher.publish("INVARIANT_VIOLATED", ctx.runId(), ev.toString());
        }
        String json = toJson(obs);
        return ToolResult.success(json, truncate(json));
    }

    private static String toJson(GameObservation obs) {
        try {
            return MAPPER.writeValueAsString(obs);
        } catch (Exception e) {
            throw new IllegalStateException("Observation 序列化失败", e);
        }
    }

    static String truncate(String s) {
        return s.length() <= MAX_SUMMARY ? s : s.substring(0, MAX_SUMMARY) + "…(截断)";
    }
}
