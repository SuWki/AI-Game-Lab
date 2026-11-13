package com.aigamelab.research;

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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.domain.ResearchClaim;
import com.aigamelab.domain.ResearchSource;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.domain.Task;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.repository.ChangeProposalRepository;
import com.aigamelab.repository.DesignDocumentRepository;
import com.aigamelab.repository.ResearchClaimRepository;
import com.aigamelab.repository.ResearchSourceRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.RunControlService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * R2-4 业务 E2E 验收：Research → Designer 产物消费的真实可追溯链。
 *
 * <p>证明「Research 的产物真正影响 Designer 的设计决策」（R2 核心判据），非仅
 * researchSourceTaskId 传递。完整链路：
 *
 * <pre>
 *   ①  Research Task + Run（FakeLlmGateway 剧本：save_source → extract_claims
 *       → compare_sources → generate_report）
 *       → research_source、research_claim、[RESEARCH REPORT] design_document 落库
 *
 *   ②  Designer Task + Run（constraintsJson.researchSourceTaskId = 研究 Task id）
 *       → get_research_context 工具被 AgentLoop 真实执行，读取研究产物
 *       → create_change_proposal：reason 引用研究 claim（id + 原文）
 *       → create_design_document：content 引用研究 claim
 *
 *   ③  断言同一条 claim id 依次出现在：
 *       research_claim → get_research_context 的 result_json → change_proposal.reason
 *       → design_document.content_md —— 一条完整可追溯链。
 * </pre>
 *
 * <p>Designer 白名单同时校验：get_research_context / get_research_claims 对 Designer
 * LLM 可见（additive）；Designer 系统提示仍为既有冻结文案。
 *
 * <p>FakeLlmGateway 经 @TestConfiguration 注入（镜像 ResearchAgentEndToEndTest 模式），
 * WebFetchService 本用例不触发（研究剧本直接 save_source，无需 stub）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("R2-4 Research→Designer 产物消费业务 E2E")
class ResearchToDesignerE2ETest {

    @Autowired RunControlService runControl;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired ResearchSourceRepository sourceRepo;
    @Autowired ResearchClaimRepository claimRepo;
    @Autowired DesignDocumentRepository docRepo;
    @Autowired ChangeProposalRepository proposalRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;

    static final ObjectMapper M = new ObjectMapper();

    /** 研究内容：两个句号 → extract_claims 拆出 2 条 claim */
    static final String SOURCE_CONTENT =
            "超过60%的游戏工作室已采用AI工具辅助设计。AI在数值平衡调试上比人工快3倍。";

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-r24-", ".db");
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

    @TestConfiguration
    static class FakeConfig {
        @Bean
        FakeLlmGateway fakeLlmGateway() {
            return new FakeLlmGateway();
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM research_claim");
        jdbc.update("DELETE FROM research_source");
        jdbc.update("DELETE FROM change_proposal");
        jdbc.update("DELETE FROM design_document");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM task");
        llm.reset();
    }

    private static ToolCall tc(String id, String name, String argsJson) {
        return new ToolCall(id, name, argsJson);
    }

    /** 轮询等待 Run 进入终态（镜像 ResearchAgentEndToEndTest 模式） */
    private void awaitTerminal(long runId) {
        for (int i = 0; i < 200; i++) {
            Run r = runRepo.findById(runId).orElseThrow();
            if (RunStatus.COMPLETED.name().equals(r.status())
                    || RunStatus.FAILED.name().equals(r.status())) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstResearchClaimText(List<ResearchClaim> claims) {
        assertThat(claims).isNotEmpty();
        return claims.get(0).claim();
    }

    @Test
    @DisplayName("Research→Designer 完整开工投产")
    void researchArtifactsDriveDesignerDecisionChain() {
        long researchTaskId = researchPhase();
        // 研究阶段请求索引基线：Designer 阶段的请求从该下标起
        int designerRequestStart = llm.callCount();
        designerPhase(researchTaskId, designerRequestStart);
    }

    // ==================== ① Research 阶段 ====================

    private long researchPhase() {
        Task task = taskRepo.insert(new Task(null, "研究 AI 游戏设计现状",
                "调查 AI 辅助游戏设计的工作室采用率",
                "{\"agent_type\":\"RESEARCH\"}", 50, false, "ACTIVE", null));

        llm.thenToolCalls(
                tc("c1", "save_source",
                        "{\"url\":\"https://research.example.com/ai-game\","
                        + "\"sourceType\":\"Official\","
                        + "\"title\":\"AI 游戏设计应用报告\","
                        + "\"contentSummary\":\"" + SOURCE_CONTENT + "\","
                        + "\"reliability\":0.8}"),
                tc("c2", "extract_claims", "{\"sourceId\":1}"),
                tc("c3", "compare_sources",
                        "{\"claimId\":1,\"supportingSourceIds\":[1],\"contradictingSourceIds\":[]}"),
                tc("c4", "generate_report",
                        "{\"question\":\"AI 辅助游戏设计应用现状\","
                        + "\"summary\":\"AI 工具已被主流工作室广泛采用。\"}"));
        llm.thenStop("研究完成");

        Run run = runControl.start(task.id());
        awaitTerminal(run.id());
        Run finished = runRepo.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(RunStatus.COMPLETED.name());

        // 研究产物落库
        List<ResearchSource> sources = sourceRepo.findByTaskId(task.id());
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).sourceType()).isEqualTo("Official");

        List<ResearchClaim> claims = claimRepo.findByTaskId(task.id());
        assertThat(claims).hasSize(2); // 两个句号 → 2 条 claim
        ResearchClaim c0 = claims.get(0);
        assertThat(c0.confidence()).isGreaterThan(0.3); // compare_sources 已提升
        assertThat(c0.status()).isEqualTo("DRAFT");

        // [RESEARCH REPORT] 归档 design_document
        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM design_document WHERE title LIKE '%[RESEARCH REPORT]%' AND run_id=?",
                Integer.class, run.id());
        assertThat(reports).isEqualTo(1);

        return task.id();
    }

    // ==================== ② Designer 阶段（消费研究产物） ====================

    private void designerPhase(long researchTaskId, int designerRequestStart) {
        List<ResearchClaim> claims = claimRepo.findByTaskId(researchTaskId);
        long claimId = claims.get(0).id();
        String claimText = firstResearchClaimText(claims);

        // Designer Task 显式关联研究任务（不复制产物，仅存引用）
        Task designerTask = taskRepo.insert(new Task(null, "设计数值平衡方案",
                "基于研究结论制定数值平衡设计决策",
                "{\"agent_type\":\"DESIGNER\",\"researchSourceTaskId\":" + researchTaskId + "}",
                50, false, "ACTIVE", null));

        String reason = "研究 claim #" + claimId + " 「" + claimText
                + "」支持 AI 辅助数值平衡，故设计决策采纳 AI 辅助工具。";
        String docContent = "# 设计决策\n\n依据研究 claim #" + claimId + " "
                + "（" + claimText + "），数值平衡流程采纳 AI 辅助。\n\n## 理由\n\n"
                + "研究证据指向工作室普遍采纳 AI 辅助设计。";

        // 用 ObjectMapper 组装工具参数 JSON（避免手拼转义错漏）
        ObjectNode proposal = M.createObjectNode();
        proposal.put("title", "采纳 AI 辅助数值平衡");
        proposal.put("reason", reason);
        proposal.put("baseVersion", "v001");
        ArrayNode candidates = proposal.putArray("candidates");
        candidates.addObject().put("name", "方案A");
        candidates.addObject().put("name", "方案B");
        String proposalArgs = proposal.toString();

        ObjectNode doc = M.createObjectNode();
        doc.put("title", "AI 辅助数值平衡设计文档");
        doc.put("content", docContent);
        doc.put("configVersion", "v001");
        String docArgs = doc.toString();

        llm.thenToolCalls(tc("d1", "get_research_context", "{}"));
        llm.thenToolCalls(
                tc("d2", "create_change_proposal", proposalArgs),
                tc("d3", "create_design_document", docArgs));
        llm.thenStop("设计完成");

        Run run = runControl.start(designerTask.id());
        awaitTerminal(run.id());
        Run finished = runRepo.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(RunStatus.COMPLETED.name());

        // ---- ③ 完整可追溯链断言 ----
        assertTraceableChain(run.id(), researchTaskId, claimId, claimText, designerRequestStart);
    }

    private void assertTraceableChain(long designerRunId, long researchTaskId,
            long claimId, String claimText, int designerRequestStart) {
        // 3.1 get_research_context 的 result_json 包含研究产物（真实消费，非空读）
        String ctxResult = jdbc.queryForObject(
                "SELECT result_json FROM tool_call WHERE run_id=? AND tool_name='get_research_context'",
                String.class, designerRunId);
        JsonNode ctx = json(ctxResult);
        assertThat(ctx.get("linked").asBoolean()).isTrue();
        assertThat(ctx.get("researchSourceTaskId").asLong()).isEqualTo(researchTaskId);
        // 工具返回的研究产物 id 与 research_claim 实际一致
        JsonNode ctxClaims = ctx.get("claims");
        assertThat(ctxClaims).isNotNull();
        boolean claimIdSeen = false;
        for (JsonNode c : ctxClaims) {
            if (c.get("id").asLong() == claimId) {
                claimIdSeen = true;
                assertThat(c.get("claim").asText()).isEqualTo(claimText);
            }
        }
        assertThat(claimIdSeen).as("get_research_context 必须返回同一条研究 claim").isTrue();

        // 3.2 change_proposal.reason 引用同一条 claim（同一条 claim id + 原文）
        String reason = jdbc.queryForObject(
                "SELECT reason FROM change_proposal WHERE run_id=?",
                String.class, designerRunId);
        assertThat(reason).contains("claim #" + claimId);
        assertThat(reason).contains(claimText);
        String status = jdbc.queryForObject(
                "SELECT status FROM change_proposal WHERE run_id=?",
                String.class, designerRunId);
        assertThat(status).isEqualTo("PENDING");

        // 3.3 design_document.content_md 引用同一条 claim
        String docContent = jdbc.queryForObject(
                "SELECT content_md FROM design_document WHERE run_id=? AND title='AI 辅助数值平衡设计文档'",
                String.class, designerRunId);
        assertThat(docContent).contains("claim #" + claimId);
        assertThat(docContent).contains(claimText);

        // 3.4 Designer 白名单含研究消费工具（additive）且系统提示冻结
        var req0 = llm.recordedRequests().get(designerRequestStart);
        var specs = req0.tools();
        assertThat(specs.stream().map(s -> s.name()))
                .contains("get_research_context", "get_research_claims");
        String prompt = req0.messages().get(0).content();
        assertThat(prompt).isEqualTo("You are AGL game designer agent.");
    }
}