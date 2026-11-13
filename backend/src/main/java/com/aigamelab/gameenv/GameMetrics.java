package com.aigamelab.gameenv;

/**
 * T1-1：自会话启动以来的累计指标（技术设计 §3.1）。
 */
public record GameMetrics(
        long ticksElapsed,
        long damageDealt,
        long damageTaken,
        long kills,
        long deaths,
        long actionsExecuted,
        long uptimeMs) {
}
