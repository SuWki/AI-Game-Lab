package com.aigamelab.tester;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.gameenv.GameAction;
import com.aigamelab.gameenv.GameObservation;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * T3-3 Tool 5/8：game_act —— 执行一个 GameAction，返回执行后 Observation 摘要。
 * 链路：Tool → GameSessionService（归属校验）→ GameAdapter.act()。
 * actionType 开放字符串（MVP 约定 MOVE/ATTACK/CHOOSE_UPGRADE/WAIT/RESTART）；
 * 每次 act 自动落 tool_call 行 = Action Sequence 单一事实源（Evidence 契约）。
 */
@Component
public class GameActTool extends AbstractTesterTool {

    static final String NAME = "game_act";

    private final GameSessionService sessionService;
    private final AgentEventPublisher publisher;

    public GameActTool(GameSessionService sessionService, AgentEventPublisher publisher) {
        this.sessionService = sessionService;
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("在游戏会话中执行一个动作并推进模拟时钟。返回执行后的状态快照。"
                        + "actionType 约定值：MOVE/ATTACK/CHOOSE_UPGRADE/WAIT/RESTART。",
                Map.of(
                        "sessionId", Map.of("type", "string",
                                "description", "game_start 返回的会话 id"),
                        "actionType", Map.of("type", "string",
                                "description", "动作类型"),
                        "params", Map.of("type", "object",
                                "description", "动作参数，如 {\"direction\":\"UP\"}"),
                        "tickBudget", Map.of("type", "integer",
                                "description", "本动作推进的最大 tick 数，默认 10")),
                "sessionId", "actionType");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        String sessionId = textArg(args, "sessionId", null);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: sessionId");
        }
        String actionType = textArg(args, "actionType", null);
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: actionType");
        }
        int tickBudget = intArgOrDefault(args, "tickBudget", 10);
        if (tickBudget < 0 || tickBudget > 10_000) {
            throw new IllegalArgumentException(
                    "tickBudget 越界 [0,10000]: " + tickBudget);
        }
        JsonNode params = args.get("params");

        GameObservation after = sessionService.act(sessionId, ctx.runId(),
                new GameAction(actionType.toUpperCase(), params, tickBudget));
        // T3-4：返回前以 Tester 默认白名单重算不变量，违规附回 invariantViolations
        after = InvariantEngine.enrich(after);
        // T4-3：存在违规时发 INVARIANT_VIOLATED 事件（中台实时可见）
        if (ctx.runId() != null && !after.invariantViolations().isEmpty()) {
            ObjectNode ev = MAPPER.createObjectNode();
            ev.put("sessionId", sessionId);
            ev.put("tick", after.tick());
            ev.set("violations", MAPPER.valueToTree(after.invariantViolations()));
            publisher.publish("INVARIANT_VIOLATED", ctx.runId(), ev.toString());
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("actionType", actionType.toUpperCase());
        payload.put("tickBudget", tickBudget);
        payload.putPOJO("observation", after);
        return ToolResult.success(payload.toString(),
                GameObserveTool.truncate("动作已执行 phase=" + after.phase()
                        + " tick=" + after.tick() + " hp=" + after.hp()
                        + (after.invariantViolations().isEmpty()
                                ? "" : " 违规=" + after.invariantViolations())));
    }
}
