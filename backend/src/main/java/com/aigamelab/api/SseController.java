package com.aigamelab.api;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aigamelab.domain.AgentEvent;
import com.aigamelab.event.SsePayload;
import com.aigamelab.event.SseEmitterRegistry;
import com.aigamelab.repository.AgentEventRepository;

/**
 * 蓝图 §16：SSE 端点。
 * /sse/runs/{id}（该 Run）、/sse/global（全部）。
 * Last-Event-ID 断线重连：按自增 id 补发 ≤1000 条后进实时流；
 * 超 1000 条推 REPLAY_TRUNCATED 元事件提示整页刷新。
 */
@RestController
public class SseController {

    /** 单次补发上限（蓝图 §16）。 */
    static final int REPLAY_LIMIT = 1000;

    private final SseEmitterRegistry registry;
    private final AgentEventRepository eventRepo;

    public SseController(SseEmitterRegistry registry, AgentEventRepository eventRepo) {
        this.registry = registry;
        this.eventRepo = eventRepo;
    }

    @GetMapping(path = "/sse/runs/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeRun(@PathVariable long id,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(0L); // 长连接不超时
        Runnable unregister = registry.register(id, emitter);
        attachLifecycle(emitter, unregister);
        replay(emitter, lastEventId,
                afterId -> eventRepo.findByRunIdAfter(id, afterId, REPLAY_LIMIT + 1),
                "run=" + id);
        return emitter;
    }

    @GetMapping(path = "/sse/global", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeGlobal(
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        Runnable unregister = registry.registerGlobal(emitter);
        attachLifecycle(emitter, unregister);
        // global 无 run 维度过滤：Last-Event-ID 补发按全局序（P1 简化：仅发连接确认）
        sendComment(emitter, "connected global");
        return emitter;
    }

    /** 补发 + 超限元事件；补完进入实时流（emitter 已注册，listener 会继续推）。 */
    private void replay(SseEmitter emitter, String lastEventId,
            java.util.function.LongFunction<List<AgentEvent>> fetcher, String tag) {
        if (lastEventId == null || lastEventId.isBlank()) {
            sendComment(emitter, "connected " + tag);
            return;
        }
        long afterId;
        try {
            afterId = Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            sendComment(emitter, "connected " + tag + " (invalid Last-Event-ID)");
            return;
        }
        // 多取 1 条判断是否超限
        List<AgentEvent> events = fetcher.apply(afterId);
        boolean truncated = events.size() > REPLAY_LIMIT;
        List<AgentEvent> toSend = truncated ? events.subList(0, REPLAY_LIMIT) : events;
        for (AgentEvent e : toSend) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(e.id()))
                        .name(e.type())
                        .data(new SsePayload(e.id(), e.eventId(), e.type(), e.runId(),
                                e.payloadJson()), MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                return; // 连接已断
            }
        }
        if (truncated) {
            try {
                emitter.send(SseEmitter.event().name("REPLAY_TRUNCATED")
                        .data("{\"hint\":\"full-refresh\"}", MediaType.APPLICATION_JSON));
            } catch (Exception ignored) {
                // 连接已断
            }
        }
        sendComment(emitter, "replay-done " + tag);
    }

    private void sendComment(SseEmitter emitter, String comment) {
        try {
            emitter.send(SseEmitter.event().comment(comment));
        } catch (Exception ignored) {
            // 连接已断
        }
    }

    private void attachLifecycle(SseEmitter emitter, Runnable unregister) {
        emitter.onCompletion(unregister::run);
        emitter.onTimeout(unregister::run);
        emitter.onError(e -> unregister.run());
    }
}
