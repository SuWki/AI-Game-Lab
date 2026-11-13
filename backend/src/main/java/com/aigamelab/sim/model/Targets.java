package com.aigamelab.sim.model;

/**
 * 蓝图 §12.1：平衡目标（验收标准"targets 三项齐全"）。
 * targetWinRate：期望胜率；acceptableBuildDpsVariance：各 build DPS 可接受方差；
 * maxDominantBuildShare：单一 build 胜率占比上限（防止某 build 碾压）。
 */
public record Targets(
        double targetWinRate,
        double acceptableBuildDpsVariance,
        double maxDominantBuildShare) {
}
