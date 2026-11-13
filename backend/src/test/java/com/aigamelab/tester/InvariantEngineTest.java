package com.aigamelab.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aigamelab.gameenv.GameObservation;

/**
 * T1-3 / T3-4：InvariantEngine 白名单谓词引擎单测。
 * 验收：每条谓词正/反例各一通过；非法表达式报错明确（无脚本执行、无反射）。
 */
@DisplayName("InvariantEngine 白名单谓词引擎")
class InvariantEngineTest {

    private static GameObservation obs(int hp, int maxHp, int enemies, int projectiles,
            long tick, String phase) {
        return new GameObservation("s1", tick, phase, hp, maxHp, 0, 0, enemies, projectiles,
                0L, 1, List.of(), Map.of(), List.of());
    }

    // ---- 正例：应判定通过（无违规） ----

    @Test
    void hpNonNegative_passesWhenHpOk() {
        assertThat(InvariantEngine.evaluate(List.of("HP>=0"),
                obs(50, 100, 0, 0, 1, "BATTLE"))).isEmpty();
    }

    @Test
    void hpBelowMax_passesWhenEqual() {
        assertThat(InvariantEngine.evaluate(List.of("HP<=MaxHP"),
                obs(100, 100, 0, 0, 1, "BATTLE"))).isEmpty();
    }

    @Test
    void enemyCountBelowCap_passes() {
        assertThat(InvariantEngine.evaluate(List.of("ENEMY_COUNT<=CAP"),
                obs(50, 100, 200, 0, 1, "BATTLE"))).isEmpty();
    }

    @Test
    void projectileBelowMax_passes() {
        assertThat(InvariantEngine.evaluate(List.of("PROJECTILE<=MAX_PROJECT"),
                obs(50, 100, 0, 500, 1, "BATTLE"))).isEmpty();
    }

    @Test
    void tickNonNegative_passes() {
        assertThat(InvariantEngine.evaluate(List.of("TICK>=0"),
                obs(50, 100, 0, 0, 0, "LOBBY"))).isEmpty();
    }

    @Test
    void phaseLegal_passesForBattle() {
        assertThat(InvariantEngine.evaluate(List.of("PHASE_LEGAL"),
                obs(50, 100, 0, 0, 1, "BATTLE"))).isEmpty();
    }

    // ---- 反例：应判定违规 ----

    @Test
    void hpNonNegative_violatedWhenNegative() {
        assertThat(InvariantEngine.evaluate(List.of("HP>=0"),
                obs(-3, 100, 0, 0, 1, "BATTLE")))
                .anySatisfy(v -> assertThat(v).contains("HP>=0 violated"));
    }

    @Test
    void hpBelowMax_violatedWhenOver() {
        assertThat(InvariantEngine.evaluate(List.of("HP<=MaxHP"),
                obs(150, 100, 0, 0, 1, "BATTLE")))
                .anySatisfy(v -> assertThat(v).contains("HP<=MaxHP violated"));
    }

    @Test
    void enemyCount_violatedWhenOverCap() {
        assertThat(InvariantEngine.evaluate(List.of("ENEMY_COUNT<=CAP"),
                obs(50, 100, 201, 0, 1, "BATTLE")))
                .anySatisfy(v -> assertThat(v).contains("ENEMY_COUNT<=CAP violated"));
    }

    @Test
    void projectile_violatedWhenOverMax() {
        assertThat(InvariantEngine.evaluate(List.of("PROJECTILE<=MAX_PROJECT"),
                obs(50, 100, 0, 501, 1, "BATTLE")))
                .anySatisfy(v -> assertThat(v).contains("PROJECTILE<=MAX_PROJECT violated"));
    }

    @Test
    void phaseLegal_violatedWhenUnknown() {
        assertThat(InvariantEngine.evaluate(List.of("PHASE_LEGAL"),
                obs(50, 100, 0, 0, 1, "NONSENSE")))
                .anySatisfy(v -> assertThat(v).contains("PHASE_LEGAL violated"));
    }

    @Test
    void multipleInvariants_collectAllViolations() {
        List<String> vs = InvariantEngine.evaluate(
                List.of("HP>=0", "PHASE_LEGAL", "TICK>=0"),
                obs(-3, 100, 0, 0, 5, "NONSENSE"));
        assertThat(vs).hasSize(2)
                .anySatisfy(v -> assertThat(v).contains("HP>=0 violated"))
                .anySatisfy(v -> assertThat(v).contains("PHASE_LEGAL violated"));
    }

    // ---- 安全性：非法表达式明确报错，绝不静默执行 ----

    @Test
    void illegalField_rejected() {
        assertThatThrownBy(() -> InvariantEngine.evaluate(List.of("MANA>0"),
                obs(50, 100, 0, 0, 1, "BATTLE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未识别字段");
    }

    @Test
    void illegalOperator_rejected() {
        assertThatThrownBy(() -> InvariantEngine.evaluate(List.of("HP=?0"),
                obs(50, 100, 0, 0, 1, "BATTLE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未识别运算符");
    }

    @Test
    void unknownConstant_rejected() {
        assertThatThrownBy(() -> InvariantEngine.evaluate(List.of("HP>=FOO"),
                obs(50, 100, 0, 0, 1, "BATTLE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未识别常量");
    }

    // ---- enrich：合并回 Observation ----

    @Test
    void enrich_attachesViolationsToObservation() {
        GameObservation o = InvariantEngine.enrich(obs(-3, 100, 0, 0, 1, "BATTLE"));
        assertThat(o.invariantViolations())
                .anySatisfy(v -> assertThat(v).contains("HP>=0 violated"));
    }
}
