package com.aigamelab.sim;

/**
 * 单局模拟产出（蓝图 §12.4 逐局最小值，供 Aggregator 聚合）。
 * 仅含聚合所需字段：胜负、存活时间、造成伤害、TTK（首杀时间）、选用 build、升级拾取列表。
 * 设计为不可变小对象，便于并行累计与确定性比对。
 */
public record GameOutcome(
        boolean win,
        double survivalTimeSec,
        double totalDamage,
        double timeToKillSec,
        String buildType,
        java.util.List<String> pickedUpgrades) {

    public static GameOutcome win(double t, double dmg, double ttk, String build,
            java.util.List<String> ups) {
        return new GameOutcome(true, t, dmg, ttk, build, ups);
    }

    public static GameOutcome death(double t, double dmg, double ttk, String build,
            java.util.List<String> ups) {
        return new GameOutcome(false, t, dmg, ttk, build, ups);
    }
}
