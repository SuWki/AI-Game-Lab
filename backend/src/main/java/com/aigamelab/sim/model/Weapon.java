package com.aigamelab.sim.model;

/**
 * 蓝图 §12.1：武器（4 把，各带 buildType，采用 config-event 模型以制造演示失衡）。
 * v001 种子值见 resources/config/v001.json（crit 偏强 / penetration 偏弱 / attack_speed 居中）。
 */
public record Weapon(
        String id,
        String name,
        String buildType,
        double damage,
        double fireIntervalMs,
        double projectileSpeed,
        double range,
        int projectileCount,
        double critChance,
        double critMultiplier,
        int pierceCount,
        double armorPenetration) {
}
