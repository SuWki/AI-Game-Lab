package com.aigamelab.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

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
import com.aigamelab.domain.ResearchClaim;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.repository.AgentEventRepository;
import com.aigamelab.repository.ResearchClaimRepository;
import com.aigamelab.repository.ResearchSourceRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * R2-2 验收：R0-8 追加的 4 个 Research 事件经真实 Runtime 发布并落库 agent_event
 * （Tool → AgentEventPublisher → AgentEventListener → agent_event，SSE 由监听器同步推送）。
 * 此前工具只写表不发事件（agent_event 无 RESEARCH_* 行），此 Task 修复为真实发布。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("R2-2 Research 事件发布与落库")
class ResearchEventPublishTest {

    @Autowired SaveSourceTool saveSourceTool;
    @Autowired ExtractClaimsTool extractClaimsTool;
    @Autowired CompareSourcesTool compareSourcesTool;
    @Autowired GenerateReportTool generateReportTool;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired ResearchSourceRepository sourceRepo;
    @Autowired ResearchClaimRepository claimRepo;
    @Autowired AgentEventRepository eventRepo;
    @Autowired JdbcTemplate jdbc;

    static final ObjectMapper M = new ObjectMapper();

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-r22-", ".db");
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
        jdbc.update("DELETE FROM research_claim");
        jdbc.update("DELETE FROM research_source");
        jdbc.update("DELETE FROM design_document");
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
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
    void saveSourcePublishesSourceFetched() {
        long runId = realRunId();
        var result = saveSourceTool.execute(json(
                "{\"url\":\"https://example.com/a\",\"sourceType\":\"Official\","
                        + "\"title\":\"来源A\",\"contentSummary\":\"内容摘要\",\"reliability\":0.8}"),
                ToolContext.of(runId, 1L));
        long sourceId = json(result.payloadJson()).get("sourceId").asLong();

        var events = eventRepo.findByRunIdAfter(runId, 0, 1000);
        var types = events.stream().map(AgentEvent::type).toList();
        assertThat(types).contains("SOURCE_FETCHED");
        var ev = events.stream().filter(e -> "SOURCE_FETCHED".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(json(ev.payloadJson()).get("sourceId").asLong()).isEqualTo(sourceId);
        assertThat(json(ev.payloadJson()).get("taskId").asLong()).isPositive();
    }

    @Test
    void extractClaimsPublishesClaimExtracted() {
        long runId = realRunId();
        var saved = saveSourceTool.execute(json(
                "{\"url\":\"https://example.com/b\",\"sourceType\":\"Official\","
                        + "\"title\":\"来源B\","
                        + "\"contentSummary\":\"AI 应用于游戏设计正逐步普及。强化学习可自动平衡数值。\","
                        + "\"reliability\":0.8}"),
                ToolContext.of(runId, 1L));
        long sourceId = json(saved.payloadJson()).get("sourceId").asLong();

        var result = extractClaimsTool.execute(json("{\"sourceId\":" + sourceId + "}"),
                ToolContext.of(runId, 2L));
        int extracted = json(result.payloadJson()).get("extractedCount").asInt();
        assertThat(extracted).isGreaterThanOrEqualTo(1);

        var events = eventRepo.findByRunIdAfter(runId, 0, 1000);
        var types = events.stream().map(AgentEvent::type).toList();
        assertThat(types).contains("SOURCE_FETCHED", "CLAIM_EXTRACTED");
        var ev = events.stream().filter(e -> "CLAIM_EXTRACTED".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(json(ev.payloadJson()).get("sourceId").asLong()).isEqualTo(sourceId);
        assertThat(json(ev.payloadJson()).get("extractedCount").asInt()).isEqualTo(extracted);
    }

    @Test
    void compareSourcesPublishesClaimValidated() {
        long runId = realRunId();
        Task task = taskRepo.findById(
                runRepo.findById(runId).orElseThrow().taskId()).orElseThrow();
        // 先落两个真实来源（TB：compare_sources 要求来源必须已存在且属于同一 run/task）
        var s1 = saveSourceTool.execute(json(
                "{\"url\":\"https://example.com/1\",\"sourceType\":\"Official\","
                        + "\"title\":\"来源1\",\"contentSummary\":\"内容摘要1\",\"reliability\":0.8}"),
                ToolContext.of(runId, 1L));
        long srcId1 = json(s1.payloadJson()).get("sourceId").asLong();
        var s2 = saveSourceTool.execute(json(
                "{\"url\":\"https://example.com/2\",\"sourceType\":\"Academic\","
                        + "\"title\":\"来源2\",\"contentSummary\":\"内容摘要2\",\"reliability\":0.7}"),
                ToolContext.of(runId, 1L));
        long srcId2 = json(s2.payloadJson()).get("sourceId").asLong();

        // 先落一个 claim（canonical 数字数组格式 [N]）
        ResearchClaim claim = claimRepo.insert(new ResearchClaim(null, runId, task.id(),
                "AI 应用于游戏设计", 0.3, "[" + srcId1 + "]", "[]", "DRAFT", null));

        // 两个独立来源支持 → confidence 0.3 + 0.2*2 + 0.1 = 0.8 → VALIDATED
        var result = compareSourcesTool.execute(json(
                "{\"claimId\":" + claim.id()
                        + ",\"supportingSourceIds\":[" + srcId1 + "," + srcId2
                        + "],\"contradictingSourceIds\":[]}"),
                ToolContext.of(runId, 1L));
        String status = json(result.payloadJson()).get("status").asText();

        var events = eventRepo.findByRunIdAfter(runId, 0, 1000);
        var types = events.stream().map(AgentEvent::type).toList();
        assertThat(types).contains("CLAIM_VALIDATED");
        var ev = events.stream().filter(e -> "CLAIM_VALIDATED".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(json(ev.payloadJson()).get("claimId").asLong()).isEqualTo(claim.id());
        assertThat(json(ev.payloadJson()).get("status").asText()).isEqualTo(status);
    }

    @Test
    void generateReportPublishesReportCreated() {
        long runId = realRunId();
        // 预置研究产出（来源 + 断言），保证报告里含证据
        var saved = saveSourceTool.execute(json(
                "{\"url\":\"https://example.com/c\",\"sourceType\":\"Official\","
                        + "\"title\":\"来源C\",\"contentSummary\":\"AI 影响游戏设计的结论。\","
                        + "\"reliability\":0.8}"),
                ToolContext.of(runId, 1L));
        long sourceId = json(saved.payloadJson()).get("sourceId").asLong();
        extractClaimsTool.execute(json("{\"sourceId\":" + sourceId + "}"),
                ToolContext.of(runId, 2L));

        var result = generateReportTool.execute(json(
                "{\"question\":\"AI 如何影响游戏设计\",\"summary\":\"研究完成\"}"),
                ToolContext.of(runId, 3L));
        long documentId = json(result.payloadJson()).get("documentId").asLong();

        var events = eventRepo.findByRunIdAfter(runId, 0, 1000);
        var types = events.stream().map(AgentEvent::type).toList();
        assertThat(types).contains("SOURCE_FETCHED", "CLAIM_EXTRACTED", "RESEARCH_REPORT_CREATED");
        var ev = events.stream().filter(e -> "RESEARCH_REPORT_CREATED".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(json(ev.payloadJson()).get("documentId").asLong()).isEqualTo(documentId);
        assertThat(json(ev.payloadJson()).get("claimsCount").asInt())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void researchEventTypesStillValid() {
        // R2-2 仅接线不改枚举：4 个 Research 类型合法，旧类型不受影响
        assertThat(com.aigamelab.event.AgentEventType.isValid("SOURCE_FETCHED")).isTrue();
        assertThat(com.aigamelab.event.AgentEventType.isValid("CLAIM_EXTRACTED")).isTrue();
        assertThat(com.aigamelab.event.AgentEventType.isValid("CLAIM_VALIDATED")).isTrue();
        assertThat(com.aigamelab.event.AgentEventType.isValid("RESEARCH_REPORT_CREATED")).isTrue();
        assertThat(com.aigamelab.event.AgentEventType.isValid("STEP_STARTED")).isTrue();
        assertThat(com.aigamelab.event.AgentEventType.isValid("FINDING_CREATED")).isTrue();
        assertThat(com.aigamelab.event.AgentEventType.isValid("MADE_UP")).isFalse();
        // 26 = 16 原 + 6 TEST_* + 4 RESEARCH_*
        assertThat(com.aigamelab.event.AgentEventType.values()).hasSize(27);
    }
}
