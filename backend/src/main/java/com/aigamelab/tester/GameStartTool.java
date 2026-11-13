package com.aigamelab.tester;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * T3-2 Tool 2/8：game_start —— 按场景启动（RESTART 重启）会话。
 * 链路：Tool → GameSessionService → GameAdapter → FakeGameEnvironment。
 * 返回 sessionId + 初始 Observation；每次 start 自动落 tool_call 行 = 执行证据。
 */
@Component
public class GameStartTool extends AbstractTesterTool {

    static final String NAME = "game_start";

    private final GameSessionService sessionService;
    private final AgentEventPublisher publisher;

    public GameStartTool(GameSessionService sessionService, AgentEventPublisher publisher) {
        this.sessionService = sessionService;
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("启动一个新的游戏会话（每个场景必须从 game_start 开始）。"
                        + "返回 sessionId 与初始状态快照。同 seed+overrides 必得确定性会话。",
                Map.of(
                        "seed", Map.of("type", "integer", "description", "确定性随机种子"),
                        "configVersion", Map.of("type", "string",
                                "description", "被测配置版本，如 v001"),
                        "scenarioId", Map.of("type", "string",
                                "description", "场景 id（来自测试计划）"),
                        "playerProfile", Map.of("type", "string",
                                "enum", java.util.List.of("SCRIPTED", "RANDOM"),
                                "description", "玩家人格，默认 SCRIPTED"),
                        "overrides", Map.of("type", "object",
                                "description",
                                "Boundary/故障注入覆盖，如 {\"player.maxHp\":1}")),
                "seed", "configVersion");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        long seed = requireLong(args, "seed");
        String configVersion = textArg(args, "configVersion", null);
        if (configVersion == null || configVersion.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: configVersion");
        }
        var handle = sessionService.start(
                seed,
                configVersion,
                overridesArg(args),
                textArg(args, "playerProfile", null),
                ctx.runId());

        if (ctx.runId() != null) {
            ObjectNode ev = MAPPER.createObjectNode();
            ev.put("sessionId", handle.sessionId());
            ev.put("seed", seed);
            ev.put("configVersion", configVersion);
            publisher.publish("GAME_SESSION_STARTED", ctx.runId(), ev.toString());
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("sessionId", handle.sessionId());
        payload.put("adapterId", handle.adapterId());
        payload.putPOJO("observation", sessionService.observe(handle.sessionId(), ctx.runId()));
        return ToolResult.success(payload.toString(),
                "会话已启动 sessionId=" + handle.sessionId()
                        + " seed=" + seed + " phase="
                        + sessionService.observe(handle.sessionId(), ctx.runId()).phase());
    }

    static Map<String, Object> overridesArg(JsonNode args) {
        JsonNode n = args.get("overrides");
        if (n == null || n.isNull() || !n.isObject()) {
            return Map.of();
        }
        return MAPPER.convertValue(n,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }
}
