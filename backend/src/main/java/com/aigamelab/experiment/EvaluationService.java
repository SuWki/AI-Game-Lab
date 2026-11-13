package com.aigamelab.experiment;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.aigamelab.config.ConfigVersionService;
import com.aigamelab.domain.ConfigVersion;
import com.aigamelab.sim.GameConfig;
import com.aigamelab.sim.SimulationResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P5-2：目标校验器（蓝图 §13 / 03 文档 P5-2）。
 *
 * <p>对照 config.targets 逐项判定达标与否，输出 pass/fail 摘要文本。
 * analyze_experiment 工具复用本服务。
 *
 * <p>三项校验（依据 02 技术设计 §12.4 / §13）：
 * <ul>
 *   <li><b>targetWinRate</b>：整体胜率 result.winRate() 在 target ± 容差内（容差 0.1）；</li>
 *   <li><b>acceptableBuildDpsVariance</b>：各 build 胜率的方差（平衡度代理指标，
 *       02 §13 仅暴露 per-build winRate+CI，故选用胜率方差作为跨 build 平衡度量）；</li>
 *   <li><b>maxDominantBuildShare</b>：单一 build 胜场占比 = max(wins_i)/totalWins，须 ≤ 上限。</li>
 * </ul>
 *
 * <p>纯函数核心 evaluate(...) 不依赖 Spring，单测直构 SimulationResult。
 */
@Service
public class EvaluationService {

    /** targetWinRate 容差（绝对）。 */
    static final double WIN_RATE_TOLERANCE = 0.1;

    private final ConfigVersionService configService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public EvaluationService(ConfigVersionService configService) {
        this.configService = configService;
    }

    /**
     * 便捷入口：按 configVersion 加载 targets 后校验。
     */
    public EvaluationReport evaluate(String configVersion, SimulationResult result) {
        String cfgVersion = (configVersion == null || configVersion.isBlank())
                ? configService.getCurrent().version()
                : configVersion;
        ConfigVersion v = configService.findByVersion(cfgVersion).orElseGet(configService::getCurrent);
        GameConfig config = parseConfig(v);
        return evaluate(result, config.targets().targetWinRate(),
                config.targets().acceptableBuildDpsVariance(),
                config.targets().maxDominantBuildShare());
    }

    /**
     * 纯函数核心：对照三项 targets 判定。
     *
     * @param result                  模拟结果
     * @param targetWinRate           期望整体胜率
     * @param acceptableBuildDpsVar   跨 build 胜率方差上限（平衡度）
     * @param maxDominantBuildShare   单一 build 胜场占比上限
     * @return 逐项结论 + 总体 pass/fail + 摘要文本
     */
    public EvaluationReport evaluate(SimulationResult result,
            double targetWinRate, double acceptableBuildDpsVar, double maxDominantBuildShare) {

        List<Item> items = new ArrayList<>();

        // 1) targetWinRate
        double wr = result.winRate();
        boolean winRateOk = Math.abs(wr - targetWinRate) <= WIN_RATE_TOLERANCE;
        items.add(new Item("targetWinRate",
                "整体胜率 " + pct(wr) + " 期望 " + pct(targetWinRate) + " ±" + pct(WIN_RATE_TOLERANCE),
                winRateOk));

        // 2) acceptableBuildDpsVariance（用 per-build 胜率方差作为平衡代理）
        double variance = buildWinRateVariance(result);
        boolean varianceOk = variance <= acceptableBuildDpsVar;
        items.add(new Item("acceptableBuildDpsVariance",
                "跨 build 胜率方差 " + String.format("%.4f", variance)
                        + " 上限 " + String.format("%.4f", acceptableBuildDpsVar),
                varianceOk));

        // 3) maxDominantBuildShare
        double dominant = dominantBuildShare(result);
        boolean dominantOk = dominant <= maxDominantBuildShare;
        items.add(new Item("maxDominantBuildShare",
                "最强 build 胜场占比 " + pct(dominant) + " 上限 " + pct(maxDominantBuildShare),
                dominantOk));

        boolean allPass = items.stream().allMatch(Item::passed);
        String summary = buildSummary(allPass, items);
        return new EvaluationReport(allPass, items, summary);
    }

    /** 各 build 胜率的总体方差（总体，除以 N 而非 N-1）。 */
    private double buildWinRateVariance(SimulationResult result) {
        var dist = result.buildDistribution();
        if (dist == null || dist.isEmpty()) {
            return 0.0;
        }
        double[] rates = dist.values().stream().mapToDouble(d -> d.winRate()).toArray();
        double mean = 0.0;
        for (double r : rates) {
            mean += r;
        }
        mean /= rates.length;
        double var = 0.0;
        for (double r : rates) {
            double d = r - mean;
            var += d * d;
        }
        return var / rates.length;
    }

    /** 单一 build 胜场占比 = max(wins_i) / totalWins（无胜场时记 0）。 */
    private double dominantBuildShare(SimulationResult result) {
        var dist = result.buildDistribution();
        if (dist == null || dist.isEmpty()) {
            return 0.0;
        }
        long totalWins = dist.values().stream().mapToLong(d -> d.wins()).sum();
        if (totalWins == 0) {
            return 0.0;
        }
        long maxWins = dist.values().stream().mapToLong(d -> d.wins()).max().orElse(0);
        return (double) maxWins / totalWins;
    }

    private String buildSummary(boolean allPass, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        sb.append(allPass ? "[PASS] 全部平衡目标达成" : "[FAIL] 存在未达标项");
        sb.append(System.lineSeparator());
        for (Item it : items) {
            sb.append("- ").append(it.name()).append(": ")
                    .append(it.passed() ? "OK" : "NOT MET").append(" | ")
                    .append(it.detail()).append(System.lineSeparator());
        }
        return sb.toString().stripTrailing();
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100.0);
    }

    private GameConfig parseConfig(ConfigVersion v) {
        try {
            return MAPPER.readValue(v.configJson(), GameConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("配置 JSON 反序列化失败: " + v.version(), e);
        }
    }

    /** 单项校验结论。 */
    public record Item(String name, String detail, boolean passed) {
    }

    /** 校验报告。 */
    public record EvaluationReport(boolean allPass, List<Item> items, String summary) {
    }
}
