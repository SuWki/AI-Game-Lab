package com.aigamelab.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.domain.DesignDocument;
import com.aigamelab.domain.ResearchClaim;
import com.aigamelab.domain.ResearchSource;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.repository.DesignDocumentRepository;
import com.aigamelab.repository.ResearchClaimRepository;
import com.aigamelab.repository.ResearchSourceRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.AgentTypeResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * R2-1 验收：Designer 研究消费工具（get_research_context / get_research_claims）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>工具已注册到 ToolRegistry + 白名单可见（Designer additive 追加）；</li>
 *   <li>researchSourceTaskId 解析（run → task → constraintsJson）；</li>
 *   <li>未关联 → linked=false（向后兼容，不抛错）；</li>
 *   <li>关联且研究有产出 → 读取上下文（来源/断言/报告，可追溯 id）；</li>
 *   <li>get_research_claims 按 status / confidenceMin / limit 过滤且有界。</li>
 * </ol>
 *
 * <p>工具为只读：execute 不写任何表，run 状态不变。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("R2-1 Designer 研究消费工具验收")
class DesignerResearchToolsTest {

    @Autowired ToolRegistry registry;
    @Autowired AgentTypeResolver resolver;
    @Autowired TaskRepository taskRepo;
    @Autowired RunRepository runRepo;
    @Autowired ResearchSourceRepository sourceRepo;
    @Autowired ResearchClaimRepository claimRepo;
    @Autowired DesignDocumentRepository docRepo;
    @Autowired JdbcTemplate jdbc;

    static final ObjectMapper M = new ObjectMapper();

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-r21-", ".db");
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

    // ==================== 注册 / 白名单 ====================

    @Test
    @DisplayName("两个研究消费工具已注册且白名单可见")
    void toolsRegisteredAndInDesignerWhitelist() {
        assertThat(registry.contains("get_research_context")).isTrue();
        assertThat(registry.contains("get_research_claims")).isTrue();
        assertThat(registry.get("get_research_context").requiresApproval()).isFalse();
        assertThat(registry.get("get_research_claims").requiresApproval()).isFalse();

        Set<String> designerTools = resolver.toolWhitelist(com.aigamelab.runtime.AgentType.DESIGNER);
        assertThat(designerTools).contains("get_research_context", "get_research_claims");

        // 隔离：研究消费工具不泄漏到 Tester / Research 白名单
        assertThat(resolver.toolWhitelist(com.aigamelab.runtime.AgentType.TESTER))
                .doesNotContain("get_research_context", "get_research_claims");
        assertThat(resolver.toolWhitelist(com.aigamelab.runtime.AgentType.RESEARCH))
                .doesNotContain("get_research_context", "get_research_claims");
    }

    // ==================== 未关联 → linked=false ====================

    @Test
    @DisplayName("未关联 researchSourceTaskId → linked=false（向后兼容，不抛错）")
    void unlinkedTaskReturnsNoContext() throws Exception {
        Task task = taskRepo.insert(new Task(null, "普通设计", "设计",
                "{\"agent_type\":\"DESIGNER\"}", 50, false, "ACTIVE", null));
        Run run = runRepo.insert(new Run(null, task.id(), "RUNNING", null,
                "deepseek-chat", 0, 50, 100000, 0, null, null, null, null, null));

        ToolResult ctx = registry.get("get_research_context")
                .execute(M.readTree("{}"), ToolContext.of(run.id(), 1L));
        assertThat(ctx.status()).isEqualTo("COMPLETED");
        assertThat(json(ctx.payloadJson()).get("linked").asBoolean()).isFalse();
        assertThat(json(ctx.payloadJson()).get("hasData").asBoolean()).isFalse();

        ToolResult claims = registry.get("get_research_claims")
                .execute(M.readTree("{}"), ToolContext.of(run.id(), 1L));
        assertThat(claims.status()).isEqualTo("COMPLETED");
        assertThat(json(claims.payloadJson()).get("linked").asBoolean()).isFalse();
    }

    // ==================== 关联且有产出 → 上下文读取 ====================

    @Test
    @DisplayName("关联研究任务且有产出 → 读取来源/断言/报告（可追溯 id）")
    void linkedTaskReturnsResearchContext() throws Exception {
        // 研究任务 + 研究 Run + 产出
        Task researchTask = taskRepo.insert(new Task(null, "研究任务", "研究",
                "{\"agent_type\":\"RESEARCH\"}", 50, false, "ACTIVE", null));
        Run researchRun = runRepo.insert(new Run(null, researchTask.id(), "COMPLETED", null,
                "deepseek-chat", 3, 50, 100000, 0, null, null, null, null, null));

        sourceRepo.insert(new ResearchSource(null, researchRun.id(), researchTask.id(),
                "https://example.com/a", "Official", "来源A", "内容摘要A", 0.8, null, null));
        claimRepo.insert(new ResearchClaim(null, researchRun.id(), researchTask.id(),
                "AI 应用于游戏设计", 0.9, "[1]", "[]", "VALIDATED", null));
        claimRepo.insert(new ResearchClaim(null, researchRun.id(), researchTask.id(),
                "AI 尚不成熟", 0.3, "[]", "[1]", "DRAFT", null));
        docRepo.insert(new DesignDocument(null, researchRun.id(), researchTask.id(),
                "[RESEARCH REPORT] AI 游戏设计现状", "# 报告\n结论：AI 应用广泛", null));

        // Designer 任务关联研究任务
        Task designerTask = taskRepo.insert(new Task(null, "设计任务", "设计",
                "{\"agent_type\":\"DESIGNER\",\"researchSourceTaskId\":" + researchTask.id() + "}",
                50, false, "ACTIVE", null));
        Run designerRun = runRepo.insert(new Run(null, designerTask.id(), "RUNNING", null,
                "deepseek-chat", 0, 50, 100000, 0, null, null, null, null, null));

        ToolResult result = registry.get("get_research_context")
                .execute(M.readTree("{}"), ToolContext.of(designerRun.id(), 1L));
        assertThat(result.status()).isEqualTo("COMPLETED");
        JsonNode payload = json(result.payloadJson());

        assertThat(payload.get("linked").asBoolean()).isTrue();
        assertThat(payload.get("hasData").asBoolean()).isTrue();
        assertThat(payload.get("researchSourceTaskId").asLong()).isEqualTo(researchTask.id());
        assertThat(payload.get("researchRunIds").size()).isGreaterThanOrEqualTo(1);

        // 概览计数
        JsonNode summary = payload.get("summary");
        assertThat(summary.get("sourceCount").asInt()).isEqualTo(1);
        assertThat(summary.get("claimCount").asInt()).isEqualTo(2);
        assertThat(summary.get("validatedClaimCount").asInt()).isEqualTo(1);
        assertThat(summary.get("reportCount").asInt()).isEqualTo(1);

        // 来源（可追溯 id）
        JsonNode source = payload.get("sources").get(0);
        assertThat(source.get("id").asLong()).isPositive();
        assertThat(source.get("title").asText()).isEqualTo("来源A");

        // 断言（可追溯 id + 支持/反驳来源 id）
        JsonNode claims = payload.get("claims");
        assertThat(claims.size()).isEqualTo(2);
        assertThat(claims.get(0).get("id").asLong()).isPositive();
        assertThat(claims.get(0).get("status").asText()).isEqualTo("VALIDATED");
        assertThat(claims.get(0).get("supportingSourceIds").size()).isGreaterThanOrEqualTo(1);

        // 报告
        JsonNode report = payload.get("reports").get(0);
        assertThat(report.get("id").asLong()).isPositive();
        assertThat(report.get("title").asText()).startsWith("[RESEARCH REPORT]");
        assertThat(report.get("contentExcerpt").asText()).contains("结论");
    }

    // ==================== get_research_claims 过滤 ====================

    @Test
    @DisplayName("get_research_claims 按 status/confidenceMin/limit 过滤且有界")
    void claimsFilteringAndLimit() throws Exception {
        Task researchTask = taskRepo.insert(new Task(null, "研究任务", "研究",
                "{\"agent_type\":\"RESEARCH\"}", 50, false, "ACTIVE", null));
        Run researchRun = runRepo.insert(new Run(null, researchTask.id(), "COMPLETED", null,
                "deepseek-chat", 3, 50, 100000, 0, null, null, null, null, null));

        claimRepo.insert(new ResearchClaim(null, researchRun.id(), researchTask.id(),
                "断言A", 0.9, "[]", "[]", "VALIDATED", null));
        claimRepo.insert(new ResearchClaim(null, researchRun.id(), researchTask.id(),
                "断言B", 0.6, "[]", "[]", "DRAFT", null));
        claimRepo.insert(new ResearchClaim(null, researchRun.id(), researchTask.id(),
                "断言C", 0.4, "[]", "[]", "DISPUTED", null));

        Task designerTask = taskRepo.insert(new Task(null, "设计任务", "设计",
                "{\"agent_type\":\"DESIGNER\",\"researchSourceTaskId\":" + researchTask.id() + "}",
                50, false, "ACTIVE", null));
        Run designerRun = runRepo.insert(new Run(null, designerTask.id(), "RUNNING", null,
                "deepseek-chat", 0, 50, 100000, 0, null, null, null, null, null));

        // 按状态过滤
        ToolResult byStatus = registry.get("get_research_claims")
                .execute(M.readTree("{\"status\":\"VALIDATED\"}"), ToolContext.of(designerRun.id(), 1L));
        JsonNode statusPayload = json(byStatus.payloadJson());
        assertThat(statusPayload.get("linked").asBoolean()).isTrue();
        assertThat(statusPayload.get("totalMatched").asInt()).isEqualTo(1);
        assertThat(statusPayload.get("claims").get(0).get("status").asText()).isEqualTo("VALIDATED");

        // 按置信度下限过滤（仅 0.9 达标）
        ToolResult byConfidence = registry.get("get_research_claims")
                .execute(M.readTree("{\"confidenceMin\":0.8}"), ToolContext.of(designerRun.id(), 1L));
        JsonNode confPayload = json(byConfidence.payloadJson());
        assertThat(confPayload.get("totalMatched").asInt()).isEqualTo(1);

        // limit 有界：limit=1 → 只回 1 条（最高置信度 0.9 在前）
        ToolResult byLimit = registry.get("get_research_claims")
                .execute(M.readTree("{\"limit\":1}"), ToolContext.of(designerRun.id(), 1L));
        JsonNode limitPayload = json(byLimit.payloadJson());
        assertThat(limitPayload.get("totalMatched").asInt()).isEqualTo(1);
        assertThat(limitPayload.get("claims").get(0).get("confidence").asDouble()).isEqualTo(0.9);

        // limit 硬上限：999 → 被压到 100
        ToolResult clamped = registry.get("get_research_claims")
                .execute(M.readTree("{\"limit\":999}"), ToolContext.of(designerRun.id(), 1L));
        JsonNode clampedPayload = json(clamped.payloadJson());
        assertThat(clampedPayload.get("limit").asInt()).isEqualTo(100);
    }

    // ==================== 工具只读性 ====================

    @Test
    @DisplayName("工具只读：读取后研究表与 Run 状态不变")
    void toolsAreReadOnly() throws Exception {
        Task researchTask = taskRepo.insert(new Task(null, "研究任务", "研究",
                "{\"agent_type\":\"RESEARCH\"}", 50, false, "ACTIVE", null));
        Run researchRun = runRepo.insert(new Run(null, researchTask.id(), "COMPLETED", null,
                "deepseek-chat", 3, 50, 100000, 0, null, null, null, null, null));
        sourceRepo.insert(new ResearchSource(null, researchRun.id(), researchTask.id(),
                "https://example.com/a", "Official", "来源A", "内容", 0.8, null, null));
        claimRepo.insert(new ResearchClaim(null, researchRun.id(), researchTask.id(),
                "断言A", 0.9, "[]", "[]", "VALIDATED", null));

        Task designerTask = taskRepo.insert(new Task(null, "设计任务", "设计",
                "{\"agent_type\":\"DESIGNER\",\"researchSourceTaskId\":" + researchTask.id() + "}",
                50, false, "ACTIVE", null));
        Run designerRun = runRepo.insert(new Run(null, designerTask.id(), "RUNNING", null,
                "deepseek-chat", 0, 50, 100000, 0, null, null, null, null, null));

        registry.get("get_research_context").execute(M.readTree("{}"), ToolContext.of(designerRun.id(), 1L));
        registry.get("get_research_claims").execute(M.readTree("{}"), ToolContext.of(designerRun.id(), 1L));

        assertThat(sourceRepo.findByRunId(researchRun.id())).hasSize(1);
        assertThat(claimRepo.findByRunId(researchRun.id())).hasSize(1);
        assertThat(runRepo.findById(designerRun.id()).orElseThrow().status()).isEqualTo("RUNNING");
    }

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
