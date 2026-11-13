package com.aigamelab.config;

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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.ChangeProposal;
import com.aigamelab.domain.ConfigVersion;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.repository.ChangeProposalRepository;
import com.aigamelab.repository.ConfigVersionRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;

/**
 * P6-3 验收测试（03 文档）：approve 成功创建新 ConfigVersion 且成为 latest/current；
 * 原历史版本保持不变；approve 后读取 latest 得到新配置；reject 对称；
 * 关联 Run 被唤醒（WAITING_APPROVAL→RUNNING）。
 * 通过 change_proposal 表与 RunControlService.resume 接口解耦（不 import P3 内部类）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P6-3 ApprovalService")
class ApprovalServiceTest {

    @Autowired ApprovalService approval;
    @Autowired ConfigVersionService configService;
    @Autowired ConfigVersionRepository configRepo;
    @Autowired ChangeProposalRepository proposalRepo;
    @Autowired RunRepository runRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p63-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Path DB = tempDb();

    private static String dbUrl() {
        return "jdbc:sqlite:" + DB.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON";
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ApprovalServiceTest::dbUrl);
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
        // 子表先于父表删除，避免 FOREIGN KEY 约束失败（foreign_keys=ON）
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM change_proposal");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
        jdbc.update("DELETE FROM game_config_version");
        // 恢复种子 v001（模拟启动后状态，作为 approve 的 base version）
        jdbc.update("INSERT INTO game_config_version(version, config_json, parent_version, change_proposal_id, comment) VALUES(?,?,?,?,?)",
                "v001", readClasspathSeed(), null, null, "initial seed");
    }

    private String readClasspathSeed() {
        try (var in = new org.springframework.core.io.ClassPathResource("config/v001.json").getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String afterConfigForVersion(String baseVersion) {
        // 模拟 P3-3 计算的 after：将 base 版本的 critMultiplier 从 2.8 改为 1.7
        ConfigVersion base = configRepo.findByVersion(baseVersion)
                .orElseThrow(() -> new IllegalStateException("base version 缺失: " + baseVersion));
        return base.configJson()
                .replace("\"critMultiplier\": 2.8", "\"critMultiplier\": 1.7")
                .replace("\"critMultiplier\":2.8", "\"critMultiplier\":1.7");
    }

    private ChangeProposal pendingProposal(String baseVersion, Long runId) {
        return proposalRepo.insert(new ChangeProposal(
                null, runId, baseVersion, null,
                "平衡 crit build", "crit 胜率过高，下调倍率",
                "[]", "[{\"path\":\"weapons[0].critMultiplier\",\"before\":2.8,\"after\":1.7}]",
                afterConfigForVersion(baseVersion), "PENDING", null, null, null));
    }

    @Test
    void approveCreatesNewVersionAndMakesItLatest() {
        // v001 由 seed 提供
        ChangeProposal p = pendingProposal("v001", null);

        ConfigVersion created = approval.approve(p.id(), "approved by human");

        // 新版本成为 latest/current
        assertThat(created.version()).isEqualTo("v002");
        assertThat(configService.findLatest()).isPresent();
        assertThat(configService.findLatest().get().version()).isEqualTo("v002");
        assertThat(configService.getCurrent().version()).isEqualTo("v002");
        // 新版本内容即 after（critMultiplier 已下调）
        assertThat(created.configJson()).contains("\"critMultiplier\": 1.7");
        // 提案回填 APPROVED + targetVersion
        ChangeProposal decided = proposalRepo.findById(p.id()).orElseThrow();
        assertThat(decided.status()).isEqualTo("APPROVED");
        assertThat(decided.targetVersion()).isEqualTo("v002");
    }

    @Test
    void approvePreservesHistoricalSnapshot() {
        ChangeProposal p = pendingProposal("v001", null);
        String v1Json = configRepo.findByVersion("v001").orElseThrow().configJson();

        approval.approve(p.id(), "ok");

        // 原历史版本 v001 未被修改
        ConfigVersion v1After = configRepo.findByVersion("v001").orElseThrow();
        assertThat(v1After.configJson()).isEqualTo(v1Json);
        assertThat(v1After.configJson()).contains("\"critMultiplier\": 2.8");
    }

    @Test
    void approvePublishesConfigVersionCreatedEvent() {
        ChangeProposal p = pendingProposal("v001", null);
        approval.approve(p.id(), "ok");

        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_event WHERE type='CONFIG_VERSION_CREATED' AND payload_json LIKE ?",
                Integer.class, "%\"version\":\"v002\"%");
        assertThat(events).isGreaterThanOrEqualTo(1);
    }

    @Test
    void approveRejectsNonPendingProposal() {
        ChangeProposal p = pendingProposal("v001", null);
        approval.approve(p.id(), "first approve");
        // 二次 approve：已是 APPROVED
        assertThatThrownBy(() -> approval.approve(p.id(), "again"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(40901));
    }

    @Test
    void approveMissingProposalThrowsNotFound() {
        assertThatThrownBy(() -> approval.approve(9999L, "x"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(40401));
    }

    @Test
    void approveResumesLinkedRunToRunning() throws Exception {
        Task t = taskRepo.insert(new Task(null, "t", "g", "{}", 50, false, "ACTIVE", null));
        Run run = runRepo.insert(new Run(null, t.id(), "PENDING", null, null,
                0, 50, 1000000, 0, null, null, null, null, null));
        // 置为 WAITING_APPROVAL（模拟 P3-3 挂起）
        runRepo.updateStatusAndStep(run.id(), com.aigamelab.domain.RunStatus.WAITING_APPROVAL, 1);

        ChangeProposal p = pendingProposal("v001", run.id());
        llm.thenStop("审批后恢复");

        approval.approve(p.id(), "ok");

        // Run 被唤醒：离开 WAITING_APPROVAL（最终落为 RUNNING 或 AgentLoop 续跑至 COMPLETED）
        // 注意：单连接池下 RUNNING→COMPLETED 可能瞬时完成，故断言"已脱离挂起"这一稳定不变量
        long deadline = System.currentTimeMillis() + 10_000;
        String finalStatus = "WAITING_APPROVAL";
        while (System.currentTimeMillis() < deadline) {
            String s = runRepo.findById(run.id()).orElseThrow().status();
            if (!"WAITING_APPROVAL".equals(s)) {
                finalStatus = s;
                break;
            }
            Thread.sleep(20);
        }
        assertThat(finalStatus).isNotEqualTo("WAITING_APPROVAL");
    }

    @Test
    void rejectSetsRejectedAndResumeRun() throws Exception {
        Task t = taskRepo.insert(new Task(null, "t", "g", "{}", 50, false, "ACTIVE", null));
        Run run = runRepo.insert(new Run(null, t.id(), "PENDING", null, null,
                0, 50, 1000000, 0, null, null, null, null, null));
        runRepo.updateStatusAndStep(run.id(), com.aigamelab.domain.RunStatus.WAITING_APPROVAL, 1);

        ChangeProposal p = pendingProposal("v001", run.id());
        llm.thenStop("拒绝后恢复");

        approval.reject(p.id(), "not good");

        ChangeProposal decided = proposalRepo.findById(p.id()).orElseThrow();
        assertThat(decided.status()).isEqualTo("REJECTED");
        assertThat(decided.targetVersion()).isNull();

        long deadline = System.currentTimeMillis() + 10_000;
        String finalStatus = "WAITING_APPROVAL";
        while (System.currentTimeMillis() < deadline) {
            String s = runRepo.findById(run.id()).orElseThrow().status();
            if (!"WAITING_APPROVAL".equals(s)) {
                finalStatus = s;
                break;
            }
            Thread.sleep(20);
        }
        assertThat(finalStatus).isNotEqualTo("WAITING_APPROVAL");
        // reject 不应产生新版本
        assertThat(configRepo.findAll()).hasSize(1); // 仅 seed v001
    }
}
