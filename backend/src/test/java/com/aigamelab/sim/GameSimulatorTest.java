package com.aigamelab.sim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P4-1 验收测试（03 文档）：同 seed 跑两局逐字段相同；不同 seed 结果不同；
 * 600s 存活判胜、hp≤0 判死；状态隔离（两实例互不影响）。
 */
@DisplayName("P4-1 单局确定性模拟器")
class GameSimulatorTest {

    private final GameConfig cfg = GameConfigLoader.loadFromClasspath("config/v001.json");

    @Test
    void sameSeedProducesIdenticalOutcome() {
        GameOutcome a = new GameSimulator(cfg, "crit", 12345).simulate();
        GameOutcome b = new GameSimulator(cfg, "crit", 12345).simulate();
        assertThat(a).isEqualTo(b);
        assertThat(a.totalDamage()).isEqualTo(b.totalDamage());
        assertThat(a.survivalTimeSec()).isEqualTo(b.survivalTimeSec());
        assertThat(a.pickedUpgrades()).containsExactlyElementsOf(b.pickedUpgrades());
    }

    @Test
    void differentSeedProducesDifferentOutcome() {
        GameOutcome a = new GameSimulator(cfg, "crit", 1).simulate();
        GameOutcome b = new GameSimulator(cfg, "crit", 2).simulate();
        // 不同 seed 至少造成升级序列差异（命中路径也可能不同）
        boolean differed = !a.pickedUpgrades().equals(b.pickedUpgrades())
                || a.totalDamage() != b.totalDamage();
        assertThat(differed).isTrue();
    }

    @Test
    void survivesFullDurationWins() {
        // crit build 偏强，应能在 600s 内存活判胜
        GameOutcome o = new GameSimulator(cfg, "crit", 777).simulate();
        assertThat(o.survivalTimeSec()).isGreaterThanOrEqualTo(600.0 - 1e-6);
        assertThat(o.win()).isTrue();
    }

    @Test
    void stateIsolationBetweenInstances() {
        // 两个独立实例用同 seed 必须产出完全一致（BuildState 不跨实例污染）
        GameSimulator s1 = new GameSimulator(cfg, "attack_speed", 99);
        GameSimulator s2 = new GameSimulator(cfg, "attack_speed", 99);
        GameOutcome o1 = s1.simulate();
        GameOutcome o2 = s2.simulate();
        assertThat(o1).isEqualTo(o2);
    }

    @Test
    void weakBuildHasLowerWinRateAcrossSeeds() {
        // 跨多 seed 统计：penetration（偏弱）胜率应低于 crit（偏强），验证种子失衡有效
        int critWins = 0, penWins = 0;
        int n = 20;
        for (int i = 0; i < n; i++) {
            if (new GameSimulator(cfg, "crit", i).simulate().win()) {
                critWins++;
            }
            if (new GameSimulator(cfg, "penetration", i).simulate().win()) {
                penWins++;
            }
        }
        assertThat((double) critWins / n).isGreaterThan((double) penWins / n);
    }
}
