package com.aigamelab.gameenv;

import java.util.List;
import java.util.Map;

/**
 * T1-1：游戏状态全量快照（对齐需求文档 §3 状态示例）。
 *
 * @param sessionId            所属会话
 * @param tick                 模拟时钟（单调递增）
 * @param phase                游戏阶段，合法值见 {@link GamePhase}
 * @param hp / maxHp           玩家生命
 * @param positionX/positionY  玩家位置
 * @param enemyCount           当前敌人数
 * @param projectileCount      当前弹丸数
 * @param score                得分（>=0）
 * @param level                玩家等级
 * @param upgrades             已选升级 id 列表
 * @param flags                附加布尔标志（如 bossActive）
 * @param invariantViolations  本快照触发的 Invariant 违规描述（T3-4 由 InvariantEngine 填充；
 *                             T1 阶段由 FakeGameEnvironment 的确定性检查器直接填充）
 */
public record GameObservation(
        String sessionId,
        long tick,
        String phase,
        int hp,
        int maxHp,
        int positionX,
        int positionY,
        int enemyCount,
        int projectileCount,
        long score,
        int level,
        List<String> upgrades,
        Map<String, Boolean> flags,
        List<String> invariantViolations) {

    public GameObservation {
        upgrades = upgrades == null ? List.of() : List.copyOf(upgrades);
        flags = flags == null ? Map.of() : Map.copyOf(flags);
        invariantViolations = invariantViolations == null ? List.of()
                : List.copyOf(invariantViolations);
    }

    /**
     * 确定性契约的相等语义：sessionId 是会话元数据而非游戏状态，
     * 双跑对比时必须逐字段一致的是状态字段——equals/hashCode 排除 sessionId。
     * 字段访问器不受影响。
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GameObservation other)) {
            return false;
        }
        return tick == other.tick && hp == other.hp && maxHp == other.maxHp
                && positionX == other.positionX && positionY == other.positionY
                && enemyCount == other.enemyCount && projectileCount == other.projectileCount
                && score == other.score && level == other.level
                && java.util.Objects.equals(phase, other.phase)
                && upgrades.equals(other.upgrades)
                && flags.equals(other.flags)
                && invariantViolations.equals(other.invariantViolations);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(tick, phase, hp, maxHp, positionX, positionY,
                enemyCount, projectileCount, score, level, upgrades, flags, invariantViolations);
    }
}
