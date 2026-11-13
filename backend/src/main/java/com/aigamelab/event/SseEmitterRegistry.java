package com.aigamelab.event;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 蓝图 §16：SSE 连接注册表。
 * per-run 与 global 两类 emitter；推送失败即移除，绝不影响主流程。
 */
@Component
public class SseEmitterRegistry {

    private final Map<Long, List<SseEmitter>> byRun = new ConcurrentHashMap<>();
    private final List<SseEmitter> global = new CopyOnWriteArrayList<>();

    /** 注册 per-run 连接；返回的 Runnable 用于连接关闭时注销。 */
    public Runnable register(long runId, SseEmitter emitter) {
        List<SseEmitter> list = byRun.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        return () -> unregister(byRun.get(runId), emitter);
    }

    public Runnable registerGlobal(SseEmitter emitter) {
        global.add(emitter);
        return () -> unregister(global, emitter);
    }

    /**
     * 推送到指定 Run 的全部连接。name=事件类型，id=dbId（供 Last-Event-ID）。
     * 内部吞掉所有推送异常并移除死连接，调用方无需处理。
     */
    public void pushToRun(long runId, String name, Object payload, long dbId) {
        List<SseEmitter> list = byRun.get(runId);
        if (list != null) {
            pushAll(list, name, payload, dbId);
        }
    }

    public void pushToGlobal(String name, Object payload, long dbId) {
        pushAll(global, name, payload, dbId);
    }

    /** 测试/Dev 控制器用：当前连接数快照。 */
    public int runConnectionCount(long runId) {
        List<SseEmitter> list = byRun.get(runId);
        return list == null ? 0 : list.size();
    }

    public int globalConnectionCount() {
        return global.size();
    }

    private void pushAll(List<SseEmitter> emitters, String name, Object payload, long dbId) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(dbId))
                        .name(name)
                        .data(payload, org.springframework.http.MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // 断线/超时：移除死连接，不影响其余连接与业务主流程
                emitters.remove(emitter);
            }
        }
    }

    private void unregister(List<SseEmitter> list, SseEmitter emitter) {
        if (list != null) {
            list.remove(emitter);
        }
    }
}
