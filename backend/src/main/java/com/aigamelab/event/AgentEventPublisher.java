package com.aigamelab.event;

/**
 * 蓝图 §16：Agent 事件发布器。
 * P1-6 起由 PersistingAgentEventPublisher 实现：落库 agent_event + SSE 推送。
 */
public interface AgentEventPublisher {

    /**
     * 发布一次 Run 相关事件。
     *
     * @param type        事件类型（如 STEP_STARTED / TOOL_RETURNED / RUN_COMPLETED）
     * @param runId       所属 Run
     * @param payloadJson 事件负载 JSON（可 null）
     */
    void publish(String type, long runId, String payloadJson);
}
