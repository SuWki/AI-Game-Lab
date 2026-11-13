package com.aigamelab.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.aigamelab.repository.ResearchClaimRepository;
import com.aigamelab.repository.ResearchSourceRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * TB + TC 硬化验收测试。
 *
 * <p><strong>TB（CompareSourcesTool）：</strong>
 * <ol>
 *   <li>来源存在性：引用不存在的 sourceId → 抛出 ToolException（非致命）</li>
 *   <li>所有权：引用不同 run 的来源 → 抛出 ToolException（跨研究污染防护）</li>
 *   <li>互斥：同一来源同时在 supporting + contradicting → 抛出 ToolException</li>
 *   <li>原子性：所有校验通过前不落库，claim 状态未改变</li>
 * </ol>
 *
 * <p><strong>TC（SaveSourceTool）：</strong>
 * <ol start="5">
 *   <li>sourceType 枚举校验：非法类型 → 抛出 ToolException</li>
 *   <li>reliability 上界：1.5 → 被 clamp 为 1.0</li>
 *   <li>reliability 下界：-0.1 → 被 clamp 为 0.0</li>
 *   <li>URL 去重幂等：同 run 同 URL → 返回 deduplicated=true + 同 id</li>
 *   <li>URL 归一化：尾部斜杠/fragment 在比较时被归一化</li>
 * </ol>
 *
 * <p>本文件不修改 Frozen Production Code（AgentLoop / ToolExecutor / ToolResult / ErrorCodes / schema.sql）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("TB + TC Research 工具硬化验收")
class ResearchToolHardeningTest {

    @Autowired SaveSourceTool saveSourceTool;
    @Autowired CompareSourcesTool compareSourcesTool;
    @Autowired ResearchSourceRepository sourceRepo;
    @Autowired ResearchClaimRepository claimRepo;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired JdbcTemplate jdbc;

    static final ObjectMapper M = new ObjectMapper();

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-tbtc-", ".db");
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

    private long createRunWithSource(String url) {
        long runId = createRun();
        saveSourceTool.execute(json(
                "{\"url\":\"" + url + "\",\"sourceType\":\"Official\","
                        + "\"title\":\"T\",\"contentSummary\":\"Some content here.\",\"reliability\":0.8}"),
                ToolContext.of(runId, 1L));
        return runId;
    }

    private JsonNode json(String s) {
        try { return M.readTree(s); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ==================== TB.1: 来源存在性 ====================

    @Test
    @DisplayName("TB.1 引用不存在的 sourceId → ToolException 非法参数")
    void missingSourceThrows() {
        long runId = createRun();
        Task task = taskRepo.findById(runRepo.findById(runId).orElseThrow().taskId()).orElseThrow();
        ResearchClaim claim = claimRepo.insert(new ResearchClaim(null, runId, task.id(),
                "test", 0.3, "[]", "[]", "DRAFT", null));

        assertThatThrownBy(() -> compareSourcesTool.execute(
                json("{\"claimId\":" + claim.id()
                        + ",\"supportingSourceIds\":[9999],\"contradictingSourceIds\":[]}"),
                ToolContext.of(runId, 1L)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("不存在");
    }

    // ==================== TB.2: 所有权（跨 run 污染防护） ====================

    @Test
    @DisplayName("TB.2 引用不同 run 的来源 → ToolException（跨研究污染防护）")
    void foreignSourceOwnershipViolationThrows() {
        // run A：有 claim
        long runA = createRun();
        Task taskA = taskRepo.findById(runRepo.findById(runA).orElseThrow().taskId()).orElseThrow();
        ResearchClaim claim = claimRepo.insert(new ResearchClaim(null, runA, taskA.id(),
                "test", 0.3, "[]", "[]", "DRAFT", null));

        // run B：有来源（不同 run）
        long runB = createRunWithSource("https://example.com/b");
        long foreignSourceId = sourceRepo.findByRunId(runB).get(0).id();

        assertThatThrownBy(() -> compareSourcesTool.execute(
                json("{\"claimId\":" + claim.id()
                        + ",\"supportingSourceIds\":[" + foreignSourceId
                        + "],\"contradictingSourceIds\":[]}"),
                ToolContext.of(runA, 1L)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("不属于当前研究");
    }

    // ==================== TB.3: 互斥 ====================

    @Test
    @DisplayName("TB.3 同一来源同时支持与反驳 → ToolException（互斥违规）")
    void mutualExclusionViolationThrows() {
        long runId = createRunWithSource("https://example.com/c");
        long sourceId = sourceRepo.findByRunId(runId).get(0).id();
        Task task = taskRepo.findById(runRepo.findById(runId).orElseThrow().taskId()).orElseThrow();
        ResearchClaim claim = claimRepo.insert(new ResearchClaim(null, runId, task.id(),
                "test", 0.3, "[]", "[]", "DRAFT", null));

        assertThatThrownBy(() -> compareSourcesTool.execute(
                json("{\"claimId\":" + claim.id()
                        + ",\"supportingSourceIds\":[" + sourceId
                        + "],\"contradictingSourceIds\":[" + sourceId + "]}"),
                ToolContext.of(runId, 1L)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("不能同时支持与反驳");
    }

    // ==================== TB.4: 原子性（校验失败不落库） ====================

    @Test
    @DisplayName("TB.4 校验失败 → claim 状态完全不变（原子性保证）")
    void failedCompareLeavesClaimUnchanged() {
        long runId = createRun();
        Task task = taskRepo.findById(runRepo.findById(runId).orElseThrow().taskId()).orElseThrow();
        ResearchClaim claim = claimRepo.insert(new ResearchClaim(null, runId, task.id(),
                "unchanged", 0.5, "[]", "[]", "DRAFT", null));

        try {
            compareSourcesTool.execute(
                    json("{\"claimId\":" + claim.id()
                            + ",\"supportingSourceIds\":[9999],\"contradictingSourceIds\":[]}"),
                    ToolContext.of(runId, 1L));
        } catch (ToolException ignored) { /* 预期失败 */ }

        ResearchClaim after = claimRepo.findById(claim.id()).orElseThrow();
        assertThat(after.confidence()).isEqualTo(0.5);
        assertThat(after.status()).isEqualTo("DRAFT");
        assertThat(after.supportingSourceIds()).isEqualTo("[]");
        assertThat(after.contradictingSourceIds()).isEqualTo("[]");
    }

    // ==================== TC.5: sourceType 枚举校验 ====================

    @Test
    @DisplayName("TC.5 非法 sourceType → ToolException（仅允许 5 种类型）")
    void invalidSourceTypeThrows() {
        long runId = createRun();
        assertThatThrownBy(() -> saveSourceTool.execute(
                json("{\"url\":\"https://x.com/a\",\"sourceType\":\"Newspaper\","
                        + "\"title\":\"t\",\"contentSummary\":\"c\"}"),
                ToolContext.of(runId, 1L)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("非法来源类型");
    }

    // ==================== TC.6 & 7: reliability clamp ====================

    @Test
    @DisplayName("TC.6 reliability > 1.0 被 clamp 为 1.0")
    void reliabilityClampedToMax() {
        long runId = createRun();
        saveSourceTool.execute(
                json("{\"url\":\"https://x.com/max\",\"sourceType\":\"Official\","
                        + "\"title\":\"t\",\"contentSummary\":\"content.\",\"reliability\":1.5}"),
                ToolContext.of(runId, 1L));
        ResearchSource src = sourceRepo.findByRunId(runId).get(0);
        assertThat(src.reliability()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("TC.7 reliability < 0.0 被 clamp 为 0.0")
    void reliabilityClampedToMin() {
        long runId = createRun();
        saveSourceTool.execute(
                json("{\"url\":\"https://x.com/min\",\"sourceType\":\"Official\","
                        + "\"title\":\"t\",\"contentSummary\":\"content.\",\"reliability\":-0.5}"),
                ToolContext.of(runId, 1L));
        ResearchSource src = sourceRepo.findByRunId(runId).get(0);
        assertThat(src.reliability()).isEqualTo(0.0);
    }

    // ==================== TC.8: URL 去重幂等 ====================

    @Test
    @DisplayName("TC.8 同 run 同 URL → deduplicated=true + 返回同一 sourceId")
    void urlDedupIdempotent() {
        long runId = createRun();
        var r1 = saveSourceTool.execute(
                json("{\"url\":\"https://example.com/dup\",\"sourceType\":\"Official\","
                        + "\"title\":\"T1\",\"contentSummary\":\"content.\"}"),
                ToolContext.of(runId, 1L));
        long id1 = json(r1.payloadJson()).get("sourceId").asLong();
        assertThat(json(r1.payloadJson()).has("deduplicated")).isFalse();

        var r2 = saveSourceTool.execute(
                json("{\"url\":\"https://example.com/dup\",\"sourceType\":\"Academic\","
                        + "\"title\":\"T2\",\"contentSummary\":\"other.\"}"),
                ToolContext.of(runId, 1L));
        long id2 = json(r2.payloadJson()).get("sourceId").asLong();
        assertThat(id2).isEqualTo(id1);
        assertThat(json(r2.payloadJson()).get("deduplicated").asBoolean()).isTrue();
        assertThat(sourceRepo.findByRunId(runId)).hasSize(1); // 未重复插入
    }

    // ==================== TC.9: URL 归一化 ====================

    @Test
    @DisplayName("TC.9 尾部斜杠 / fragment 归一化后判重")
    void urlCanonicalizationDeduplicates() {
        long runId = createRun();
        saveSourceTool.execute(
                json("{\"url\":\"https://example.com/page\",\"sourceType\":\"Official\","
                        + "\"title\":\"T\",\"contentSummary\":\"content.\"}"),
                ToolContext.of(runId, 1L));
        // 尾部斜杠
        var r2 = saveSourceTool.execute(
                json("{\"url\":\"https://example.com/page/\",\"sourceType\":\"Developer\","
                        + "\"title\":\"T2\",\"contentSummary\":\"content 2.\"}"),
                ToolContext.of(runId, 1L));
        assertThat(json(r2.payloadJson()).get("deduplicated").asBoolean()).isTrue();
        assertThat(sourceRepo.findByRunId(runId)).hasSize(1);

        // fragment 归一化
        var r3 = saveSourceTool.execute(
                json("{\"url\":\"https://example.com/page#section\",\"sourceType\":\"Community\","
                        + "\"title\":\"T3\",\"contentSummary\":\"content 3.\"}"),
                ToolContext.of(runId, 1L));
        assertThat(json(r3.payloadJson()).get("deduplicated").asBoolean()).isTrue();
        assertThat(sourceRepo.findByRunId(runId)).hasSize(1);
    }
}
