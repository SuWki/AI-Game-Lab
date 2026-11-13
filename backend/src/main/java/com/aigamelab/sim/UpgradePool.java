package com.aigamelab.sim;

import java.util.List;
import java.util.SplittableRandom;

import com.aigamelab.sim.model.Upgrade;

/**
 * 蓝图 §12.2 升级步骤：按 weight 加权随机从池中择一升级。
 * 使用注入的 RandomGenerator（蓝图 §12.3 可复现要求），保持确定性。
 */
public final class UpgradePool {

    private UpgradePool() {
    }

    /** 按权重加权随机选一个升级；池为空返回 null。 */
    public static Upgrade pickWeighted(List<Upgrade> pool, SplittableRandom rng) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        double total = 0.0;
        for (Upgrade u : pool) {
            total += Math.max(0.0, u.weight());
        }
        if (total <= 0.0) {
            return pool.get(0);
        }
        double r = rng.nextDouble() * total;
        double acc = 0.0;
        for (Upgrade u : pool) {
            acc += Math.max(0.0, u.weight());
            if (r <= acc) {
                return u;
            }
        }
        return pool.get(pool.size() - 1);
    }
}
