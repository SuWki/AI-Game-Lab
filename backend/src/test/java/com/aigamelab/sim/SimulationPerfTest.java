package com.aigamelab.sim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P4-4 性能达标（03 文档）：基准机 100000 局 <60s 且结果可复现。
 * 标注 @Tag("perf")：默认被 pom surefire excludedGroups=perf 跳过，
 * 手动触发：mvn test -Dgroups=perf
 */
@DisplayName("P4-4 性能基准（默认跳过）")
@Tag("perf")
class SimulationPerfTest {

    private final GameConfig cfg = GameConfigLoader.loadFromClasspath("config/v001.json");

    @Test
    void hundredKGamesUnderSixtySecondsAndReproducible() {
        int count = 100_000;
        long t0 = System.currentTimeMillis();
        SimulationResult r1 = new BatchSimulator(cfg, "crit", 12345, count).run();
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println("[P4-4 perf] 100k games in " + elapsed + "ms, winRate="
                + r1.winRate());
        assertThat(elapsed).isLessThan(60_000L);

        // 复现性：同参再跑结果一致
        SimulationResult r2 = new BatchSimulator(cfg, "crit", 12345, count).run();
        assertThat(r2.winCount()).isEqualTo(r1.winCount());
        assertThat(r2.avgDps()).isEqualTo(r1.avgDps());
    }
}
