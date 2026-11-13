package com.aigamelab.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P4-3 验收测试（03 文档）：构造已知分布的 GameOutcome 序列直测 Aggregator
 * （不必真跑模拟）；小样本指标与手工计算一致；CI 公式正确；直方图自洽。
 */
@DisplayName("P4-3 Aggregator 指标聚合")
class AggregatorTest {

    private GameOutcome win(double t, double dmg, String build, List<String> ups) {
        return GameOutcome.win(t, dmg, t, build, ups);
    }

    private GameOutcome death(double t, double dmg, String build, List<String> ups) {
        return GameOutcome.death(t, dmg, t, build, ups);
    }

    @Test
    void handComputedSmallSample() {
        // 3 局：2 胜 1 死；存活 100/200/50；伤害 1000/2000/500
        var outcomes = List.of(
                win(100, 1000, "crit", List.of("u_a")),
                win(200, 2000, "crit", List.of("u_a", "u_b")),
                death(50, 500, "crit", List.of("u_b")));
        Aggregator agg = new Aggregator(3);
        outcomes.forEach(agg::add);
        SimulationResult r = agg.result();

        assertThat(r.totalGames()).isEqualTo(3);
        assertThat(r.winCount()).isEqualTo(2);
        assertThat(r.deathCount()).isEqualTo(1);
        assertThat(r.winRate()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.deathRate()).isCloseTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        // avgGameTime = (100+200+50)/3 = 116.67
        assertThat(r.avgGameTime()).isCloseTo(116.6667, org.assertj.core.data.Offset.offset(1e-3));
        // avgDps = (1000/100 + 2000/200 + 500/50)/3 = (10+10+10)/3 = 10
        assertThat(r.avgDps()).isCloseTo(10.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void buildDistributionAndCi() {
        var outcomes = List.of(
                win(100, 1000, "crit", List.of()),
                win(120, 1100, "crit", List.of()),
                win(90, 900, "crit", List.of()),
                win(110, 1000, "crit", List.of()),
                death(50, 400, "crit", List.of()));
        Aggregator agg = new Aggregator(5);
        outcomes.forEach(agg::add);
        SimulationResult r = agg.result();

        var dist = r.buildDistribution().get("crit");
        assertThat(dist.games()).isEqualTo(5);
        assertThat(dist.wins()).isEqualTo(4);
        assertThat(dist.winRate()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
        // 95% CI 应包围 0.8 且宽度合理
        assertThat(dist.ciLow()).isLessThan(0.8);
        assertThat(dist.ciHigh()).isGreaterThan(0.8);
        assertThat(dist.ciLow()).isGreaterThanOrEqualTo(0.0);
        assertThat(dist.ciHigh()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void upgradePickRate() {
        var outcomes = List.of(
                win(100, 1000, "crit", List.of("u_a")),
                win(120, 1100, "crit", List.of("u_a", "u_b")),
                death(50, 400, "crit", List.of("u_b")));
        Aggregator agg = new Aggregator(3);
        outcomes.forEach(agg::add);
        SimulationResult r = agg.result();

        // u_a 被 2/3 局选取；u_b 被 2/3 局选取
        assertThat(r.upgradePickRate().get("u_a")).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.upgradePickRate().get("u_b")).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        // 胜局 2 局都选了 u_a；u_b 仅在 1 胜局选取
        assertThat(r.upgradePickRateInWins().get("u_a")).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.upgradePickRateInWins().get("u_b")).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void histogramsSumToTotalGames() {
        var outcomes = List.of(
                win(100, 1000, "crit", List.of()),
                win(300, 2000, "crit", List.of()),
                death(50, 500, "crit", List.of()));
        Aggregator agg = new Aggregator(3);
        outcomes.forEach(agg::add);
        SimulationResult r = agg.result();

        int timeSum = 0;
        for (int v : r.gameTimeHistogram()) {
            timeSum += v;
        }
        assertThat(timeSum).isEqualTo(3);

        int dmgSum = 0;
        for (int v : r.damageHistogram()) {
            dmgSum += v;
        }
        assertThat(dmgSum).isEqualTo(3);
        // 直方图长度符合蓝图 §12.4 约定
        assertThat(r.gameTimeHistogram()).hasSize(601);
        assertThat(r.damageHistogram()).hasSize(24);
    }
}
