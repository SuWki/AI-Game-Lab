package com.aigamelab.sim;

import java.util.Map;

/**
 * 蓝图 §12.4：模拟聚合结果（单结果 JSON <10KB，不存逐局明细）。
 * 由 Aggregator 从 GameOutcome 序列聚合得到；P5-1 序列化为 resultJson 写回 experiment 表。
 *
 * 包含：胜负/时长/伤害分布 + 各 Build 胜率（含 95%CI）+ 升级选取率。
 */
public record SimulationResult(
        int totalGames,
        int winCount,
        int deathCount,
        double winRate,
        double deathRate,
        double avgGameTime,
        double medianGameTime,
        double p90GameTime,
        double avgDps,
        double avgTtk,
        int[] gameTimeHistogram,     // 601 桶（0..600s）
        int[] damageHistogram,       // 24 桶
        Map<String, BuildDistribution> buildDistribution,
        Map<String, Double> upgradePickRate,
        Map<String, Double> upgradePickRateInWins) {

    /**
     * 单 Build 分布（蓝图 §12.4 buildDistribution）。
     */
    public record BuildDistribution(
            int games,
            int wins,
            double winRate,
            double ciLow,
            double ciHigh) {
    }
}
