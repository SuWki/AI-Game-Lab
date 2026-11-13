package com.aigamelab.tester;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.gameenv.GameMetrics;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * T3-3 Tool 6/8：game_collect_metrics —— 会话累计指标。
 * 链路：Tool → GameSessionService（归属校验）→ GameAdapter.metrics()。
 */
@Component
public class GameCollectMetricsTool extends AbstractTesterTool {

    static final String NAME = "game_collect_metrics";

    private final GameSessionService sessionService;

    public GameCollectMetricsTool(GameSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("获取游戏会话自启动以来的累计指标（tick 数/伤害/击杀/死亡/动作数/运行时长）。",
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
        GameMetrics m = sessionService.metrics(sessionId, ctx.runId());
        String json = toJson(m);
        return ToolResult.success(json,
                "ticks=" + m.ticksElapsed() + " kills=" + m.kills()
                        + " deaths=" + m.deaths() + " actions=" + m.actionsExecuted());
    }

    private static String toJson(GameMetrics m) {
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("Metrics 序列化失败", e);
        }
    }
}
