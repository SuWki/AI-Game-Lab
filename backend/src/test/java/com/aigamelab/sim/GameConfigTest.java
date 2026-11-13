package com.aigamelab.sim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aigamelab.sim.model.Targets;

/**
 * P4-0 验收测试（03 文档）：v001.json 反序列化成功且字段值正确；targets 三项齐全；
 * Jackson round-trip 双向可用。
 */
@DisplayName("P4-0 GameConfig 模型 + v001 种子")
class GameConfigTest {

    @Test
    void v001JsonDeserializesWithCorrectFields() {
        GameConfig cfg = GameConfigLoader.loadFromClasspath("config/v001.json");
        assertThat(cfg.version()).isEqualTo("v001");
        // 四把武器 + 六升级（蓝图 §12.1）
        assertThat(cfg.weapons()).hasSize(4);
        assertThat(cfg.upgrades()).hasSize(6);
        // 失衡种子值校验（蓝图 §12.1：crit 偏强 / penetration 偏弱）
        var crit = cfg.weapons().stream().filter(w -> "crit".equals(w.buildType())).findFirst().orElseThrow();
        assertThat(crit.critChance()).isEqualTo(0.35);
        assertThat(crit.critMultiplier()).isEqualTo(2.8);
        var pen = cfg.weapons().stream().filter(w -> "penetration".equals(w.buildType())).findFirst().orElseThrow();
        assertThat(pen.armorPenetration()).isEqualTo(0.15);
    }

    @Test
    void targetsHasThreeItems() {
        GameConfig cfg = GameConfigLoader.loadFromClasspath("config/v001.json");
        Targets t = cfg.targets();
        assertThat(t.targetWinRate()).isGreaterThan(0);
        assertThat(t.acceptableBuildDpsVariance()).isGreaterThan(0);
        assertThat(t.maxDominantBuildShare()).isGreaterThan(0);
    }

    @Test
    void roundTripJsonPreservesFields() {
        GameConfig cfg = GameConfigLoader.loadFromClasspath("config/v001.json");
        String json = GameConfigLoader.toJson(cfg);
        GameConfig back = GameConfigLoader.fromJson(json);
        assertThat(back.version()).isEqualTo(cfg.version());
        assertThat(back.weapons()).hasSize(cfg.weapons().size());
        assertThat(back.targets().targetWinRate()).isEqualTo(cfg.targets().targetWinRate());
    }

    @Test
    void weaponsFilteredByBuildType() {
        GameConfig cfg = GameConfigLoader.loadFromClasspath("config/v001.json");
        assertThat(cfg.weaponsByBuild("crit")).hasSize(1);
        assertThat(cfg.weaponsByBuild("penetration")).hasSize(1);
        assertThat(cfg.weaponsByBuild("attack_speed")).hasSize(2);
        assertThat(cfg.upgradesByBuild("crit")).hasSize(2);
    }
}
