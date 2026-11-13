package com.aigamelab.gameenv;

import java.util.Set;

/**
 * T1-1：游戏阶段白名单（State Transition 测试的合法状态集，
 * 对应技术设计 §5 Invariant `PHASE∈{LOBBY,BATTLE,SETTLEMENT,GAME_OVER}`）。
 *
 * 开放字符串 phase 的合法值集合；非法 phase 即 StateTransition 类 Finding。
 */
public final class GamePhase {

    public static final String LOBBY = "LOBBY";
    public static final String BATTLE = "BATTLE";
    public static final String SETTLEMENT = "SETTLEMENT";
    public static final String GAME_OVER = "GAME_OVER";

    private static final Set<String> LEGAL = Set.of(LOBBY, BATTLE, SETTLEMENT, GAME_OVER);

    public static boolean isLegal(String phase) {
        return phase != null && LEGAL.contains(phase);
    }

    public static Set<String> legalValues() {
        return Set.copyOf(LEGAL);
    }

    private GamePhase() {
    }
}
