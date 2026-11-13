package com.aigamelab.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.Experiment;
import com.aigamelab.repository.ExperimentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * P3-4 工具 #3：compare_experiments（03 文档 P3-4 / 02 文档 §11 #6）。
 *
 * <p>READ 权限、无需审批。对给定实验 id 序列做两两 Δ 胜率 + z 显著性检验
 * （02 技术设计 §13：z = Δ / √(pA(1−pA)/nA + pB(1−pB)/nB)，|z|&gt;1.96 显著）。
 * 输出 Δ+z 显著性文本表。
 *
 * <p>不重新实现统计逻辑（与 P5-3 Controller 同源公式，但 Tool 独立实现，不依赖 REST）。
 */
@Component
public class CompareExperimentsTool implements GameTool {

    private static final double Z_THRESHOLD = 1.96;
    private static final int MAX_SUMMARY = 4000;

    private final ExperimentRepository experimentRepo;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public CompareExperimentsTool(ExperimentRepository experimentRepo) {
        this.experimentRepo = experimentRepo;
    }

    @Override
    public String name() {
        return "compare_experiments";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> ids = new LinkedHashMap<>();
        ids.put("type", "array");
        ids.put("description", "要对比的实验 id 列表（≥2）");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "integer");
        ids.put("items", item);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("ids", ids);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("ids"));
        return schema;
    }

    @Override
    public boolean requiresApproval() {
        return false; // READ 权限，无需审批
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        if (args == null || !args.has("ids") || !args.get("ids").isArray()) {
            throw new ToolException("ids 为必填数组参数");
        }
        List<Long> ids = new java.util.ArrayList<>();
        args.get("ids").forEach(n -> ids.add(n.asLong()));
        if (ids.size() < 2) {
            throw new ToolException("compare 至少需要 2 个实验 id");
        }

        List<Experiment> experiments = experimentRepo.findByIds(ids);
        if (experiments.size() < 2) {
            throw new ToolException("未找到足够实验（需要 2+，找到 " + experiments.size() + "）");
        }

        ArrayNode pairs = MAPPER.createArrayNode();
        StringBuilder sb = new StringBuilder();
        sb.append("实验对比（Δ 胜率 + z 显著性，阈值 |z|>1.96）:\n");
        for (int i = 0; i < experiments.size() - 1; i++) {
            Experiment a = experiments.get(i);
            Experiment b = experiments.get(i + 1);
            ObjectNode pair = comparePair(a, b);
            pairs.add(pair);
            sb.append("  #").append(a.id()).append(" vs #").append(b.id())
                    .append(": ΔwinRate=").append(String.format("%.4f", pair.get("deltaWinRate").asDouble()))
                    .append(" z=").append(String.format("%.3f", pair.get("zScore").asDouble()))
                    .append(" 显著=").append(pair.get("significant").asBoolean()).append("\n");
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("pairs", pairs);

        return ToolResult.success(payload.toString(), truncate(sb.toString()));
    }

    private ObjectNode comparePair(Experiment a, Experiment b) {
        double pA = a.winRate() == null ? 0 : a.winRate();
        double nA = a.simulationCount();
        double pB = b.winRate() == null ? 0 : b.winRate();
        double nB = b.simulationCount();
        double delta = pA - pB;
        double se = Math.sqrt(pA * (1 - pA) / nA + pB * (1 - pB) / nB);
        double z = se == 0.0 ? 0.0 : delta / se;
        boolean significant = Math.abs(z) > Z_THRESHOLD;

        ObjectNode r = MAPPER.createObjectNode();
        r.put("experimentA", a.id());
        r.put("experimentB", b.id());
        r.put("winRateA", pA);
        r.put("winRateB", pB);
        r.put("deltaWinRate", delta);
        r.put("zScore", z);
        r.put("significant", significant);
        return r;
    }

    private static String truncate(String text) {
        return text == null ? "" : (text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY));
    }
}
