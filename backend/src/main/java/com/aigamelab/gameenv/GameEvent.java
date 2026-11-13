package com.aigamelab.gameenv;

import java.util.Map;

/**
 * T1-1：游戏内领域事件（drain 语义取走，技术设计 §3.1）。
 *
 * MVP type 约定（开放字符串不封闭）：PLAYER_SPAWNED / WAVE_STARTED / ENEMY_SPAWNED /
 * PROJECTILE_FIRED / ENEMY_KILLED / PLAYER_DAMAGED / PLAYER_DIED / LEVEL_UP /
 * UPGRADE_CHOSEN / PHASE_CHANGED / GAME_OVER / FAULT_INJECTED
 *
 * @param tick    事件发生的模拟时刻
 * @param type    事件类型
 * @param payload 事件负载（如 {"enemyId":7,"damage":12}）
 */
public record GameEvent(long tick, String type, Map<String, Object> payload) {

    public GameEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
