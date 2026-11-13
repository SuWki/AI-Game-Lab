package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * 持久化事件（agent_event 表）。eventId(UUID) 兼作 SSE id: 行；
 * 自增 id 定全局序，供 Last-Event-ID 补发。runId/taskId 为 TEXT（蓝图原样），
 * 以便携带跨表标识；payloadJson 为事件负载。
 */
public record AgentEvent(
        Long id,
        String eventId,
        String type,
        String runId,
        String taskId,
        String payloadJson,
        LocalDateTime createdAt) {
}
