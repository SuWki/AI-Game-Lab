package com.aigamelab.event;

/**
 * 蓝图 §16：进程内领域事件包络。
 * Publisher 组装 → ApplicationEventPublisher 同步发布 → AgentEventListener 消费。
 */
public record DomainEventEnvelope(
        String type,
        Long runId,
        Long taskId,
        String payloadJson) {
}
