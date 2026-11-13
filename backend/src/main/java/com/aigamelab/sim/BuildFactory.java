package com.aigamelab.sim;

import java.util.List;

import com.aigamelab.sim.model.Upgrade;
import com.aigamelab.sim.model.Weapon;

/**
 * 蓝图 §12.5：Build 工厂。按 scenario 给定的 buildType 从 GameConfig 过滤出
 * 该局使用的武器与升级池，封装为可变 BuildState（升级会就地修正武器属性）。
 *
 * 注意：BuildState 是"单局内可变"状态（升级叠加），与不可变的 GameConfig 解耦；
 * 每个 GameSimulator 实例持有独立 BuildState，互不污染（状态隔离验收）。
 */
public final class BuildFactory {

    private BuildFactory() {
    }

    public static BuildState create(GameConfig config, String buildType) {
        List<Weapon> weapons = config.weaponsByBuild(buildType);
        if (weapons.isEmpty()) {
            throw new IllegalArgumentException("buildType 无对应武器: " + buildType);
        }
        List<Upgrade> pool = config.upgradesByBuild(buildType);
        return new BuildState(buildType, weapons, pool);
    }
}
