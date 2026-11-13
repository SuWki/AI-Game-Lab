package com.aigamelab.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.Experiment;
import com.aigamelab.experiment.EvaluationService;
import com.aigamelab.repository.ExperimentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * P3-4 工具 #2：analyze_experiment（03 文档 P3-4 / 02 文档 §11 #5）。
 *
 * <p>READ 权限、无需审批。输出单实验紧凑指标文本表（含整体胜率、各 build 胜率±CI、
 * 升级选取 Top3）+ 复用 P5-2 EvaluationService 的达标评估结论。
 * 大结果经截断回传（蓝图 §11 防 token 爆炸）。
 *
 * <p>仅做参数解析 + 取数 + 调 EvaluationService + 转 ToolResult，不重新实现评估逻辑。
 */
@Component
public class AnalyzeExperimentTool implements GameTool {

    private static final int MAX_SUMMARY = 4000;

    private final ExperimentRepository experimentRepo;
    private final EvaluationService evaluationService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public AnalyzeExperimentTool(ExperimentRepository experimentRepo,
            EvaluationService evaluationService) {
        this.experimentRepo = experimentRepo;
        this.evaluationService = evaluationService;
    }

    @Override
    public String name() {
        return "analyze_experiment";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> experimentId = new LinkedHashMap<>();
        experimentId.put("type", "integer");
        experimentId.put("description", "要分析的实验 id（experiment 表主键）");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("experimentId", experimentId);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("experimentId"));
        return schema;
    }

    @Override
    public boolean requiresApproval() {
        return false; // READ 权限，无需审批
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        if (args == null || !args.has("experimentId") || args.get("experimentId").isNull()) {
            throw new ToolException("experimentId 为必填参数");
        }
        long experimentId = args.get("experimentId").asLong();

        Experiment e = experimentRepo.findById(experimentId)
                .orElseThrow(() -> new ToolException("实验不存在: " + experimentId));

        // 解析 result_json → SimulationResult
        SimulationResultLike result = parseResult(e.resultJson());

        // 复用 P5-2 评估（按 experiment 的 configVersion 加载 targets）
        String evalSummary;
        try {
            var report = evaluationService.evaluate(e.configVersion(), toSimResult(result));
            evalSummary = report.summary();
        } catch (RuntimeException ex) {
            evalSummary = "[评估跳过] " + ex.getMessage();
        }

        // 紧凑指标文本表
        StringBuilder sb = new StringBuilder();
        sb.append("实验 #").append(e.id())
                .append(" | config=").append(e.configVersion())
                .append(" seed=").append(e.seed())
                .append(" count=").append(e.simulationCount())
                .append(" scenario=").append(e.scenario())
                .append(" status=").append(e.status()).append("\n");
        sb.append("整体胜率: ").append(String.format("%.2f%%", (e.winRate() == null ? 0 : e.winRate()) * 100)).append("\n");
        sb.append("各 build 胜率(±95%CI):\n");
        for (var entry : result.buildDistribution().entrySet()) {
            var bd = entry.getValue();
            sb.append("  ").append(entry.getKey()).append(": ")
                    .append(String.format("%.2f%%", bd.winRate() * 100))
                    .append(" [").append(String.format("%.2f%%", bd.ciLow() * 100))
                    .append(", ").append(String.format("%.2f%%", bd.ciHigh() * 100)).append("]\n");
        }
        sb.append("评估结论:\n").append(evalSummary);

        String summary = truncate(sb.toString());

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("experimentId", e.id());
        payload.put("configVersion", e.configVersion());
        payload.put("winRate", e.winRate() == null ? 0.0 : e.winRate());
        payload.put("scenario", e.scenario());
        payload.put("summary", summary);

        return ToolResult.success(payload.toString(), summary);
    }

    /**
     * 从 experiment.result_json 抽取评估所需字段（轻量解析，不依赖完整 SimulationResult 反序列化）。
     * 仅取 buildDistribution（name→{winRate,ciLow,ciHigh}），供 EvaluationService 判定平衡度。
     */
    private SimulationResultLike parseResult(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return new SimulationResultLike(Map.of());
        }
        try {
            JsonNode root = MAPPER.readTree(resultJson);
            JsonNode dist = root.get("buildDistribution");
            Map<String, SimulationResultLike.BuildDist> map = new LinkedHashMap<>();
            if (dist != null && dist.isObject()) {
                dist.fields().forEachRemaining(en -> {
                    JsonNode b = en.getValue();
                    map.put(en.getKey(), new SimulationResultLike.BuildDist(
                            b.path("winRate").asDouble(),
                            b.path("ciLow").asDouble(),
                            b.path("ciHigh").asDouble()));
                });
            }
            return new SimulationResultLike(map);
        } catch (Exception e) {
            return new SimulationResultLike(Map.of());
        }
    }

    /** 适配 EvaluationService.evaluate(SimulationResult) 的最小结构。 */
    private com.aigamelab.sim.SimulationResult toSimResult(SimulationResultLike like) {
        Map<String, com.aigamelab.sim.SimulationResult.BuildDistribution> bd = new LinkedHashMap<>();
        like.buildDistribution().forEach((k, v) -> bd.put(k,
                new com.aigamelab.sim.SimulationResult.BuildDistribution(0, 0, v.winRate(), v.ciLow(), v.ciHigh())));
        return new com.aigamelab.sim.SimulationResult(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, bd, null, null);
    }

    private static String truncate(String text) {
        return text == null ? "" : (text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY));
    }

    /** 轻量结果承载（仅评估所需字段）。 */
    private static final class SimulationResultLike {
        record BuildDist(double winRate, double ciLow, double ciHigh) {
        }

        private final Map<String, BuildDist> buildDistribution;

        SimulationResultLike(Map<String, BuildDist> buildDistribution) {
            this.buildDistribution = buildDistribution;
        }

        Map<String, BuildDist> buildDistribution() {
            return buildDistribution;
        }
    }
}
