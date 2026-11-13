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

import com.aigamelab.domain.ResearchClaim;
import com.aigamelab.domain.ResearchSource;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.repository.DesignDocumentRepository;
import com.aigamelab.repository.ResearchClaimRepository;
import com.aigamelab.repository.ResearchSourceRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * TF + TG 验收测试。
 *
 * <p><strong>TF（GenerateReportTool 7 节契约）：</strong>
 * <ol>
 *   <li>输出包含 research_p001.md 定义的 7 节全部小节标题</li>
 *   <li>证据带可追溯 id：claim 正文带 [claim#N]</li>
 *   <li>来源带可追溯 id：[source#M]</li>
 *   <li>DRAFT 与 VALIDATED 分离：DRAFT 只出现在「不确定性」节，不混入「支持证据」</li>
 *   <li>结论节含 Confidence Score</li>
 *   <li>空状态（无 claim / 无 source）也能生成合法报告</li>
 * </ol>
 *
 * <p><strong>TG（去重幂等）：</strong>
 * <ol start="7">
 *   <li>generate_report 同 run 同 question 重复调用 → 返回既有 report（deduplicated=true），不重复插入</li>
 *   <li>extract_claims 对同来源重复调用 → 重复文本跳过（skippedCount &gt; 0），不产生重复行</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("TF + TG 报告契约与去重幂等验收")
class ReportContractAndDedupTest {

    @Autowired SaveSourceTool saveSourceTool;
    @Autowired ExtractClaimsTool extractClaimsTool;
    @Autowired GenerateReportTool generateReportTool;
    @Autowired ResearchSourceRepository sourceRepo;
    @Autowired ResearchClaimRepository claimRepo;
    @Autowired DesignDocumentRepository docRepo;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired JdbcTemplate jdbc;

    static final ObjectMapper M = new ObjectMapper();

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-tftg-", ".db");
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

    private long createRun() {
        Task task = taskRepo.insert(new Task(null, "研究", "研究",
                "{\"agent_type\":\"RESEARCH\"}", 50, false, "ACTIVE", null));
        Run run = runRepo.insert(new Run(null, task.id(), "RUNNING", null,
                "deepseek-chat", 0, 50, 100000, 0, null, null, null, null, null));
        return run.id();
    }

    private JsonNode json(String s) {
        try { return M.readTree(s); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ==================== TF.1-6: 报告契约 ====================

    @Test
    @DisplayName("TF 报告含 7 节标题 + Confidence Score + 可追溯 id")
    void reportFollowsSevenSectionContract() {
        long runId = createRun();
        Task task = taskRepo.findById(runRepo.findById(runId).orElseThrow().taskId()).orElseThrow();

        // 1 个 VALIDATED claim + 1 个 DRAFT claim + 1 来源
        var saved = saveSourceTool.execute(json(
                "{\"url\":\"https://example.com/rpt\",\"sourceType\":\"Official\","
                        + "\"title\":\"报告来源\",\"contentSummary\":\"来源内容。\",\"reliability\":0.9}"),
                ToolContext.of(runId, 1L));
        long sourceId = json(saved.payloadJson()).get("sourceId").asLong();

        claimRepo.insert(new ResearchClaim(null, runId, task.id(),
                "AI 应用于游戏设计", 0.9, "[" + sourceId + "]", "[]", "VALIDATED", null));
        claimRepo.insert(new ResearchClaim(null, runId, task.id(),
                "AI 将取代人类设计师", 0.3, "[" + sourceId + "]", "[]", "DRAFT", null));

        var result = generateReportTool.execute(json(
                "{\"question\":\"AI 如何影响游戏设计\",\"summary\":\"AI 广泛应用。\"}"),
                ToolContext.of(runId, 3L));
        long docId = json(result.payloadJson()).get("documentId").asLong();
        String md = docRepo.findById(docId).orElseThrow().contentMd();

        // 7 节标题全部出现
        assertThat(md).contains("## 1. 研究问题");
        assertThat(md).contains("## 2. 结论（含 Confidence Score）");
        assertThat(md).contains("## 3. 支持证据（来源 + 可靠性）");
        assertThat(md).contains("## 4. 反对证据（来源 + 可靠性）");
        assertThat(md).contains("## 5. 最合理模型/假设");
        assertThat(md).contains("## 6. 不确定性说明");
        assertThat(md).contains("## 7. 后续研究建议");

        // Confidence Score 节存在且非负
        assertThat(md).contains("综合 Confidence Score");

        // 可追溯 id：claim + source
        assertThat(md).contains("[claim#");
        assertThat(md).contains("[source#" + sourceId + "]");

        // DRAFT/VALIDATED 分离：VALIDATED 在支持证据，DRAFT 只在不确定性
        String supportSection = md.substring(md.indexOf("## 3."), md.indexOf("## 4."));
        assertThat(supportSection).contains("AI 应用于游戏设计");
        assertThat(supportSection).doesNotContain("AI 将取代人类设计师");
        String uncertaintySection = md.substring(md.indexOf("## 6."), md.indexOf("## 7."));
        assertThat(uncertaintySection).contains("AI 将取代人类设计师");
    }

    @Test
    @DisplayName("TF 空状态（无 claim/无 source）也能生成合法报告")
    void reportRendersEmptyState() {
        long runId = createRun();
        var result = generateReportTool.execute(json(
                "{\"question\":\"空研究\",\"summary\":\"无结论。\"}"),
                ToolContext.of(runId, 1L));
        long docId = json(result.payloadJson()).get("documentId").asLong();
        String md = docRepo.findById(docId).orElseThrow().contentMd();

        assertThat(md).contains("## 1. 研究问题");
        assertThat(md).contains("## 7. 后续研究建议");
        assertThat(md).contains("无已验证的断言");
        assertThat(md).contains("无来源");
    }

    // ==================== TG.7: generate_report 幂等 ====================

    @Test
    @DisplayName("TG generate_report 同 run 同 question → 复用既有报告，不重复插入")
    void generateReportIsIdempotent() {
        long runId = createRun();
        var r1 = generateReportTool.execute(json(
                "{\"question\":\"重复问题\",\"summary\":\"结论。\"}"),
                ToolContext.of(runId, 1L));
        long docId1 = json(r1.payloadJson()).get("documentId").asLong();

        var r2 = generateReportTool.execute(json(
                "{\"question\":\"重复问题\",\"summary\":\"结论。\"}"),
                ToolContext.of(runId, 1L));
        long docId2 = json(r2.payloadJson()).get("documentId").asLong();

        assertThat(docId2).isEqualTo(docId1);
        assertThat(json(r2.payloadJson()).get("deduplicated").asBoolean()).isTrue();
        assertThat(docRepo.count()).isEqualTo(1); // 未重复插入
    }

    // ==================== TG.8: extract_claims 幂等 ====================

    @Test
    @DisplayName("TG extract_claims 同来源重复调用 → 重复文本跳过，不产生重复行")
    void extractClaimsIsIdempotent() {
        long runId = createRun();
        var saved = saveSourceTool.execute(json(
                "{\"url\":\"https://example.com/idem\",\"sourceType\":\"Official\","
                        + "\"title\":\"幂等来源\","
                        + "\"contentSummary\":\"第一句话内容足够长。第二句话内容同样足够长。\","
                        + "\"reliability\":0.8}"),
                ToolContext.of(runId, 1L));
        long sourceId = json(saved.payloadJson()).get("sourceId").asLong();

        var r1 = extractClaimsTool.execute(json("{\"sourceId\":" + sourceId + "}"),
                ToolContext.of(runId, 2L));
        int first = json(r1.payloadJson()).get("extractedCount").asInt();
        assertThat(first).isGreaterThanOrEqualTo(1);

        var r2 = extractClaimsTool.execute(json("{\"sourceId\":" + sourceId + "}"),
                ToolContext.of(runId, 3L));
        int skipped = json(r2.payloadJson()).get("skippedCount").asInt();
        assertThat(skipped).isEqualTo(first); // 全部重复，全跳过
        assertThat(json(r2.payloadJson()).get("extractedCount").asInt()).isEqualTo(0);

        // 不产生重复行
        assertThat(claimRepo.findByRunId(runId)).hasSize(first);
    }
}
