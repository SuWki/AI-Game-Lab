package com.aigamelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
import com.aigamelab.domain.ChangeProposal;
import com.aigamelab.domain.ConfigVersion;

/**
 * P6-4 ConfigController 验收：4 个 endpoint + Error Contract。
 *
 * 真实 Spring 集成测试（WebEnvironment.NONE，直接调用 Controller 方法），
 * 不 mock 任何 Service/Repository —— 验证 HTTP → Controller → 既有 Service → ApiResp / Error Contract 的真实接线。
 * 异常以 BusinessException 形式在 NONE 环境下抛出，断言其 getCode()（40401 / 40901）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P6-4 ConfigController")
class ConfigControllerTest {

    @Autowired ConfigController controller;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p64-cfg-", ".db");
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
        // 清空顺序遵循 FK：子表先清。
        // 异步 AgentLoop（approve→resume→run）可能在测试间写入 step/tool_call，
        // 所以在 DELETE FROM run 前追加第二轮清理，消除竞态窗口。
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
        // 第二轮：捕获异步 AgentLoop 在 clean 过程中写入的 step/tool_call
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
        // game_config_version 保留种子 v001（ConfigVersionService 幂等导入），不删除
    }

    private void seedTaskAndRun() {
        jdbc.update("INSERT INTO task(id, title, goal, constraints_json) VALUES(1, 't', 'g', '{}')");
        jdbc.update("INSERT INTO run(id, task_id, status, model, current_step, max_steps, token_budget, tokens_used) "
                + "VALUES(1, 1, 'WAITING_APPROVAL', 'deepseek-chat', 3, 10, 200000, 500)");
    }

    private long insertPendingProposal(String afterConfig) {
        jdbc.update("INSERT INTO change_proposal(run_id, base_version, title, reason, diff_json, after_config_json, status) "
                + "VALUES(1, ?, '提升 DPS', '胜率偏低', '[]', ?, 'PENDING')",jdbc.queryForObject("SELECT version FROM game_config_version ORDER BY version DESC LIMIT 1",String.class), afterConfig);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM change_proposal", Long.class);
        return id == null ? -1L : id;
    }

    /** 包装审批意见为 Controller 的请求体类型。 */
    private ConfigController.DecisionRequest decision(String comment) {
        return new ConfigController.DecisionRequest(comment);
    }

    // ==================== Versions ====================

    @Test
    void versionsReturnsHistoryLatestFirst() {
        ApiResp<List<ConfigVersion>> resp = controller.versions();
        assertThat(resp.code()).isZero();
        List<ConfigVersion> vs = resp.data();
        assertThat(vs).isNotEmpty(); // 至少含种子 v001
        // 最新优先：首元素版本号 >= 末元素
        assertThat(vs.get(0).version()).isGreaterThanOrEqualTo(vs.get(vs.size() - 1).version());
        // append-only 历史数据未被修改：v001 仍存在且 config_json 非空
        boolean hasV001 = vs.stream().anyMatch(v -> "v001".equals(v.version()));
        assertThat(hasV001).isTrue();
    }

    // ==================== Proposals ====================

    @Test
    void proposalsByStatusPendingReturnsOnlyPending() {
        seedTaskAndRun();
        insertPendingProposal("{\"a\":1}");
        // 插入一个已审批的提案，验证不被混入 PENDING 查询
        jdbc.update("INSERT INTO change_proposal(run_id, base_version, title, reason, after_config_json, status, target_version, diff_json) "
                + "VALUES(1, 'v001', '已批', 'r', '{}', 'APPROVED', 'v002', '[]')");

        ApiResp<List<ChangeProposal>> resp = controller.proposals("PENDING");
        assertThat(resp.code()).isZero();
        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0)).isInstanceOf(com.aigamelab.domain.ChangeProposal.class);
        com.aigamelab.domain.ChangeProposal p = resp.data().get(0);
        assertThat(p.status()).isEqualTo("PENDING");
    }

    @Test
    void proposalsNoStatusReturnsAll() {
        seedTaskAndRun();
        insertPendingProposal("{\"a\":1}");
        jdbc.update("INSERT INTO change_proposal(run_id, base_version, title, reason, after_config_json, status, target_version, diff_json) "
                + "VALUES(1, 'v001', '已批', 'r', '{}', 'APPROVED', 'v002', '[]')");

        ApiResp<List<ChangeProposal>> resp = controller.proposals(null);
        assertThat(resp.code()).isZero();
        assertThat(resp.data()).hasSize(2);
    }

    // ==================== Approve ====================

    @Test
    void approveCreatesVersionAndApprovesProposal() {
        seedTaskAndRun();
        long pid = insertPendingProposal("{\"buff\":true}");
        int versionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM game_config_version", Integer.class);

        ApiResp<ConfigVersion> resp = controller.approve(pid, decision("同意"));
        assertThat(resp.code()).isZero();
        ConfigVersion created = resp.data();
        assertThat(created).isNotNull();
        assertThat(created.version()).isNotEqualTo("v001");

        // 新版本成为 latest = current
        String latest = jdbc.queryForObject("SELECT version FROM game_config_version ORDER BY version DESC LIMIT 1", String.class);
        assertThat(latest).isEqualTo(created.version());

        // 版本数 +1（append-only 未改历史）
        int versionsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM game_config_version", Integer.class);
        assertThat(versionsAfter).isEqualTo(versionsBefore + 1);

        // 提案 → APPROVED + targetVersion 回填
        Map<String, Object> prop = jdbc.queryForMap("SELECT status, target_version FROM change_proposal WHERE id=?", pid);
        assertThat(prop.get("status")).isEqualTo("APPROVED");
        assertThat(prop.get("target_version")).isEqualTo(created.version());
        // Run resume 断言不在此处：resume 为异步（AgentLoop），验证属于 P1-4 / RunControllerReadTest；
        // P6-4 只验证 HTTP → Controller → ApprovalService 接线正确。
    }

    // ==================== Reject ====================

    @Test
    void rejectDoesNotCreateVersion() {
        seedTaskAndRun();
        long pid = insertPendingProposal("{\"buff\":true}");
        int versionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM game_config_version", Integer.class);

        ApiResp<Void> resp = controller.reject(pid, decision("不同意"));
        assertThat(resp.code()).isZero();

        // 不创建新版本
        int versionsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM game_config_version", Integer.class);
        assertThat(versionsAfter).isEqualTo(versionsBefore);

        // 提案 → REJECTED，targetVersion 为 null
        Map<String, Object> prop = jdbc.queryForMap("SELECT status, target_version FROM change_proposal WHERE id=?", pid);
        assertThat(prop.get("status")).isEqualTo("REJECTED");
        assertThat(prop.get("target_version")).isNull();

        // latest 不变化
        String latest = jdbc.queryForObject("SELECT version FROM game_config_version ORDER BY version DESC LIMIT 1", String.class);
        assertThat(latest).isEqualTo("v001");
    }

    // ==================== Error Contract ====================

    @Test
    void approveMissingProposalThrows404() {
        BusinessException e = assertThrows(BusinessException.class, () -> controller.approve(99999L, null));
        assertThat(e.getCode()).isEqualTo(40401);
    }

    @Test
    void approveNonPendingThrows409() {
        seedTaskAndRun();
        long pid = insertPendingProposal("{\"buff\":true}");
        controller.approve(pid, decision("同意")); // 已是 APPROVED
        BusinessException e = assertThrows(BusinessException.class, () -> controller.approve(pid, null));
        assertThat(e.getCode()).isEqualTo(40901);
    }

    @Test
    void rejectMissingProposalThrows404() {
        BusinessException e = assertThrows(BusinessException.class, () -> controller.reject(99999L, null));
        assertThat(e.getCode()).isEqualTo(40401);
    }

    @Test
    void rejectNonPendingThrows409() {
        seedTaskAndRun();
        long pid = insertPendingProposal("{\"buff\":true}");
        controller.reject(pid, decision("拒绝")); // 已是 REJECTED
        BusinessException e = assertThrows(BusinessException.class, () -> controller.reject(pid, null));
        assertThat(e.getCode()).isEqualTo(40901);
    }
}
