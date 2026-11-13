package com.aigamelab.event;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.aigamelab.domain.AgentEvent;
import com.aigamelab.repository.AgentEventRepository;

/**
 * 蓝图 §16：事件监听器（同步 @EventListener，与发布同线程）。
 * ① INSERT agent_event（拿自增 id 定全局序）→ ② 推送 SseEmitterRegistry。
 * 先落库后推送保证"可见必已持久化"；推送失败只移除 emitter，绝不影响主流程。
 */
@Component
public class AgentEventListener {

    private static final Logger log = LoggerFactory.getLogger(AgentEventListener.class);

    private final AgentEventRepository eventRepo;
    private final SseEmitterRegistry emitterRegistry;

    public AgentEventListener(AgentEventRepository eventRepo, SseEmitterRegistry emitterRegistry) {
        this.eventRepo = eventRepo;
        this.emitterRegistry = emitterRegistry;
    }

    @EventListener
    public void onDomainEvent(DomainEventEnvelope envelope) {
        // ① 落库（自增 id = 全局序）
        AgentEvent saved = eventRepo.insert(new AgentEvent(
                null, UUID.randomUUID().toString(), envelope.type(),
                envelope.runId() == null ? null : String.valueOf(envelope.runId()),
                envelope.taskId() == null ? null : String.valueOf(envelope.taskId()),
                envelope.payloadJson(), null));

        // ② 推送：runId 非空 → per-run + global；runId 为空 → 仅 global
        String name = envelope.type();
        Object payload = new SsePayload(saved.id(), saved.eventId(),
                envelope.type(), saved.runId(), saved.payloadJson());
        try {
            if (envelope.runId() != null) {
                emitterRegistry.pushToRun(envelope.runId(), name, payload, saved.id());
            }
            emitterRegistry.pushToGlobal(name, payload, saved.id());
        } catch (RuntimeException e) {
            // 双保险：registry 内部已吞推送异常，此处兜底不阻断业务线程
            log.warn("[event] SSE 推送异常(已忽略): type={} run={}", name, envelope.runId(), e);
        }
    }
}
