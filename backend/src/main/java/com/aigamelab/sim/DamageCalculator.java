package com.aigamelab.sim;

import java.util.SplittableRandom;

import com.aigamelab.sim.model.Weapon;

/**
 * 蓝图 §12.2 / §12.5：伤害纯函数（零状态，可单测，无副作用）。
 * 计算一次射击对单个敌人的实际伤害：先扣护甲减伤（armorPenetration 穿透部分），
 * 再按 critChance 掷骰判定暴击（critMultiplier 放大）。
 *
 * 设计：所有随机由外部注入的 SplittableRandom 驱动（蓝图 §12.3 可复现要求），
 * 本类本身不持有 RNG，保持纯函数特性。
 */
public final class DamageCalculator {

    private DamageCalculator() {
    }

    /**
     * 计算单次命中伤害。
     *
     * @param weapon         武器（含 damage/critChance/critMultiplier/armorPenetration）
     * @param enemyDefense   当前敌人防御（按波次成长后的值）
     * @param rollCrit       本次是否暴击（由调用方用注入 RNG 掷出）
     * @return 实际伤害（≥0）
     */
    public static double compute(Weapon weapon, double enemyDefense, boolean rollCrit) {
        double effectiveDef = enemyDefense * (1.0 - weapon.armorPenetration());
        double mitigated = weapon.damage() - effectiveDef;
        if (mitigated < 1.0) {
            mitigated = 1.0; // 至少造成 1 点保底伤害，避免敌人数值膨胀导致完全打不动
        }
        return rollCrit ? mitigated * weapon.critMultiplier() : mitigated;
    }

    /** 是否暴击（蓝图 §12.3：随机经注入 RNG）。 */
    public static boolean rollCrit(double critChance, SplittableRandom rng) {
        return rng.nextDouble() < critChance;
    }
}
