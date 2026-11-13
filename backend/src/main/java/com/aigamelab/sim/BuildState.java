package com.aigamelab.sim;

import java.util.ArrayList;
import java.util.List;

import com.aigamelab.sim.model.Upgrade;
import com.aigamelab.sim.model.Weapon;

/**
 * 蓝图 §12.1/§12.5：单局 Build 可变状态。
 * 持有本局武器（经升级修正后的运行时属性）与升级池、已拾取升级列表。
 * 每局独立实例 → 满足状态隔离验收（一局升级不影响另一局）。
 */
public final class BuildState {

    private final String buildType;
    /** 运行时武器（副本，升级会就地修正） */
    private final List<Weapon> weapons;
    private final List<Upgrade> upgradePool;
    private final List<String> pickedUpgrades = new ArrayList<>();
    /** 每类升级已生效层数（用于 maxStacks 护栏，防 MUL 无限复利爆炸）。 */
    private final java.util.Map<String, Integer> stackCounts = new java.util.HashMap<>();
    /** 累计升级对基础属性的乘加修正，供伤害/射速计算读取 */
    private double critChanceBonus = 0.0;
    private double critMultiplierMul = 1.0;
    private double armorPenAdd = 0.0;
    private double fireIntervalMul = 1.0;
    private double damageMul = 1.0;

    BuildState(String buildType, List<Weapon> weapons, List<Upgrade> upgradePool) {
        this.buildType = buildType;
        this.weapons = new ArrayList<>(weapons);
        this.upgradePool = new ArrayList<>(upgradePool);
    }

    public String buildType() {
        return buildType;
    }

    /** 当前生效武器（已叠加升级修正）。 */
    public List<Weapon> weapons() {
        return weapons;
    }

    public List<Upgrade> upgradePool() {
        return upgradePool;
    }

    public List<String> pickedUpgrades() {
        return List.copyOf(pickedUpgrades);
    }

    /**
     * 应用一次升级（蓝图 §12.2 升级步骤：加权随机选中后叠加效果）。
     * 每类升级受 maxStacks 护栏限制：超额仍记录 pick（保证 pickRate 真实），但不再叠加数值，
     * 防止 MUL 类升级无限复利导致 DPS 数值爆炸（退化平衡）。
     */
    public void applyUpgrade(Upgrade u) {
        pickedUpgrades.add(u.id());
        int count = stackCounts.getOrDefault(u.id(), 0);
        stackCounts.put(u.id(), count + 1);
        if (count >= u.maxStacks()) {
            return; // 已达上限，仅记录 pick，不叠加
        }
        var e = u.effect();
        switch (e.stat()) {
            case "critChance" -> critChanceBonus += e.value();
            case "critMultiplier" -> critMultiplierMul *= e.value();
            case "armorPenetration" -> armorPenAdd += e.value();
            case "fireIntervalMs" -> fireIntervalMul *= e.value();
            case "damage" -> damageMul *= e.value();
            default -> { /* 未知 stat 忽略 */ }
        }
        // 重新计算武器运行时属性（仅第一把主武器应用 build 修正，简化模型）
        Weapon base = weapons.get(0);
        Weapon updated = new Weapon(base.id(), base.name(), base.buildType(),
                base.damage() * damageMul,
                base.fireIntervalMs() * fireIntervalMul,
                base.projectileSpeed(), base.range(),
                base.projectileCount(),
                clamp01(base.critChance() + critChanceBonus),
                base.critMultiplier() * critMultiplierMul,
                base.pierceCount(), base.armorPenetration() + armorPenAdd);
        weapons.set(0, updated);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
