package com.aigamelab.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aigamelab.sim.SimulationResult;

/**
 * P5-2 验收（03 文档 P5-2）：目标校验器 EvaluationService 纯函数核心。
 * 直构 SimulationResult（不依赖 Spring/DB）验证三项判定。
 */
@DisplayName("P5-2 EvaluationService 目标校验")
class EvaluationServiceTest {

    private static final double TARGET_WR = 0.5;
    private static final double ACC_VAR = 0.15;
    private static final double MAX_SHARE = 0.7;

    private static SimulationResult.BuildDistribution bd(int games, int wins) {
        double wr = games == 0 ? 0.0 : (double) wins / games;
        return new SimulationResult.BuildDistribution(games, wins, wr, wr - 0.05, wr + 0.05);
    }

    private static SimulationResult result(int total, int wins,
            Map<String, SimulationResult.BuildDistribution> dist) {
        double wr = total == 0 ? 0.0 : (double) wins / total;
        return new SimulationResult(total, wins, 0, wr, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, null, null, dist, null, null);
    }

    private static Map<String, SimulationResult.BuildDistribution> dist(int cw, int pw, int aw) {
        Map<String, SimulationResult.BuildDistribution> m = new LinkedHashMap<>();
        m.put("crit", bd(100, cw));
        m.put("penetration", bd(100, pw));
        m.put("attack_speed", bd(100, aw));
        return m;
    }

    @Test
    void passingSampleAllTargetsMet() {
        var res = result(300, 150, dist(50, 50, 50));
        var svc = new EvaluationService(null);
        var report = svc.evaluate(res, TARGET_WR, ACC_VAR, MAX_SHARE);
        assertThat(report.allPass()).isTrue();
        assertThat(report.items()).hasSize(3);
        assertThat(report.items()).allMatch(EvaluationService.Item::passed);
        assertThat(report.summary()).contains("[PASS]");
    }

    @Test
    void outOfSpecIdentifiesFailingItem() {
        // 整体胜率 0.2（期望 0.5，越界）但三 build 均衡 → 仅 targetWinRate 不达标
        var res = result(300, 60, dist(20, 20, 20));
        var svc = new EvaluationService(null);
        var report = svc.evaluate(res, TARGET_WR, ACC_VAR, MAX_SHARE);
        assertThat(report.allPass()).isFalse();
        var wrItem = report.items().stream()
                .filter(i -> "targetWinRate".equals(i.name())).findFirst().orElseThrow();
        assertThat(wrItem.passed()).isFalse();
        assertThat(report.summary()).contains("[FAIL]");
    }

    @Test
    void dominantBuildShareExceedsLimit() {
        // crit 90 / penetration 10 / attack_speed 10 → 占比 ~0.818 > 0.7
        var res = result(300, 110, dist(90, 10, 10));
        var svc = new EvaluationService(null);
        var report = svc.evaluate(res, TARGET_WR, ACC_VAR, MAX_SHARE);
        assertThat(report.allPass()).isFalse();
        var domItem = report.items().stream()
                .filter(i -> "maxDominantBuildShare".equals(i.name())).findFirst().orElseThrow();
        assertThat(domItem.passed()).isFalse();
    }

    @Test
    void buildDpsVarianceProxyDetectsImbalance() {
        // 胜率 0.95 / 0.05 / 0.05 → 方差 0.18 > 0.15；整体胜率 0.35≈目标 0.37；占比 0.905<0.95
        var res = result(300, 105, dist(95, 5, 5));
        var svc = new EvaluationService(null);
        var report = svc.evaluate(res, 0.37, 0.15, 0.95);
        var varItem = report.items().stream()
                .filter(i -> "acceptableBuildDpsVariance".equals(i.name())).findFirst().orElseThrow();
        assertThat(varItem.passed()).isFalse();
    }
}
