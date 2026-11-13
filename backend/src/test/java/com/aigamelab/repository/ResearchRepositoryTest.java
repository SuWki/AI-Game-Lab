package com.aigamelab.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aigamelab.domain.ResearchClaim;
import com.aigamelab.domain.ResearchSource;

/**
 * R0-3 验收测试：research_source / research_claim Repository CRUD。
 */
@DisplayName("R0-3 Research Repository 落地验收")
class ResearchRepositoryTest extends AbstractRepositoryTest {

    @Autowired ResearchSourceRepository sourceRepo;
    @Autowired ResearchClaimRepository claimRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired RunRepository runRepo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanAll() {
        jdbc.update("DELETE FROM research_claim");
        jdbc.update("DELETE FROM research_source");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM change_proposal");
        jdbc.update("DELETE FROM game_config_version");
        jdbc.update("DELETE FROM design_document");
        jdbc.update("DELETE FROM prompt_version");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    private long newTaskId() {
        return taskRepo.insert(new com.aigamelab.domain.Task(null, "Research 验收任务",
                "验证 research_source/claim", "{}", 30, true, "ACTIVE", null)).id();
    }

    private long newRunId(long taskId) {
        return runRepo.insert(new com.aigamelab.domain.Run(null, taskId, "PENDING", null,
                null, 0, 25, 100000, 0, null, null, null, null, null)).id();
    }

    // ==================== research_source CRUD ====================

    @Test
    @DisplayName("research_source CRUD 往返")
    void source_crudRoundtrip() {
        long taskId = newTaskId();
        long runId = newRunId(taskId);

        ResearchSource created = sourceRepo.insert(new ResearchSource(null, runId, taskId,
                "https://example.com/article1", "Official", "测试来源标题",
                "这是内容摘要", 0.9, null, null));
        assertThat(created.id()).isNotNull();
        assertThat(created.url()).isEqualTo("https://example.com/article1");
        assertThat(created.sourceType()).isEqualTo("Official");
        assertThat(created.reliability()).isEqualTo(0.9);
        assertThat(created.createdAt()).isNotNull();

        // Read
        ResearchSource loaded = sourceRepo.findById(created.id()).orElseThrow();
        assertThat(loaded.title()).isEqualTo("测试来源标题");
        assertThat(loaded.contentSummary()).isEqualTo("这是内容摘要");

        // findByRunId
        sourceRepo.insert(new ResearchSource(null, runId, taskId,
                "https://example.com/article2", "Community", "第二篇",
                "摘要2", 0.5, null, null));
        List<ResearchSource> byRun = sourceRepo.findByRunId(runId);
        assertThat(byRun).hasSize(2);
        assertThat(byRun.get(0).url()).isEqualTo("https://example.com/article1");
        assertThat(byRun.get(1).url()).isEqualTo("https://example.com/article2");

        // findByTaskId
        List<ResearchSource> byTask = sourceRepo.findByTaskId(taskId);
        assertThat(byTask).hasSize(2);

        // Not found
        assertThat(sourceRepo.findById(999999L)).isEmpty();
    }

    @Test
    @DisplayName("research_source reliability 可为 null")
    void source_nullableReliability() {
        long taskId = newTaskId();
        long runId = newRunId(taskId);

        ResearchSource created = sourceRepo.insert(new ResearchSource(null, runId, taskId,
                "https://example.com", "Speculation", "无评分来源",
                null, null, null, null));
        ResearchSource loaded = sourceRepo.findById(created.id()).orElseThrow();
        assertThat(loaded.reliability()).isNull();
        assertThat(loaded.contentSummary()).isNull();
    }

    // ==================== research_claim CRUD ====================

    @Test
    @DisplayName("research_claim CRUD 往返 + confidence 排序")
    void claim_crudRoundtrip() {
        long taskId = newTaskId();
        long runId = newRunId(taskId);

        ResearchClaim created = claimRepo.insert(new ResearchClaim(null, runId, taskId,
                "游戏平衡性良好", 0.7, "[1,2]", "[]", "DRAFT", null));
        assertThat(created.id()).isNotNull();
        assertThat(created.claim()).isEqualTo("游戏平衡性良好");
        assertThat(created.confidence()).isEqualTo(0.7);
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.supportingSourceIds()).isEqualTo("[1,2]");
        assertThat(created.createdAt()).isNotNull();

        // Read
        ResearchClaim loaded = claimRepo.findById(created.id()).orElseThrow();
        assertThat(loaded.claim()).isEqualTo("游戏平衡性良好");

        // updateConfidence
        claimRepo.updateConfidence(created.id(), 0.9);
        assertThat(claimRepo.findById(created.id()).orElseThrow().confidence()).isEqualTo(0.9);

        // updateStatus
        claimRepo.updateStatus(created.id(), "VALIDATED");
        assertThat(claimRepo.findById(created.id()).orElseThrow().status()).isEqualTo("VALIDATED");

        // updateSourceIds
        claimRepo.updateSourceIds(created.id(), "[1,2,3]", "[4]");
        ResearchClaim updated = claimRepo.findById(created.id()).orElseThrow();
        assertThat(updated.supportingSourceIds()).isEqualTo("[1,2,3]");
        assertThat(updated.contradictingSourceIds()).isEqualTo("[4]");

        // findByRunIdOrderByConfidenceDesc
        claimRepo.insert(new ResearchClaim(null, runId, taskId,
                "低置信度断言", 0.3, "[]", "[]", "DRAFT", null));
        claimRepo.insert(new ResearchClaim(null, runId, taskId,
                "高置信度断言", 0.95, "[]", "[]", "VALIDATED", null));
        List<ResearchClaim> ordered = claimRepo.findByRunIdOrderByConfidenceDesc(runId);
        assertThat(ordered).hasSize(3);
        assertThat(ordered.get(0).confidence()).isEqualTo(0.95);
        assertThat(ordered.get(1).confidence()).isEqualTo(0.9);
        assertThat(ordered.get(2).confidence()).isEqualTo(0.3);

        // findByTaskId
        assertThat(claimRepo.findByTaskId(taskId)).hasSize(3);

        // findByRunId
        assertThat(claimRepo.findByRunId(runId)).hasSize(3);

        // Not found
        assertThat(claimRepo.findById(999999L)).isEmpty();
    }

    // ==================== 索引验证 ====================

    @Test
    @DisplayName("research 表索引存在")
    void researchIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'idx_r%'",
                String.class);
        assertThat(indexes).contains(
                "idx_rs_run", "idx_rs_task",
                "idx_rc_run", "idx_rc_task", "idx_rc_confidence");
    }

    // ==================== schema 表数量验证 ====================

    @Test
    @DisplayName("共 23 张表（Runtime 协议、版本绑定与隔离修改记录）")
    void schemaHasTwentyOneTables() {
        List<String> names = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
                String.class);
        assertThat(names).hasSize(23);
        assertThat(names).contains("research_source", "research_claim", "local_test_run");
    }
}
