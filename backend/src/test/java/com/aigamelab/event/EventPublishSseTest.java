package com.aigamelab.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aigamelab.domain.AgentEvent;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.repository.AgentEventRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.AgentLoop;
import com.aigamelab.tool.StubTool;
import com.aigamelab.tool.ToolRegistry;

/**
 * P1-6 验收测试（03 文档）：AgentLoop 跑 Fake 剧本时事件落库 agent_event，
 * STEP_STARTED/RUN_COMPLETED 序列正确；Last-Event-ID 补发语义正确；
 * emitter 注册/注销生命周期正确。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@org.springframework.context.annotation.Import(com.aigamelab.runtime.RuntimeTestTools.class)
@DisplayName("P1-6 事件发布与 SSE 推送链路")
class EventPublishSseTest {

    @Autowired AgentLoop loop;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired AgentEventRepository eventRepo;
    @Autowired SseEmitterRegistry registry;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;
    @Autowired ToolRegistry toolRegistry;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p16-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Path DB = tempDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:"
                + DB.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON");
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class FakeConfig {
        @org.springframework.context.annotation.Bean
        FakeLlmGateway fakeLlmGateway() {
            return new FakeLlmGateway();
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    private Task newTask() {
        return taskRepo.insert(new Task(null, "t", "g", "{}", 50, false, "ACTIVE", null));
    }

    private Run newRun() {
        return runRepo.insert(new Run(null, newTask().id(),
                "PENDING", null, null, 0, 50, 1000000, 0, null, null, null, null, null));
    }

    @Test
    void loopRunPersistsEventSequence() {
        int base = llm.callCount();
        llm.thenToolCalls(new com.aigamelab.llm.dto.ToolCall("c1", "ev_x", "{}"))
                .thenStop("完成");
        if (!toolRegistry.contains("ev_x")) {
            StubTool t = new StubTool("ev_x");
            t.defaultSuccess("{}", "ok");
            toolRegistry.register(t);
        }

        Run run = newRun();
        loop.run(run.id());

        // 落库序列：每步 STEP_STARTED + 终态 RUN_COMPLETED，全局序单调递增
        List<AgentEvent> events = eventRepo.findByRunIdAfter(run.id(), 0, 1000);
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).type()).isEqualTo("STEP_STARTED");
        List<String> types = events.stream().map(AgentEvent::type).toList();
        assertThat(types).contains("STEP_STARTED", "RUN_COMPLETED");
        long last = 0;
        for (AgentEvent e : events) {
            assertThat(e.id()).isGreaterThan(last);
            last = e.id();
        }
        // 验收标准：agent_event 表行数与查询一致
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_event WHERE run_id=?",
                Integer.class, String.valueOf(run.id()));
        assertThat(total).isEqualTo(events.size());
        assertThat(llm.callCount() - base).isEqualTo(2);
    }

    @Test
    void unknownEventTypeIsRejectedBeforePublishing() {
        int before = eventRepo.findRecent(1000).size();
        // 非法 type 在 publisher 校验分支被拒，不触达 Spring 总线也不落库
        PersistingAgentEventPublisher p = new PersistingAgentEventPublisher(evt -> {
            throw new IllegalStateException("不应发布到 Spring 总线");
        });
        p.publish("NOT_A_TYPE", 1L, "{}");
        assertThat(eventRepo.findRecent(1000).size()).isEqualTo(before);
    }

    @Test
    void listenerPersistsThenRegistersCleanup() {
        Run run = newRun();
        SseEmitter emitter = new SseEmitter(0L);
        Runnable unregister = registry.register(run.id(), emitter);
        try {
            AgentEventListener listener = new AgentEventListener(eventRepo, registry);
            listener.onDomainEvent(
                    new DomainEventEnvelope("STEP_STARTED", run.id(), null, "{\"seq\":1}"));

            List<AgentEvent> saved = eventRepo.findByRunIdAfter(run.id(), 0, 10);
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).type()).isEqualTo("STEP_STARTED");
            assertThat(saved.get(0).runId()).isEqualTo(String.valueOf(run.id()));
            assertThat(registry.runConnectionCount(run.id())).isEqualTo(1);
        } finally {
            unregister.run();
        }
        assertThat(registry.runConnectionCount(run.id())).isZero();
    }

    @Test
    void replayAfterLastEventIdReturnsOnlyNewerEvents() {
        Run run = newRun();
        AgentEventListener listener = new AgentEventListener(eventRepo, registry);
        listener.onDomainEvent(new DomainEventEnvelope("STEP_STARTED", run.id(), null, "{}"));
        listener.onDomainEvent(new DomainEventEnvelope("TOOL_RETURNED", run.id(), null, "{}"));
        List<AgentEvent> mid = eventRepo.findByRunIdAfter(run.id(), 0, 10);
        assertThat(mid).hasSize(2);

        // 模拟 Last-Event-ID=第一条 dbId 的断线补发：只回其后的事件
        long afterId = mid.get(0).id();
        List<AgentEvent> replayed = eventRepo.findByRunIdAfter(run.id(), afterId, 1000);
        assertThat(replayed).hasSize(1);
        assertThat(replayed.get(0).type()).isEqualTo("TOOL_RETURNED");

        // 已追平则无补发
        assertThat(eventRepo.findByRunIdAfter(run.id(), mid.get(1).id(), 1000)).isEmpty();
    }

    @Test
    void eventTypeEnumMatchesBlueprint16Types() {
        assertThat(AgentEventType.isValid("STEP_STARTED")).isTrue();
        assertThat(AgentEventType.isValid("RUN_CANCELLED")).isTrue();
        assertThat(AgentEventType.isValid("MADE_UP")).isFalse();
        // 蓝图 §16：16 类原事件 + T4-3 追加 6 类 TEST_* + R0-8 追加 4 类 RESEARCH_* = 26
        assertThat(AgentEventType.values()).hasSize(27);
        assertThat(AgentEventType.isValid("RUN_TIMEOUT")).isTrue();
    }

    @Test
    void sseEmitterLifecycleUnregistersOnCompletion() {
        Run run = newRun();
        SseEmitter emitter = new SseEmitter(0L);
        Runnable unregister = registry.register(run.id(), emitter);
        assertThat(registry.runConnectionCount(run.id())).isEqualTo(1);
        unregister.run();
        assertThat(registry.runConnectionCount(run.id())).isZero();

        Runnable g = registry.registerGlobal(emitter);
        assertThat(registry.globalConnectionCount()).isEqualTo(1);
        g.run();
        assertThat(registry.globalConnectionCount()).isZero();
    }
}
