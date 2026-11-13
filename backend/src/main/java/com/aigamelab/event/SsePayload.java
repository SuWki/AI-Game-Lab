package com.aigamelab.event;

/**
 * SSE 数据负载形状（前端按 id 去重、按 type 分发）。
 * dbId=agent_event 自增主键（SSE id: 行，Last-Event-ID 断线补发基准）。
 */
public record SsePayload(
        long dbId,
        String eventId,
        String type,
        String runId,
        String payloadJson) {
}
