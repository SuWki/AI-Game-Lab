package com.aigamelab.gameenv;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * T1-1：游戏测试环境抽象（技术设计 §3.1 冻结契约）。
 *
 * 表达通用测试交互能力（start / observe / act / drainEvents / metrics / stop），
 * 禁止耦合具体游戏业务 API（attack/jump/useSkill 等）——
 * 具体玩法通过 {@link GameAction#actionType()} 开放字符串 + 参数 JSON 表达。
 *
 * 未来 WebGameAdapter / UnityAdapter 实现同一接口，Tools 层不变。
 */
public interface GameAdapter {

    /** Adapter 标识，如 "fake-orbital-v1" */
    String id();

    /** 启动会话；同一 seed + spec 必得确定性会话（FakeGameEnvironment 契约） */
    GameSessionHandle start(GameLaunchSpec spec);

    /** 全量状态快照（含 invariantViolations，由环境自行填充） */
    GameObservation observe(String sessionId);

    /**
     * 执行动作并推进至多 action.tickBudget() 个模拟 tick，返回执行后快照。
     * GAME_OVER 后调用为幂等 no-op。未知 sessionId 抛 {@link UnknownSessionException}。
     */
    GameObservation act(String sessionId, GameAction action);

    /**
     * 取走自上次调用以来的增量事件（drain 语义：取后即清）。
     * 未知 sessionId 抛 {@link UnknownSessionException}。
     */
    List<GameEvent> drainEvents(String sessionId);

    /** 自会话启动以来的累计指标。未知 sessionId 抛 {@link UnknownSessionException}。 */
    GameMetrics metrics(String sessionId);

    /** 结束并移除会话。未知 sessionId 静默忽略（stop 幂等）。 */
    void stop(String sessionId);
}
