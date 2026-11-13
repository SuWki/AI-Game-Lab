package com.aigamelab.gameenv;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T1-2：确定性模拟游戏环境（技术设计 §3.2）。
 *
 * 与 Designer 的批量 Simulation Engine 是不同物：本类是单会话有状态机，
 * 供逐步 observe → act；Simulation 是无状态批量聚合器。
 *
 * 确定性契约：同 (seed, 动作序列, overrides) 必得逐字段相同的 Observation /
 * Event / Metrics 序列。实现方式：
 * - 所有随机经注入 SplittableRandom(seed)，RNG 只在 tick 推进内消费，
 *   动作语义本身零随机 ⇒ 消费序与 (动作序列 × tickBudget) 一一对应；
 * - 模拟时钟为整数 tick，metrics.uptimeMs 按 tick×常量推导，杜绝墙钟进入结果。
 *   （GameSessionHandle.startedAt 仅作句柄元数据，不参与任何比较。）
 *
 * 故障注入（overrides["inject.*"]，启动时固化/可控/可重复/正常模式零影响）：
 * - inject.hpNegativeAtTick     : 该 tick 起强制 hp=-3（非法 HP）
 * - inject.stuckPhaseAtTick     : 该 tick 起锁死在 BATTLE（软锁/非法状态转换）
 * - inject.enemyCountOverride   : 初始敌人数固定为该值（资源异常，可 >ENEMY_CAP）
 * - inject.projectileBurstAtTick: 该 tick 起弹丸数爆至 MAX_PROJECTILE+1（资源异常）
 *
 * Boundary 数值覆盖：player.maxHp（如 =1 的极限场景）。
 *
 * 状态检查（T1 内置确定性检查器；T3-4 由 InvariantEngine 替换）：
 * 对应技术设计 §5 谓词——HP>=0 / HP<=MaxHP / ENEMY_COUNT<=CAP /
 * PROJECTILE_COUNT<=MAX / TICK>=0 / PHASE_LEGAL。
 */
public final class FakeGameEnvironment implements GameAdapter {

    public static final String ADAPTER_ID = "fake-orbital-v1";

    /** 环境内置上限（Boundary 测试基准，对应需求文档 §6 Invariants） */
    public static final int ENEMY_CAP = 200;
    public static final int MAX_PROJECTILE = 500;

    /** uptime 推导常量：每 tick = 20ms（对应 dt=0.02s），避免墙钟破坏确定性 */
    private static final long MS_PER_TICK = 20;

    /** 故障注入键 */
    public static final String INJ_HP_NEGATIVE_AT_TICK = "inject.hpNegativeAtTick";
    public static final String INJ_STUCK_PHASE_AT_TICK = "inject.stuckPhaseAtTick";
    public static final String INJ_ENEMY_COUNT_OVERRIDE = "inject.enemyCountOverride";
    public static final String INJ_PROJECTILE_BURST_AT_TICK = "inject.projectileBurstAtTick";

    /** Boundary 数值覆盖键 */
    public static final String OVR_PLAYER_MAX_HP = "player.maxHp";

    /** 会话模型：3 波 × 100 tick，波间不间歇 */
    private static final int WAVES_PER_SESSION = 3;
    private static final int WAVE_TICKS = 100;
    private static final int SPAWN_INTERVAL_TICKS = 10;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ADAPTER_ID;
    }

    // ------------------------------------------------------------------
    // start
    // ------------------------------------------------------------------

    @Override
    public GameSessionHandle start(GameLaunchSpec spec) {
        String sessionId = "gs-" + UUID.randomUUID();
        sessions.put(sessionId, new Session(sessionId, spec));
        Session created = sessions.get(sessionId);
        // LOBBY 阶段的初始事件（PLAYER_SPAWNED 先于任何 act 可见）
        created.events.add(new GameEvent(0, "PLAYER_SPAWNED",
                Map.of("hp", created.maxHp, "profile", spec.playerProfile())));
        return new GameSessionHandle(sessionId, id(), LocalDateTime.now());
    }

    // ------------------------------------------------------------------
    // observe
    // ------------------------------------------------------------------

    @Override
    public GameObservation observe(String sessionId) {
        Session s = require(sessionId);
        return snapshot(s);
    }

    // ------------------------------------------------------------------
    // act
    // ------------------------------------------------------------------

    @Override
    public GameObservation act(String sessionId, GameAction action) {
        Session s = require(sessionId);
        if (action == null) {
            throw new com.aigamelab.tool.ToolException("action 不能为空");
        }
        if (!s.phaseEnded()) {
            applyAction(s, action);
            for (int i = 0; i < Math.max(0, action.tickBudget()); i++) {
                advanceTick(s);
                if (s.phaseEnded()) {
                    break;
                }
            }
            s.actionsExecuted++;
        } // GAME_OVER 后动作幂等 no-op（不计 actionsExecuted）
        return snapshot(s);
    }

    // ------------------------------------------------------------------
    // drainEvents / metrics / stop
    // ------------------------------------------------------------------

    @Override
    public List<GameEvent> drainEvents(String sessionId) {
        Session s = require(sessionId);
        List<GameEvent> out = new ArrayList<>(s.events);
        s.events.clear();
        return out;
    }

    @Override
    public GameMetrics metrics(String sessionId) {
        Session s = require(sessionId);
        return new GameMetrics(s.tick, s.damageDealt, s.damageTaken,
                s.kills, s.deaths, s.actionsExecuted, s.tick * MS_PER_TICK);
    }

    @Override
    public void stop(String sessionId) {
        sessions.remove(sessionId); // 幂等：未知 id 静默忽略（接口契约）
    }

    /** 测试辅助：当前活跃会话数（验证 stop 真正移除会话） */
    public int activeSessions() {
        return sessions.size();
    }

    // ==================================================================
    // 会话内部状态与确定性推进
    // ==================================================================

    private static final class Session {
        final String id;
        final long seed;
        /** 非 final：RESTART 时整体替换为同 seed 新实例（SplittableRandom 无 setSeed） */
        SplittableRandom rng;
        final Map<String, Object> overrides;

        long tick;
        String phase = GamePhase.LOBBY;
        int hp;
        int maxHp;
        int positionX;
        int positionY;
        int enemyCount;
        int projectileCount;
        long score;
        int level;
        final List<String> upgrades = new ArrayList<>();
        final Map<String, Boolean> flags = new LinkedHashMap<>();
        final List<GameEvent> events = new ArrayList<>();
        final List<String> violations = new ArrayList<>();

        // 统计
        long damageDealt;
        long damageTaken;
        long kills;
        long deaths;
        long actionsExecuted;

        // 注入参数（启动时固化，运行中不可变 ⇒ 可重复）
        final Long injHpNegativeAtTick;
        final Long injStuckPhaseAtTick;
        final Integer injEnemyCountOverride;
        final Long injProjectileBurstAtTick;

        Session(String sessionId, GameLaunchSpec spec) {
            this.id = sessionId;
            this.seed = spec.seed();
            this.rng = new SplittableRandom(spec.seed());
            this.overrides = spec.overrides() == null ? Map.of() : spec.overrides();
            this.injHpNegativeAtTick = numOf(INJ_HP_NEGATIVE_AT_TICK, Long.class);
            this.injStuckPhaseAtTick = numOf(INJ_STUCK_PHASE_AT_TICK, Long.class);
            this.injEnemyCountOverride = numOf(INJ_ENEMY_COUNT_OVERRIDE, Integer.class);
            this.injProjectileBurstAtTick = numOf(INJ_PROJECTILE_BURST_AT_TICK, Long.class);
            Integer ovrMaxHp = numOf(OVR_PLAYER_MAX_HP, Integer.class);
            this.maxHp = ovrMaxHp != null ? ovrMaxHp : 100;
            this.hp = this.maxHp;
            if (this.maxHp <= 0) {
                this.hp = 0; // maxHp=0 属合法边界输入，hp 不为负
            }
            this.enemyCount = this.injEnemyCountOverride != null ? this.injEnemyCountOverride : 0;
            this.flags.put("bossActive", false);
        }

        @SuppressWarnings("unchecked")
        <T extends Number> T numOf(String key, Class<T> type) {
            Object v = overrides.get(key);
            if (!(v instanceof Number n)) {
                return null;
            }
            return type == Integer.class ? (T) Integer.valueOf(n.intValue())
                    : (T) Long.valueOf(n.longValue());
        }

        boolean phaseEnded() {
            return GamePhase.GAME_OVER.equals(phase);
        }
    }

    /**
     * 单 tick 确定性推进：
     * 波次调度 → 刷怪(RNG) → 射击 → 命中杀伤(RNG) → 敌人接触伤害(RNG)
     * → 故障注入覆盖 → 结算/结束判定。
     */
    private void advanceTick(Session s) {
        s.tick++;

        // ---- 波次调度 ----
        int wave = waveOf(s.tick);
        if ((s.tick - 1) % WAVE_TICKS == 0 && wave <= WAVES_PER_SESSION) {
            setPhase(s, GamePhase.BATTLE);
            s.events.add(new GameEvent(s.tick, "WAVE_STARTED", Map.of("wave", wave)));
            s.flags.put("bossActive", wave == WAVES_PER_SESSION);
        }

        boolean battle = GamePhase.BATTLE.equals(displayedPhase(s));

        // ---- 刷怪（数量走 RNG ⇒ 不同 seed 行为分化）----
        if (battle && s.tick % SPAWN_INTERVAL_TICKS == 0) {
            int spawn = 1 + s.rng.nextInt(3); // 1..3
            for (int i = 0; i < spawn; i++) {
                s.enemyCount++;
                s.events.add(new GameEvent(s.tick, "ENEMY_SPAWNED",
                        Map.of("seq", s.enemyCount)));
            }
        }

        // ---- 射击 ----
        if (battle && s.tick % 5 == 0) {
            s.projectileCount = Math.min(MAX_PROJECTILE, s.projectileCount + 4);
            s.events.add(new GameEvent(s.tick, "PROJECTILE_FIRED", Map.of("count", 4)));
        }

        // ---- 命中杀伤 ----
        if (s.tick % 7 == 0 && s.enemyCount > 0) {
            int dmg = 8 + s.rng.nextInt(12); // 8..19
            s.projectileCount = Math.max(0, s.projectileCount - 2);
            s.enemyCount--;
            s.kills++;
            s.damageDealt += dmg;
            s.score += dmg * 10L;
            s.events.add(new GameEvent(s.tick, "ENEMY_KILLED", Map.of("damage", dmg)));
            if (s.kills % 4 == 0) { // 每 4 杀升 1 级并选升级
                s.level++;
                s.events.add(new GameEvent(s.tick, "LEVEL_UP", Map.of("level", s.level)));
                if (!s.upgrades.contains("dmg10")) {
                    s.upgrades.add("dmg10");
                    s.events.add(new GameEvent(s.tick, "UPGRADE_CHOSEN",
                            Map.of("upgradeId", "dmg10")));
                }
            }
        }

        // ---- 敌人接触伤害 ----
        if (battle && s.tick % 11 == 0 && s.enemyCount > 0) {
            int taken = 2 + s.rng.nextInt(6); // 2..7
            s.hp -= taken;
            s.damageTaken += taken;
            s.events.add(new GameEvent(s.tick, "PLAYER_DAMAGED", Map.of("damage", taken)));
            if (s.hp <= 0) {
                playerDied(s);
                return;
            }
        }

        // ---- 故障注入（自然伤害之后覆盖，制造非法状态）----
        applyFaultInjection(s);

        // ---- 结算/结束判定（软锁注入期间不演化终态：这正是"锁死"语义）----
        if (wave > WAVES_PER_SESSION && !softLocked(s)) {
            settleOrEnd(s);
        }
    }

    /** 显示 phase（含 stuckPhase 锁死效果：波次应结束仍停在 BATTLE） */
    private String displayedPhase(Session s) {
        if (s.injStuckPhaseAtTick != null && s.tick >= s.injStuckPhaseAtTick) {
            return GamePhase.BATTLE;
        }
        return s.phase;
    }

    /** 软锁判定：stuckPhase 注入生效期间，真实 phase 不再向 SETTLEMENT/GAME_OVER 演化 */
    private boolean softLocked(Session s) {
        return s.injStuckPhaseAtTick != null && s.tick >= s.injStuckPhaseAtTick;
    }

    private void setPhase(Session s, String target) {
        s.phase = target;
        s.events.add(new GameEvent(s.tick, "PHASE_CHANGED", Map.of("to", target)));
    }

    private void applyFaultInjection(Session s) {
        if (s.injHpNegativeAtTick != null && s.tick >= s.injHpNegativeAtTick) {
            s.hp = -3; // 非法 HP（Invariant: HP>=0）；死亡归零路径不会产生负值
            s.events.add(new GameEvent(s.tick, "FAULT_INJECTED", Map.of("kind", "HP_NEGATIVE")));
        }
        if (s.injProjectileBurstAtTick != null && s.tick >= s.injProjectileBurstAtTick) {
            s.projectileCount = MAX_PROJECTILE + 1; // 资源异常（Invariant: PROJECTILE<=MAX）
            s.events.add(new GameEvent(s.tick, "FAULT_INJECTED",
                    Map.of("kind", "PROJECTILE_BURST")));
        }
    }

    private void playerDied(Session s) {
        s.hp = 0; // 死亡时 hp 归零而非负数（合法边界）；hp<0 只能来自注入
        s.deaths++;
        setPhase(s, GamePhase.SETTLEMENT);
        s.events.add(new GameEvent(s.tick, "PLAYER_DIED", Map.of()));
    }

    private void settleOrEnd(Session s) {
        if (!GamePhase.SETTLEMENT.equals(displayedPhase(s))) {
            setPhase(s, GamePhase.SETTLEMENT);
        }
        if (s.tick >= WAVES_PER_SESSION * WAVE_TICKS + 5) {
            s.phase = GamePhase.GAME_OVER; // 终态不再发 PHASE_CHANGED（GAME_OVER 由 GAME_OVER 事件表达）
            s.events.add(new GameEvent(s.tick, "GAME_OVER", Map.of("score", s.score)));
        }
    }

    /** 当前 tick 所属波次（1-based）：tick=1..100 为第 1 波 */
    private static int waveOf(long tick) {
        return (int) ((tick - 1) / WAVE_TICKS) + 1;
    }

    // ------------------------------------------------------------------
    // 动作语义（MVP 最小集；开放 actionType，未知类型记事件不报错）
    // ------------------------------------------------------------------

    private void applyAction(Session s, GameAction a) {
        switch (a.actionType() == null ? "" : a.actionType().toUpperCase()) {
            case "MOVE" -> {
                String dir = textParam(a, "direction", "UP");
                switch (dir) {
                    case "UP" -> s.positionY++;
                    case "DOWN" -> s.positionY--;
                    case "LEFT" -> s.positionX--;
                    case "RIGHT" -> s.positionX++;
                    default -> { /* 未知方向忽略 */ }
                }
                s.events.add(new GameEvent(s.tick, "PLAYER_MOVED", Map.of("direction", dir)));
            }
            case "ATTACK" ->
                s.events.add(new GameEvent(s.tick, "PROJECTILE_FIRED", Map.of("count", 1)));
            case "CHOOSE_UPGRADE" -> {
                String id = textParam(a, "upgradeId", "unknown");
                if (!s.upgrades.contains(id)) {
                    s.upgrades.add(id);
                }
                s.events.add(new GameEvent(s.tick, "UPGRADE_CHOSEN", Map.of("upgradeId", id)));
            }
            case "WAIT" -> { /* 仅推进时间 */ }
            case "RESTART" -> restart(s);
            default -> s.events.add(new GameEvent(s.tick, "ACTION_IGNORED",
                    Map.of("actionType", String.valueOf(a.actionType()))));
        }
    }

    /**
     * RESTART：重掷 RNG 并复位全部进度字段。
     * 语义 = 同 seed 新开局 ⇒ "同 seed + 同动作序列" 在含 RESTART 的序列上依然可复现
     * （restart 后的演化只依赖 seed 与其后动作，与 restart 前的 rng 消费量无关）。
     */
    private void restart(Session s) {
        // 重掷 RNG（新实例，SplittableRandom 无 setSeed）。语义 = 同 seed 新开局 ⇒
        // "同 seed + 同动作序列" 在含 RESTART 的序列上依然可复现：
        // restart 后演化只依赖 seed 与其后动作，与 restart 前的 rng 消费量无关。
        s.rng = new SplittableRandom(s.seed);
        s.tick = 0;
        s.phase = GamePhase.LOBBY;
        s.hp = Math.max(0, s.maxHp);
        s.positionX = 0;
        s.positionY = 0;
        s.enemyCount = s.injEnemyCountOverride != null ? s.injEnemyCountOverride : 0;
        s.projectileCount = 0;
        s.score = 0;
        s.level = 0;
        s.upgrades.clear();
        s.flags.clear();
        s.flags.put("bossActive", false);
        s.deaths = 0;
        s.violations.clear();
        s.events.add(new GameEvent(0, "PLAYER_SPAWNED", Map.of("reset", true)));
    }

    private static String textParam(GameAction a, String key, String dflt) {
        if (a.params() != null && a.params().hasNonNull(key)) {
            return a.params().get(key).asText();
        }
        return dflt;
    }

    // ------------------------------------------------------------------
    // 快照与内置检查器
    // ------------------------------------------------------------------

    private GameObservation snapshot(Session s) {
        checkInvariants(s);
        return new GameObservation(s.id, s.tick, displayedPhase(s), s.hp, s.maxHp,
                s.positionX, s.positionY, s.enemyCount, s.projectileCount, s.score, s.level,
                List.copyOf(s.upgrades), Map.copyOf(s.flags), List.copyOf(s.violations));
    }

    private void checkInvariants(Session s) {
        s.violations.clear();
        if (s.hp < 0) {
            s.violations.add("HP>=0 violated: hp=" + s.hp);
        }
        if (s.hp > s.maxHp) {
            s.violations.add("HP<=MaxHP violated: hp=" + s.hp + " maxHp=" + s.maxHp);
        }
        if (s.enemyCount > ENEMY_CAP) {
            s.violations.add(
                    "ENEMY_COUNT<=CAP violated: enemyCount=" + s.enemyCount + " cap=" + ENEMY_CAP);
        }
        if (s.projectileCount > MAX_PROJECTILE) {
            s.violations.add(
                    "PROJECTILE_COUNT<=MAX violated: projectileCount=" + s.projectileCount);
        }
        if (s.tick < 0) {
            s.violations.add("TICK>=0 violated: tick=" + s.tick);
        }
        if (!GamePhase.isLegal(displayedPhase(s))) {
            s.violations.add("PHASE_LEGAL violated: phase=" + s.phase);
        }
    }

    private Session require(String sessionId) {
        Session s = sessions.get(sessionId);
        if (s == null) {
            throw new UnknownSessionException(sessionId);
        }
        return s;
    }
}
