package com.aigamelab.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.Experiment;
import com.aigamelab.experiment.ExperimentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * P3-4 工具 #1：simulate_game（03 文档 P3-4 / 02 文档 §11 #4）。
 *
 * <p>EXECUTE 权限、无需审批。参数校验（count ≤ 100000）后调 ExperimentService.create()。
 * 复用 P5-1 已冻结的幂等语义（同 (config_version,seed,simulation_count,scenario) → reused=true）。
 * scenario 语义严格复用 P5-1：crit/penetration/attack_speed 单 build；null/空 → ALL_BUILDS。
 *
 * <p>不重新实现模拟逻辑（禁止重复造轮子）；仅做参数解析 + 校验 + 调 Service + 转 ToolResult。
 * 大结果经截断回传（summaryText ≤ ~4000 字符，防 token 爆炸，蓝图 §11）。
 */
@Component
public class SimulateGameTool implements GameTool {

    static final int MAX_COUNT = 100000;
    private static final int MAX_SUMMARY = 4000;

    private final ExperimentService experimentService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public SimulateGameTool(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @Override
    public String name() {
        return "simulate_game";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> configVersion = new LinkedHashMap<>();
        configVersion.put("type", "string");
        configVersion.put("description", "基于哪个配置版本，缺省当前生效版本");

        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("type", "integer");
        seed.put("description", "RNG 种子（幂等键之一）");

        Map<String, Object> count = new LinkedHashMap<>();
        count.put("type", "integer");
        count.put("description", "每 build 模拟局数（≤ 100000）");

        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("type", "string");
        scenario.put("description", "buildType：crit/penetration/attack_speed；缺省/空 → 全部 3 build 聚合(ALL_BUILDS)");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("configVersion", configVersion);
        props.put("seed", seed);
        props.put("count", count);
        props.put("scenario", scenario);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("seed", "count"));
        return schema;
    }

    @Override
    public boolean requiresApproval() {
        return false; // EXECUTE 权限，无需审批
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        long seed = args.has("seed") && !args.get("seed").isNull()
                ? args.get("seed").asLong() : 0L;
        int count = args.has("count") && !args.get("count").isNull()
                ? args.get("count").asInt() : 0;
        String configVersion = args.has("configVersion") && !args.get("configVersion").isNull()
                ? args.get("configVersion").asText() : null;
        String scenario = args.has("scenario") && !args.get("scenario").isNull()
                ? args.get("scenario").asText() : null;

        // 参数校验
        if (count <= 0) {
            throw new ToolException("count 必须为正整数");
        }
        if (count > MAX_COUNT) {
            throw new ToolException("count 超过上限 " + MAX_COUNT + "（防超时/资源爆炸）");
        }

        // 调 P5-1 服务（同步、幂等）
        var result = experimentService.create(configVersion, seed, count, scenario, ctx.runId());
        Experiment e = result.experiment();

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("experimentId", e.id());
        payload.put("configVersion", e.configVersion());
        payload.put("seed", e.seed());
        payload.put("simulationCount", e.simulationCount());
        payload.put("scenario", e.scenario());
        payload.put("status", e.status());
        payload.put("winRate", e.winRate() == null ? 0.0 : e.winRate());
        payload.put("reused", result.reused());

        String summary = truncate("实验完成 #" + e.id()
                + " | config=" + e.configVersion()
                + " seed=" + e.seed() + " count=" + e.simulationCount()
                + " scenario=" + e.scenario()
                + " status=" + e.status()
                + " winRate=" + String.format("%.4f", e.winRate() == null ? 0.0 : e.winRate())
                + (result.reused() ? " (reused)" : ""));

        return ToolResult.success(payload.toString(), summary);
    }

    private static String truncate(String text) {
        return text == null ? "" : (text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY));
    }
}
