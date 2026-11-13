package com.aigamelab.gameenv;

import java.util.Map;

import com.aigamelab.sim.GameConfig;

/**
 * T1-1：游戏启动规格（技术设计 §3.1）。
 *
 * @param seed          确定性随机种子
 * @param configVersion 被测 Config 版本标识（只读快照引用，Tester 禁止写 Config）
 * @param overrides     Boundary 注入/配置覆盖（如 {"player.maxHp":1}）+ 故障注入（如 inject.*）
 * @param playerProfile 玩家人格：RANDOM / SCRIPTED（MVP 仅此两种，需求文档 §4 其余为二期）
 * @param gameConfig    已解析的被测版本 GameConfig 快照（T7-1 显式解析；环境若忽略则仅作可观测/可校验用途）
 */
public record GameLaunchSpec(long seed, String configVersion,
        Map<String, Object> overrides, String playerProfile, GameConfig gameConfig) {

    /** SCRIPTED 默认人格 */
    public static final String PROFILE_SCRIPTED = "SCRIPTED";
    /** RANDOM 人格（随机输入进入确定性 RNG 路径的验证载体） */
    public static final String PROFILE_RANDOM = "RANDOM";

    public static GameLaunchSpec of(long seed, String configVersion) {
        return new GameLaunchSpec(seed, configVersion, Map.of(), PROFILE_SCRIPTED, null);
    }

    public static GameLaunchSpec of(long seed, String configVersion, Map<String, Object> overrides) {
        return new GameLaunchSpec(seed, configVersion, Map.copyOf(overrides), PROFILE_SCRIPTED, null);
    }

    /** 4-arg 便捷构造（不携带 GameConfig；保持既有调用方兼容）。 */
    public GameLaunchSpec(long seed, String configVersion, Map<String, Object> overrides,
            String playerProfile) {
        this(seed, configVersion, overrides, playerProfile, null);
    }

}
