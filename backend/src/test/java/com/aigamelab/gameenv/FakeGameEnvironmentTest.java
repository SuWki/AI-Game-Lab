package com.aigamelab.gameenv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T1 验收测试（技术设计 §13 测试策略 + T1 Sprint 指令 §七）：
 * 1. 正常生命周期 start→observe→act→observe
 * 2. Determinism：同 (seed, 动作序列, overrides) 双跑 Observation 逐字段一致
 * 3. 不同 seed：随机输入确实进入确定性 RNG 路径（seed 未被忽略），但不假设"必不同"
 * 4. 故障注入：正常模式正常结果；注入模式指定异常且可稳定复现
 */
@DisplayName("T1 FakeGameEnvironment：确定性模拟游戏环境")
class FakeGameEnvironmentTest {

    private final FakeGameEnvironment env = new FakeGameEnvironment();

    // ------------------------------------------------------------------
    // 1. 正常生命周期
    // ------------------------------------------------------------------

    @Test
    @DisplayName("生命周期：start→observe→act→observe 稳定运行")
    void lifecycle_start_observe_act_observe() {
        GameSessionHandle h = env.start(GameLaunchSpec.of(42, "v001"));

        assertThat(h.adapterId()).isEqualTo(FakeGameEnvironment.ADAPTER_ID);
        assertThat(h.sessionId()).isNotBlank();

        // 初始观察：LOBBY，满血，零进度
        GameObservation o0 = env.observe(h.sessionId());
        assertThat(o0.tick()).isZero();
        assertThat(o0.phase()).isEqualTo(GamePhase.LOBBY);
        assertThat(o0.hp()).isEqualTo(o0.maxHp()).isEqualTo(100);

        // act 推进后：tick 前进、进入战斗、位置因 MOVE 改变
        GameObservation o1 = env.act(h.sessionId(),
                GameAction.of("MOVE", "{\"direction\":\"RIGHT\"}", 30));
        assertThat(o1.tick()).isEqualTo(30);
        assertThat(o1.phase()).isEqualTo(GamePhase.BATTLE); // tick=1 触发 WAVE_STARTED
        assertThat(o1.positionX()).isEqualTo(1);
        assertThat(o1.enemyCount()).isPositive(); // tick=10/20/30 已刷怪

        // observe 与 act 返回一致（同一状态）
        assertThat(env.observe(h.sessionId())).isEqualTo(o1);

        env.stop(h.sessionId());
        assertThat(env.activeSessions()).isZero();
    }

    @Test
    @DisplayName("未知 sessionId：observe/metrics/drainEvents 抛 UnknownSessionException；stop 幂等")
    void unknownSessionRejected_stopIdempotent() {
        assertThatThrownBy(() -> env.observe("nope")).isInstanceOf(UnknownSessionException.class);
        assertThatThrownBy(() -> env.metrics("nope")).isInstanceOf(UnknownSessionException.class);
        assertThatThrownBy(() -> env.drainEvents("nope"))
                .isInstanceOf(UnknownSessionException.class);

        GameSessionHandle h = env.start(GameLaunchSpec.of(1, "v001"));
        env.stop(h.sessionId());
        env.stop(h.sessionId()); // 二次 stop 不抛
        assertThat(env.activeSessions()).isZero();
    }

    @Test
    @DisplayName("drainEvents 语义：取后即清；事件按 tick 升序")
    void drainEvents_semantics() {
        GameSessionHandle h = env.start(GameLaunchSpec.of(42, "v001"));
        env.act(h.sessionId(), GameAction.of("WAIT", null, 25));

        List<GameEvent> first = env.drainEvents(h.sessionId());
        List<GameEvent> second = env.drainEvents(h.sessionId());
        assertThat(second).isEmpty();
        assertThat(first).isNotEmpty();
        for (int i = 1; i < first.size(); i++) {
            assertThat(first.get(i).tick())
                    .as("事件应按 tick 升序: %s vs %s", first.get(i - 1), first.get(i))
                    .isGreaterThanOrEqualTo(first.get(i - 1).tick());
        }
        // 初始 spawn + 波次启动 + 刷怪事件均应出现
        assertThat(first).extracting(GameEvent::type)
                .contains("PLAYER_SPAWNED", "WAVE_STARTED", "ENEMY_SPAWNED");
    }

    // ------------------------------------------------------------------
    // 2. Determinism（核心验收）
    // ------------------------------------------------------------------

    /** 固定动作剧本 */
    private static List<GameAction> script() {
        return List.of(
                GameAction.of("MOVE", "{\"direction\":\"UP\"}", 20),
                GameAction.of("ATTACK", null, 15),
                GameAction.of("WAIT", null, 30),
                GameAction.of("MOVE", "{\"direction\":\"LEFT\"}", 25),
                GameAction.of("CHOOSE_UPGRADE", "{\"upgradeId\":\"dmg10\"}", 40));
    }

    /** 双跑并逐字段比对全部 Observation + Events 摘要 + Metrics */
    private void assertDeterministic(long seed, Map<String, Object> overrides,
            List<GameAction> actions) {
        Function<Void, List<Object>> run = ignored -> {
            GameSessionHandle h = env.start(new GameLaunchSpec(seed, "v001", overrides,
                    GameLaunchSpec.PROFILE_SCRIPTED));
            List<Object> trace = new ArrayList<>();
            for (GameAction a : actions) {
                trace.add(env.act(h.sessionId(), a));
            }
            trace.add(env.metrics(h.sessionId()));
            trace.add(env.drainEvents(h.sessionId()));
            env.stop(h.sessionId());
            return trace;
        };
        List<Object> r1 = run.apply(null);
        List<Object> r2 = run.apply(null);

        // Observation 逐字段一致（record equals 即逐字段比较——含 upgrades/flags/violations）
        for (int i = 0; i < actions.size(); i++) {
            assertThat(r2.get(i)).as("第 %d 个动作后的 Observation 必须逐字段一致", i + 1)
                    .isEqualTo(r1.get(i));
        }
        assertThat(r2.get(actions.size())).as("Metrics 一致").isEqualTo(r1.get(actions.size()));
        assertThat(r2.get(actions.size() + 1)).as("Events 序列一致")
                .isEqualTo(r1.get(actions.size() + 1));
    }

    @Test
    @DisplayName("Determinism：seed=123 同剧本双跑 O1..O5 逐字段完全一致")
    void determinism_sameSeedSameActions() {
        assertDeterministic(123L, Map.of(), script());
    }

    @Test
    @DisplayName("Determinism：多 seed × 含 RESTART 的剧本全部双跑一致")
    void determinism_withRestart_multipleSeeds() {
        List<GameAction> withRestart = List.of(
                GameAction.of("MOVE", "{\"direction\":\"DOWN\"}", 35),
                GameAction.of("RESTART", null, 50), // 中途重开：rng 重掷后仍须可复现
                GameAction.of("ATTACK", null, 20));
        for (long seed : new long[] {7L, 123L, 999_999L}) {
            assertDeterministic(seed, Map.of(), withRestart);
        }
    }

    @Test
    @DisplayName("Determinism：相同 overrides（Boundary maxHp=1）下双跑一致")
    void determinism_withOverrides() {
        assertDeterministic(42L, Map.of(FakeGameEnvironment.OVR_PLAYER_MAX_HP, 1), script());
    }

    // ------------------------------------------------------------------
    // 3. 不同 seed：RNG 真实参与（不假设"必不同"）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Seed 分化：多 seed 下刷怪总量呈分布（证明 seed 进入 RNG 路径而非被忽略）")
    void differentSeeds_feedRng() {
        // 单个动作推进 300 tick ⇒ 每 10 tick 消费一次 rng.nextInt(3)。
        // 若 seed 被忽略，所有 seed 的 enemyKilledTotal 完全相同的概率≈0；
        // 断言"分布非退化"（≥2 种不同取值）即可稳健证明 RNG 参与。
        var totals = new java.util.HashMap<Long, Long>();
        for (long seed : new long[] {1, 2, 3, 4, 5, 6, 7, 8}) {
            GameSessionHandle h = env.start(GameLaunchSpec.of(seed, "v001"));
            env.act(h.sessionId(), GameAction.of("WAIT", null, 305));
            long spawned = env.metrics(h.sessionId()).ticksElapsed() > 0
                    ? countEvents(h, "ENEMY_SPAWNED") : 0;
            totals.put(seed, spawned);
            env.stop(h.sessionId());
        }
        assertThat(totals.values().stream().distinct().count())
                .as("8 个 seed 至少产生 2 种刷怪总量（否则 seed 未生效）")
                .isGreaterThanOrEqualTo(2);
        // 同时单 seed 自身必须稳定（同 seed 再跑一次同值）
        long seed1Again = spawnCountFor(1);
        assertThat(spawnCountFor(1)).isEqualTo(seed1Again);
    }

    private long spawnCountFor(long seed) {
        GameSessionHandle h = env.start(GameLaunchSpec.of(seed, "v001"));
        env.act(h.sessionId(), GameAction.of("WAIT", null, 305));
        long n = countEvents(h, "ENEMY_SPAWNED");
        env.stop(h.sessionId());
        return n;
    }

    private long countEvents(GameSessionHandle h, String type) {
        return env.drainEvents(h.sessionId()).stream()
                .filter(e -> type.equals(e.type())).count();
    }

    // ------------------------------------------------------------------
    // 4. 故障注入
    // ------------------------------------------------------------------

    @Test
    @DisplayName("正常模式零误报：健康会话全程无 invariantViolations")
    void healthyRun_noViolations() {
        GameSessionHandle h = env.start(GameLaunchSpec.of(42, "v001"));
        for (GameAction a : script()) {
            GameObservation o = env.act(h.sessionId(), a);
            if (!o.invariantViolations().isEmpty()) {
                // hp<=0 死亡归零是合法边界，不应产生违规；此处兜底输出便于诊断
                throw new AssertionError("健康会话出现违规: " + o.invariantViolations()
                        + " @tick=" + o.tick());
            }
        }
        env.stop(h.sessionId());
    }

    @Test
    @DisplayName("注入 HP_NEGATIVE：指定 tick 后 hp<0 且违规被捕获，双跑复现")
    void faultInjection_hpNegative_reproducible() {
        var overrides = Map.<String, Object>of(FakeGameEnvironment.INJ_HP_NEGATIVE_AT_TICK, 60L);
        Function<Void, GameObservation> probe = ignored -> {
            GameSessionHandle h = env.start(
                    new GameLaunchSpec(42L, "v001", overrides, GameLaunchSpec.PROFILE_SCRIPTED));
            GameObservation o = env.act(h.sessionId(), GameAction.of("WAIT", null, 80));
            env.stop(h.sessionId());
            return o;
        };

        GameObservation o1 = probe.apply(null);
        assertThat(o1.hp()).as("注入后 hp 应为 -3").isEqualTo(-3);
        assertThat(o1.invariantViolations())
                .anyMatch(v -> v.startsWith("HP>=0 violated"));
        // FAULT_INJECTED 事件已随 drain 输出（probe 内 stop），此处仅验证状态可复现：

        GameObservation o2 = probe.apply(null);
        assertThat(o2).as("注入故障必须可重复").isEqualTo(o1);
    }

    @Test
    @DisplayName("注入 STUCK_PHASE：波次结束后仍锁死 BATTLE（软锁可检测）")
    void faultInjection_stuckPhase() {
        var overrides = Map.<String, Object>of(FakeGameEnvironment.INJ_STUCK_PHASE_AT_TICK, 120L);
        GameSessionHandle h = env.start(
                new GameLaunchSpec(42L, "v001", overrides, GameLaunchSpec.PROFILE_SCRIPTED));
        // 推进到第 2 波之后：正常应在 tick=201 回 LOBBY→BATTLE 循环，
        // 锁死场景下 phase 恒为 BATTLE 且 GAME_OVER 不出现（软锁特征）
        GameObservation o = env.act(h.sessionId(), GameAction.of("WAIT", null, 330));
        assertThat(o.tick()).isEqualTo(330);
        assertThat(o.phase()).as("stuckPhase 注入下不得进入终态").isEqualTo(GamePhase.BATTLE);
        env.stop(h.sessionId());
    }

    @Test
    @DisplayName("注入 PROJECTILE_BURST：弹丸数超 MAX_PROJECTILE 上限被捕获")
    void faultInjection_projectileBurst() {
        var overrides = Map.<String, Object>of(FakeGameEnvironment.INJ_PROJECTILE_BURST_AT_TICK, 50L);
        GameSessionHandle h = env.start(
                new GameLaunchSpec(42L, "v001", overrides, GameLaunchSpec.PROFILE_SCRIPTED));
        GameObservation o = env.act(h.sessionId(), GameAction.of("WAIT", null, 55));
        assertThat(o.projectileCount())
                .as("爆量后应超上限一档").isEqualTo(FakeGameEnvironment.MAX_PROJECTILE + 1);
        assertThat(o.invariantViolations())
                .anyMatch(v -> v.startsWith("PROJECTILE_COUNT<=MAX violated"));
        env.stop(h.sessionId());
    }

    @Test
    @DisplayName("注入 ENEMY_COUNT_OVERRIDE：初始敌人数可控（资源异常入口）")
    void faultInjection_enemyCountOverride() {
        var overrides = Map.<String, Object>of(
                FakeGameEnvironment.INJ_ENEMY_COUNT_OVERRIDE, FakeGameEnvironment.ENEMY_CAP + 5);
        GameSessionHandle h = env.start(
                new GameLaunchSpec(42L, "v001", overrides, GameLaunchSpec.PROFILE_SCRIPTED));
        GameObservation o0 = env.observe(h.sessionId());
        assertThat(o0.enemyCount()).isEqualTo(FakeGameEnvironment.ENEMY_CAP + 5);
        assertThat(o0.invariantViolations())
                .anyMatch(v -> v.startsWith("ENEMY_COUNT<=CAP violated"));
        env.stop(h.sessionId());
    }

    // ------------------------------------------------------------------
    // Boundary 覆盖
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Boundary：maxHp=1 极限配置下正常演化且 hp 恒 >=0")
    void boundary_maxHp1_staysLegal() {
        var overrides = Map.<String, Object>of(FakeGameEnvironment.OVR_PLAYER_MAX_HP, 1);
        GameSessionHandle h = env.start(
                new GameLaunchSpec(43L, "v001", overrides, GameLaunchSpec.PROFILE_SCRIPTED));
        GameObservation last = null;
        for (int i = 0; i < 6; i++) {
            last = env.act(h.sessionId(), GameAction.of("WAIT", null, 20));
            assertThat(last.maxHp()).isEqualTo(1);
            assertThat(last.hp()).as("hp 不得为负（死亡归零路径）").isGreaterThanOrEqualTo(0);
        }
        env.stop(h.sessionId());
    }

    @Test
    @DisplayName("Metrics 累计正确：actionsExecuted / ticksElapsed / uptimeMs 推导无墙钟")
    void metrics_accumulate_deterministically() {
        GameSessionHandle h = env.start(GameLaunchSpec.of(42, "v001"));
        env.act(h.sessionId(), GameAction.of("MOVE", null, 10));
        env.act(h.sessionId(), GameAction.of("ATTACK", null, 5));
        GameMetrics m = env.metrics(h.sessionId());
        assertThat(m.actionsExecuted()).isEqualTo(2);
        assertThat(m.ticksElapsed()).isEqualTo(15);
        assertThat(m.uptimeMs()).isEqualTo(15 * 20); // tick×常量推导，非墙钟
        env.stop(h.sessionId());
    }
}
