package com.aigamelab.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.repository.ResearchClaimRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * TA 验收：ExtractClaimsTool 畸形 JSON 修复 + 幂等迁移脚本。
 *
 * <p>覆盖 TA.2 项 1–5（项 6 由前端 ResearchPage.test.tsx 覆盖）：
 * <ol>
 *   <li>单来源 canonical [id] — ExtractClaimsTool 写入合法 JSON 数组</li>
 *   <li>多来源 [id,id] — 可被 JSON 解析</li>
 *   <li>所有合法格式（[] / [1] / [1,2]）均 JSON 可解析</li>
 *   <li>legacy 畸形 ["source:N] → 幂等迁移为 [N]</li>
 *   <li>已有合法数据不受迁移影响</li>
 * </ol>
 *
 * <p>迁移脚本路径：scripts/fix_claim_source_ids.sql（相对模块目录 ../scripts）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("TA: extract_claims 畸形 JSON 修复 + 幂等迁移验收")
class ExtractClaimsSourceIdsTest {

    @Autowired SaveSourceTool saveSourceTool;
    @Autowired ExtractClaimsTool extractClaimsTool;
    @Autowired ResearchClaimRepository claimRepo;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired JdbcTemplate jdbc;

    static final ObjectMapper M = new ObjectMapper();

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-ta-", ".db");
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
        Task task = taskRepo.insert(new Task(null, "研究任务", "研究",
                "{\"agent_type\":\"RESEARCH\"}", 50, false, "ACTIVE", null));
        Run run = runRepo.insert(new Run(null, task.id(), "RUNNING", null,
                "deepseek-chat", 0, 50, 100000, 0, null, null, null, null, null));
        return run.id();
    }

    // ==================== TA.2.1: 单来源 canonical [id] ====================

    @Test
    @DisplayName("TA.2.1 extract_claims 存储 canonical [id] 格式（单来源）")
    void singleSourceClaimStoredAsCanonicalJsonArray() throws Exception {
        long runId = createRun();

        var saved = saveSourceTool.execute(M.readTree(
                "{\"url\":\"https://example.com/test\",\"sourceType\":\"Official\","
                        + "\"title\":\"Test Source\","
                        + "\"contentSummary\":\"AI applied to game design has proven effective. "
                        + "Modern approaches use reinforcement learning techniques extensively.\","
                        + "\"reliability\":0.8}"),
                ToolContext.of(runId, 1L));
        long sourceId = M.readTree(saved.payloadJson()).get("sourceId").asLong();

        var extractResult = extractClaimsTool.execute(
                M.readTree("{\"sourceId\":" + sourceId + "}"),
                ToolContext.of(runId, 2L));
        int extracted = M.readTree(extractResult.payloadJson()).get("extractedCount").asInt();
        assertThat(extracted).isGreaterThanOrEqualTo(1);

        var claims = claimRepo.findByRunId(runId);
        for (ResearchClaim claim : claims) {
            // 存储值是 canonical "[id]" 格式（不是 legacy "[\"source:N]"）
            String supporting = claim.supportingSourceIds();
            assertThat(supporting).startsWith("[");
            assertThat(supporting).endsWith("]");
            assertThat(supporting).doesNotContain("source:");

            // JSON 解析验证：可被 ObjectMapper（等价 JSON.parse）解析
            JsonNode arr = M.readTree(supporting);
            assertThat(arr.isArray()).isTrue();
            assertThat(arr.size()).isGreaterThanOrEqualTo(1);
            assertThat(arr.get(0).asLong()).isEqualTo(sourceId);
        }
    }

    // ==================== TA.2.2: 多来源 [id,id] ====================

    @Test
    @DisplayName("TA.2.2 多来源 JSON 数组 [1,2] JSON 可解析")
    void multiSourceIdsParseAsJsonArray() throws Exception {
        long runId = createRun();
        Task task = taskRepo.findById(runRepo.findById(runId)
                .orElseThrow().taskId()).orElseThrow();

        claimRepo.insert(new ResearchClaim(null, runId, task.id(),
                "Multi-source claim", 0.5, "[1,2]", "[3]", "DRAFT", null));

        var claims = claimRepo.findByRunId(runId);
        assertThat(claims).hasSize(1);

        JsonNode supporting = M.readTree(claims.get(0).supportingSourceIds());
        assertThat(supporting.isArray()).isTrue();
        assertThat(supporting.size()).isEqualTo(2);
        assertThat(supporting.get(0).asLong()).isEqualTo(1);
        assertThat(supporting.get(1).asLong()).isEqualTo(2);

        JsonNode contradicting = M.readTree(claims.get(0).contradictingSourceIds());
        assertThat(contradicting.isArray()).isTrue();
        assertThat(contradicting.size()).isEqualTo(1);
        assertThat(contradicting.get(0).asLong()).isEqualTo(3);
    }

    // ==================== TA.2.3: 所有合法格式 JSON 可解析 ====================

    @Test
    @DisplayName("TA.2.3 空数组 [] 与单来源 [1] 均 JSON 可解析")
    void emptyAndSingleSourceParseable() throws Exception {
        long runId = createRun();
        Task task = taskRepo.findById(runRepo.findById(runId)
                .orElseThrow().taskId()).orElseThrow();

        claimRepo.insert(new ResearchClaim(null, runId, task.id(),
                "Empty sources", 0.1, "[]", "[]", "DRAFT", null));
        claimRepo.insert(new ResearchClaim(null, runId, task.id(),
                "One source", 0.4, "[7]", "[8,9]", "DRAFT", null));

        var claims = claimRepo.findByRunId(runId);
        assertThat(claims).hasSize(2);

        for (ResearchClaim claim : claims) {
            JsonNode sup = M.readTree(claim.supportingSourceIds());
            JsonNode contra = M.readTree(claim.contradictingSourceIds());
            assertThat(sup.isArray()).isTrue();
            assertThat(contra.isArray()).isTrue();
        }
    }

    // ==================== TA.2.4: legacy 畸形值幂等迁移 ====================

    @Test
    @DisplayName("TA.2.4 legacy [\"source:N] → 幂等迁移为 [N]")
    void legacyMalformedValueMigratedToCanonical() throws Exception {
        long runId = createRun();
        Task task = taskRepo.findById(runRepo.findById(runId)
                .orElseThrow().taskId()).orElseThrow();

        // legacy 格式：["source:1]
        claimRepo.insert(new ResearchClaim(null, runId, task.id(),
                "Legacy claim", 0.5, "[\"source:1]", "[\"source:2]", "DRAFT", null));

        // 验证 legacy 值确实写入了 DB
        var before = claimRepo.findByRunId(runId);
        assertThat(before).hasSize(1);
        assertThat(before.get(0).supportingSourceIds()).isEqualTo("[\"source:1]");

        // 执行迁移脚本
        runMigrationSql();

        var after = claimRepo.findByRunId(runId);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).supportingSourceIds()).isEqualTo("[1]");
        assertThat(after.get(0).contradictingSourceIds()).isEqualTo("[2]");

        // JSON 解析验证
        JsonNode sup = M.readTree(after.get(0).supportingSourceIds());
        assertThat(sup.isArray()).isTrue();
        assertThat(sup.get(0).asLong()).isEqualTo(1);

        // 幂等：再次执行不变
        runMigrationSql();
        var final_ = claimRepo.findByRunId(runId);
        assertThat(final_.get(0).supportingSourceIds()).isEqualTo("[1]");
        assertThat(final_.get(0).contradictingSourceIds()).isEqualTo("[2]");
    }

    // ==================== TA.2.5: 合法数据不受迁移影响 ====================

    @Test
    @DisplayName("TA.2.5 合法数据 [1] [1,2] [] 不受迁移影响")
    void validDataUnbrokenAfterMigration() throws Exception {
        long runId = createRun();
        Task task = taskRepo.findById(runRepo.findById(runId)
                .orElseThrow().taskId()).orElseThrow();

        claimRepo.insert(new ResearchClaim(null, runId, task.id(),
                "Valid single", 0.6, "[1]", "[2]", "VALIDATED", null));
        claimRepo.insert(new ResearchClaim(null, runId, task.id(),
                "Valid multi", 0.4, "[1,2]", "[3,4]", "DRAFT", null));
        claimRepo.insert(new ResearchClaim(null, runId, task.id(),
                "Valid empty", 0.2, "[]", "[]", "DRAFT", null));

        runMigrationSql();

        var after = claimRepo.findByRunId(runId);
        assertThat(after).hasSize(3);

        var single = after.stream().filter(c -> "Valid single".equals(c.claim())).findFirst().orElseThrow();
        assertThat(single.supportingSourceIds()).isEqualTo("[1]");
        assertThat(single.contradictingSourceIds()).isEqualTo("[2]");

        var multi = after.stream().filter(c -> "Valid multi".equals(c.claim())).findFirst().orElseThrow();
        assertThat(multi.supportingSourceIds()).isEqualTo("[1,2]");
        assertThat(multi.contradictingSourceIds()).isEqualTo("[3,4]");

        var empty = after.stream().filter(c -> "Valid empty".equals(c.claim())).findFirst().orElseThrow();
        assertThat(empty.supportingSourceIds()).isEqualTo("[]");
        assertThat(empty.contradictingSourceIds()).isEqualTo("[]");
    }

    /**
     * 执行 scripts/fix_claim_source_ids.sql 幂等迁移脚本。
     * 从文件系统读取 SQL，忽略注释和空行，按分号拆分后逐条执行。
     */
    private void runMigrationSql() throws Exception {
        String[] candidates = {
                "../scripts/fix_claim_source_ids.sql", // Maven 标准：cwd = backend/
                "scripts/fix_claim_source_ids.sql"       // root or IDE
        };
        String sql = null;
        for (String candidate : candidates) {
            Path p = Path.of(candidate);
            if (Files.exists(p)) {
                sql = Files.readString(p, StandardCharsets.UTF_8);
                break;
            }
        }
        assertThat(sql).as(
                "迁移脚本 scripts/fix_claim_source_ids.sql 必须可达").isNotNull();

        // 逐行过滤：去掉注释行与空行（脚本开头的 -- 注释会污染第一个 UPDATE，
        // 不能仅靠整块 startsWith("--") 判定跳过）。
        StringBuilder clean = new StringBuilder();
        for (String line : sql.split("\\r?\\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("--")) {
                continue;
            }
            clean.append(line).append("\n");
        }

        for (String stmt : clean.toString().split(";")) {
            String trimmed = stmt.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            jdbc.execute(trimmed);
        }
    }
}
