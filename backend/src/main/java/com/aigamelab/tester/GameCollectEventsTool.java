package com.aigamelab.tester;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.gameenv.GameEvent;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * T3-2 Tool 4/8：game_collect_events —— drain 自上次以来的增量事件（取后即清）。
 * 链路：Tool → GameSessionService（归属校验）→ GameAdapter.drainEvents()。
 */
@Component
public class GameCollectEventsTool extends AbstractTesterTool {

    static final String NAME = "game_collect_events";

    private final GameSessionService sessionService;

    public GameCollectEventsTool(GameSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("取走自上次调用以来的游戏事件列表（drain 语义，取后即清）。"
                        + "用于核实异常上下文后再决定是否上报 Finding。",
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
        List<GameEvent> events = sessionService.drainEvents(sessionId, ctx.runId());
        var payload = MAPPER.createArrayNode();
        for (GameEvent e : events) {
            var node = payload.addObject();
            node.put("tick", e.tick());
            node.put("type", e.type());
            node.putPOJO("payload", MAPPER.valueToTree(e.payload()));
        }
        String json = payload.toString();
        return ToolResult.success(json,
                "共 " + events.size() + " 条事件: " + GameObserveTool.truncate(json));
    }
}
