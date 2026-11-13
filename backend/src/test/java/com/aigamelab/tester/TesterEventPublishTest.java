package com.aigamelab.tester;

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

import com.aigamelab.domain.AgentEvent;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.gameenv.FakeGameEnvironment;
import com.aigamelab.repository.AgentEventRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T4-3 验收：Tester 线事件枚举追加后，各 Tester Tool / Service 经真实 Runtime
 * 发布 TEST_* 事件并落库 agent_event（PersistingAgentEventPublisher 不再 warn+drop）。
 * 旧事件（STEP_STARTED 等）回归不变。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T4-3 Tester 事件枚举发布与落库")
class TesterEventPublishTest {

    @Autowired GameStartTool gameStartTool;
    @Autowired GameObserveTool gameObserveTool;
    @Autowired GameActTool gameActTool;
    @Autowired FindingReportTool findingReportTool;
    @Autowired TestReportCreateTool testReportCreateTool;
    @Autowired TestPlanService testPlanService;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired AgentEventRepository eventRepo;
    @Autowired JdbcTemplate jdbc;

    static final ObjectMapper M = new ObjectMapper();

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-t43-", ".db");
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

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM task");
    }

    private long realRunId() {
        Task task = taskRepo.insert(new Task(null, "t", "g", "{}", 50, false, "ACTIVE", null));
        Run run = runRepo.insert(new Run(null, task.id(), "RUNNING", null,
                "deepseek-chat", 0, 50, 100000, 0, null, null, null, null, null));
        return run.id();
    }

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void gameStartPublishesSessionStarted() {
        long runId = realRunId();
        // GameTool.execute 公开入口（protected doExecute 经此调用）
        gameStartTool.execute(json("{\"seed\":42,\"configVersion\":\"v001\"}"),
                ToolContext.of(runId, 1L));
        var events = eventRepo.findByRunIdAfter(runId, 0, 1000);
        var types = events.stream().map(AgentEvent::type).toList();
        assertThat(types).contains("GAME_SESSION_STARTED");
    }

    @Test
    void invariantViolatedEventOnHpNegative() {
        long runId = realRunId();
        var start = gameStartTool.execute(json(
                "{\"seed\":42,\"configVersion\":\"v001\",\"overrides\":{\""
                        + FakeGameEnvironment.INJ_HP_NEGATIVE_AT_TICK + "\":0}}"),
                ToolContext.of(runId, 1L));
        String sessionId = json(start.payloadJson()).get("sessionId").asText();
        // 推进 tick 触发 hp=-3 注入（注入在 advanceTick 内生效，observe 不推进 tick）
        gameActTool.execute(json("{\"sessionId\":\"" + sessionId
                + "\",\"actionType\":\"ATTACK\"}"), ToolContext.of(runId, 2L));
        gameObserveTool.execute(json("{\"sessionId\":\"" + sessionId + "\"}"),
                ToolContext.of(runId, 3L));
        var events = eventRepo.findByRunIdAfter(runId, 0, 1000);
        var types = events.stream().map(AgentEvent::type).toList();
        assertThat(types).contains("GAME_SESSION_STARTED", "INVARIANT_VIOLATED");
    }

    @Test
    void findingReportPublishesFindingCreated() {
        long runId = realRunId();
        var result = findingReportTool.execute(json(
                "{\"sessionId\":\"s1\",\"scenarioId\":\"sc1\",\"configVersion\":\"v001\","
                        + "\"severity\":\"HIGH\",\"category\":\"BALANCE_ANOMALY\","
                        + "\"title\":\"hp 越界\",\"expected\":\"HP>=0\",\"actual\":\"hp=999\","
                        + "\"reproSteps\":\"seed=42\"}"),
                ToolContext.of(runId, 1L));
        long findingId = json(result.payloadJson()).get("findingId").asLong();
        var events = eventRepo.findByRunIdAfter(runId, 0, 1000);
        var types = events.stream().map(AgentEvent::type).toList();
        assertThat(types).contains("FINDING_CREATED");
        var ev = events.stream().filter(e -> "FINDING_CREATED".equals(e.type())).findFirst().orElseThrow();
        assertThat(json(ev.payloadJson()).get("findingId").asLong()).isEqualTo(findingId);
    }

    @Test
    void testReportCreatePublishesReportCreated() {
        long runId = realRunId();
        findingReportTool.execute(json(
                "{\"sessionId\":\"s1\",\"scenarioId\":\"sc1\",\"configVersion\":\"v001\","
                        + "\"severity\":\"BLOCKER\",\"category\":\"CRASH\","
                        + "\"title\":\"崩溃\",\"expected\":\"正常启动\",\"actual\":\"进程崩溃\","
                        + "\"reproSteps\":\"seed=42\"}"),
                ToolContext.of(runId, 1L));
        Task task = taskRepo.insert(new Task(null, "rt", "g", "{}", 50, false, "ACTIVE", null));
        testReportCreateTool.execute(json(
                "{\"taskId\":" + task.id() + ",\"configVersion\":\"v001\","
                        + "\"summary\":\"存在 blocker\"}"),
                ToolContext.of(runId, 2L));
        var events = eventRepo.findByRunIdAfter(runId, 0, 1000);
        var types = events.stream().map(AgentEvent::type).toList();
        assertThat(types).contains("FINDING_CREATED", "TEST_REPORT_CREATED");
    }

    @Test
    void planServicePublishesCreatedAndActivated() {
        Task task = taskRepo.insert(new Task(null, "pt", "g", "{}", 50, false, "ACTIVE", null));
        var draft = testPlanService.createDraft(task.id(), "v001", null);
        testPlanService.activate(draft.id());
        // TestPlanService 事件 runId=0，从全局最近事件按 type 检索
        var recent = eventRepo.findRecent(1000);
        var types = recent.stream().map(AgentEvent::type).toList();
        assertThat(types).contains("TEST_PLAN_CREATED", "TEST_PLAN_ACTIVATED");
    }

    @Test
    void oldEventTypesStillValid() {
        // T4-3 仅追加，不改旧值；旧事件仍合法
        assertThat(com.aigamelab.event.AgentEventType.isValid("STEP_STARTED")).isTrue();
        assertThat(com.aigamelab.event.AgentEventType.isValid("RUN_COMPLETED")).isTrue();
        assertThat(com.aigamelab.event.AgentEventType.isValid("MADE_UP")).isFalse();
    }
}
