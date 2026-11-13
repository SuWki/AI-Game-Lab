package com.aigamelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

import com.aigamelab.common.ApiResp;
import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.DesignDocument;
import com.aigamelab.domain.ResearchClaim;
import com.aigamelab.domain.ResearchSource;

/**
 * R1-2 验收：Research REST API 5 端点 + Error Contract。
 *
 * 真实 Spring 集成测试（WebEnvironment.NONE，直接调用 Controller 方法），
 * 不 mock —— 验证 Controller → Repository → SQLite 真实接线与包络/错误码。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("R1-2 ResearchController")
class ResearchControllerTest {

    @Autowired ResearchController controller;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-r1-research-", ".db");
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
    void seed() {
        // 完整 FK-safe 清理
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM change_proposal");
        jdbc.update("DELETE FROM design_document");
        jdbc.update("DELETE FROM research_claim");
        jdbc.update("DELETE FROM research_source");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM prompt_version");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");

        // 种子：task + run + 1 来源 + 2 断言 + 1 报告 + 1 普通文档
        jdbc.update("INSERT INTO task(id, title, goal, constraints_json) VALUES(1, '研究任务', '查 Build', '{}')");
        jdbc.update("INSERT INTO run(id, task_id, status, model, current_step, max_steps, token_budget, tokens_used) "
                + "VALUES(1, 1, 'COMPLETED', 'deepseek-chat', 5, 10, 200000, 800)");
        jdbc.update("INSERT INTO research_source(id, run_id, task_id, url, source_type, title, content_summary, reliability) "
                + "VALUES(1, 1, 1, 'https://docs.example.com', 'Official', '官方文档', '内容摘要', 0.9)");
        jdbc.update("INSERT INTO research_claim(id, run_id, task_id, claim, confidence, status) "
                + "VALUES(1, 1, 1, 'Crit 胜率偏高', 0.85, 'VALIDATED')");
        jdbc.update("INSERT INTO research_claim(id, run_id, task_id, claim, confidence, status) "
                + "VALUES(2, 1, 1, 'AS 需要测试', 0.4, 'DRAFT')");
        jdbc.update("INSERT INTO design_document(id, run_id, task_id, title, content_md) "
                + "VALUES(1, 1, 1, '[RESEARCH REPORT] Build 研究', '# 报告正文')");
        jdbc.update("INSERT INTO design_document(id, run_id, task_id, title, content_md) "
                + "VALUES(2, 1, 1, '[DESIGN] 普通设计文档', '# 设计正文')");
    }

    // ==================== Sources ====================

    @Test
    void listSourcesByRunId_returnsSources() {
        ApiResp<List<ResearchSource>> resp = controller.listSources(1L, null);
        assertThat(resp.code()).isZero();
        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).url()).isEqualTo("https://docs.example.com");
        assertThat(resp.data().get(0).sourceType()).isEqualTo("Official");
        assertThat(resp.data().get(0).reliability()).isEqualTo(0.9);
    }

    @Test
    void listSourcesByTaskId_returnsSources() {
        ApiResp<List<ResearchSource>> resp = controller.listSources(null, 1L);
        assertThat(resp.code()).isZero();
        assertThat(resp.data()).hasSize(1);
    }

    @Test
    void listSources_noScope_throws40001() {
        BusinessException e = assertThrows(BusinessException.class, () -> controller.listSources(null, null));
        assertThat(e.getCode()).isEqualTo(40001);
    }

    @Test
    void getSource_foundAndMissing() {
        ApiResp<ResearchSource> found = controller.getSource(1L);
        assertThat(found.data().title()).isEqualTo("官方文档");

        BusinessException e = assertThrows(BusinessException.class, () -> controller.getSource(999L));
        assertThat(e.getCode()).isEqualTo(40401);
    }

    // ==================== Claims ====================

    @Test
    void listClaimsByRunId_returnsAll() {
        ApiResp<List<ResearchClaim>> resp = controller.listClaims(1L, null, null);
        assertThat(resp.code()).isZero();
        assertThat(resp.data()).hasSize(2);
    }

    @Test
    void listClaims_withStatusFilter_filtersOnlyMatching() {
        ApiResp<List<ResearchClaim>> resp = controller.listClaims(1L, null, "VALIDATED");
        assertThat(resp.code()).isZero();
        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).status()).isEqualTo("VALIDATED");
        assertThat(resp.data().get(0).confidence()).isEqualTo(0.85);
    }

    @Test
    void listClaims_noScope_throws40001() {
        BusinessException e = assertThrows(BusinessException.class, () -> controller.listClaims(null, null, null));
        assertThat(e.getCode()).isEqualTo(40001);
    }

    @Test
    void getClaim_foundAndMissing() {
        ApiResp<ResearchClaim> found = controller.getClaim(2L);
        assertThat(found.data().claim()).isEqualTo("AS 需要测试");
        assertThat(found.data().status()).isEqualTo("DRAFT");

        BusinessException e = assertThrows(BusinessException.class, () -> controller.getClaim(999L));
        assertThat(e.getCode()).isEqualTo(40401);
    }

    // ==================== Reports ====================

    @Test
    void listReportsByRunId_returnsOnlyResearchReportPrefix() {
        ApiResp<List<DesignDocument>> resp = controller.listReports(1L, null);
        assertThat(resp.code()).isZero();
        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).title()).startsWith("[RESEARCH REPORT]");
        assertThat(resp.data().get(0).contentMd()).isEqualTo("# 报告正文");
    }

    @Test
    void listReports_noScope_throws40001() {
        BusinessException e = assertThrows(BusinessException.class, () -> controller.listReports(null, null));
        assertThat(e.getCode()).isEqualTo(40001);
    }

    // ==================== TD：Claim Sources Drill-down ====================

    @Test
    void getClaimSources_validClaim_returnsSupportingAndContradicting() {
        // 补充种植：给 claim 1 一条支持来源 id=1，一条反驳来源 id=2
        jdbc.update("INSERT INTO research_source(id, run_id, task_id, url, source_type, title, content_summary, reliability) "
                + "VALUES(2, 1, 1, 'https://example.com/2', 'Academic', '来源2', '摘要2', 0.7)");
        jdbc.update("UPDATE research_claim SET supporting_source_ids='[1]', contradicting_source_ids='[2]' WHERE id=1");

        var resp = controller.getClaimSources(1L);
        assertThat(resp.code()).isZero();
        assertThat(resp.data().claim().id()).isEqualTo(1L);
        assertThat(resp.data().supporting()).hasSize(1);
        assertThat(resp.data().supporting().get(0).title()).isEqualTo("官方文档");
        assertThat(resp.data().contradicting()).hasSize(1);
        assertThat(resp.data().contradicting().get(0).title()).isEqualTo("来源2");
    }

    @Test
    void getClaimSources_missingClaim_throws40401() {
        BusinessException e = assertThrows(BusinessException.class, () -> controller.getClaimSources(999L));
        assertThat(e.getCode()).isEqualTo(40401);
    }

    @Test
    void getClaimSources_emptyArrays_returnsEmptyLists() {
        // claim 2 没有 sources，返回空
        var resp = controller.getClaimSources(2L);
        assertThat(resp.code()).isZero();
        assertThat(resp.data().claim().id()).isEqualTo(2L);
        assertThat(resp.data().supporting()).isEmpty();
        assertThat(resp.data().contradicting()).isEmpty();
    }

    @Test
    void getClaimSources_crossResearchLeak_filteringWorks() {
        // 种子另一条不同 run 的来源 id=3，确保不会出现在 claim 1 的结果中
        jdbc.update("INSERT INTO task(id, title, goal, constraints_json) VALUES(2, '研究B', '查B', '{}')");
        jdbc.update("INSERT INTO run(id, task_id, status, model, current_step, max_steps, token_budget, tokens_used) "
                + "VALUES(2, 2, 'COMPLETED', 'deepseek-chat', 5, 10, 200000, 800)");
        jdbc.update("INSERT INTO research_source(id, run_id, task_id, url, source_type, title, content_summary, reliability) "
                + "VALUES(3, 2, 2, 'https://example.com/other', 'Official', '其他研究来源', '摘要', 0.9)");
        // 引用 run=2 的来源 id=3（所有权不匹配）
        jdbc.update("UPDATE research_claim SET supporting_source_ids='[1,3]' WHERE id=1");

        var resp = controller.getClaimSources(1L);
        // 仅 run=1 的来源 id=1 通过所有权过滤，id=3 被剔除
        assertThat(resp.data().supporting()).hasSize(1);
        assertThat(resp.data().supporting().get(0).id()).isEqualTo(1L);
    }
}
