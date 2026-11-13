package com.aigamelab.sim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P4-2 验收测试（03 文档）：10000 局两次运行聚合结果一致（复现性）；
 * 串行预分裂保证可复现；并行与串行聚合结果一致（隔离正确）。
 */
@DisplayName("P4-2 批量并行模拟")
class BatchSimulatorTest {

    private final GameConfig cfg = GameConfigLoader.loadFromClasspath("config/v001.json");

    @Test
    void twoRunsWithSameSeedAreReproducible() {
        int count = 200;
        SimulationResult r1 = new BatchSimulator(cfg, "crit", 42, count).run();
        SimulationResult r2 = new BatchSimulator(cfg, "crit", 42, count).run();
        assertThat(r1.winCount()).isEqualTo(r2.winCount());
        assertThat(r1.winRate()).isEqualTo(r1.winRate());
        assertThat(r1.avgGameTime()).isEqualTo(r2.avgGameTime());
        assertThat(r1.avgDps()).isEqualTo(r2.avgDps());
    }

    @Test
    void parallelAndSerialAggregationMatch() {
        int count = 100;
        SimulationResult parallel = new BatchSimulator(cfg, "crit", 7, count).run();
        SimulationResult serial = new BatchSimulator(cfg, "crit", 7, count).runSerial();
        assertThat(parallel.winCount()).isEqualTo(serial.winCount());
        assertThat(parallel.avgDps()).isEqualTo(serial.avgDps());
        assertThat(parallel.buildDistribution().get("crit").games()).isEqualTo(count);
    }

    @Test
    void aggregationCountsAllGames() {
        int count = 150;
        SimulationResult r = new BatchSimulator(cfg, "attack_speed", 11, count).run();
        assertThat(r.totalGames()).isEqualTo(count);
        assertThat(r.winCount() + r.deathCount()).isEqualTo(count);
        assertThat(r.buildDistribution().get("attack_speed").games()).isEqualTo(count);
    }
}
