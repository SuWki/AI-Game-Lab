package com.aigamelab.sim.model;

/**
 * 蓝图 §12.1：波次配置。20 波 × 30s = 600s ≈ 10 分钟；含敌人按波次成长与 boss 节奏。
 */
public record Wave(
        int totalWaves,
        double waveDurationSec,
        double enemyHpGrowthPerWave,
        double enemyDamageGrowthPerWave,
        double spawnRate,
        int bossEveryNWaves) {
}
