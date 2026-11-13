package com.aigamelab.sim.model;

/**
 * 蓝图 §12.1：玩家属性（PROJECT ORBITAL）。
 * 同局玩家固定 1 个 buildType（scenario 决定），所用武器与升级池按此 buildType 过滤。
 */
public record Player(
        double maxHp,
        double moveSpeed,
        double pickupRadius,
        double armor,
        double hpRegenPerSec,
        double levelUpIntervalSec,
        String buildType) {
}
