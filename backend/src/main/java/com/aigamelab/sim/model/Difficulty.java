package com.aigamelab.sim.model;

/**
 * 蓝图 §12.1：难度参数（影响敌人/节奏的整体缩放）。
 */
public record Difficulty(
        double enemyHpMultiplier,
        double enemyDamageMultiplier,
        double spawnRateMultiplier) {
}
