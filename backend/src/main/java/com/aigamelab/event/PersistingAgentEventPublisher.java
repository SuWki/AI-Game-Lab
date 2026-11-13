package com.aigamelab.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 蓝图 §16：真实发布器（替换 P1-2 NoOp 占位）。
 * 组装 DomainEventEnvelope → ApplicationEventPublisher 同步同线程发布，
 * 由 AgentEventListener 先落库后推 SSE（"可见必已持久化"）。
 */
@Component
public class PersistingAgentEventPublisher implements AgentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PersistingAgentEventPublisher.class);

    private final ApplicationEventPublisher events;

    public PersistingAgentEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void publish(String type, long runId, String payloadJson) {
        if (!AgentEventType.isValid(type)) {
            log.warn("[event] 未知事件类型被忽略: type={} runId={}", type, runId);
            return;
        }
        events.publishEvent(new DomainEventEnvelope(type, runId, null, payloadJson));
    }

    /** 带 taskId 的完整发布入口（P3 任务链路使用）。 */
    public void publish(String type, Long runId, Long taskId, String payloadJson) {
        if (!AgentEventType.isValid(type)) {
            log.warn("[event] 未知事件类型被忽略: type={} runId={} taskId={}", type, runId, taskId);
            return;
        }
        events.publishEvent(new DomainEventEnvelope(type, runId, taskId, payloadJson));
    }

    /** eventId 兼作 SSE id 行；此处仅生成 UUID，落库在 listener。 */
    static String newEventId() {
        return UUID.randomUUID().toString();
    }
}
