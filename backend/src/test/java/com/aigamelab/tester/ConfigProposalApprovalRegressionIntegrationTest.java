package com.aigamelab.tester;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.aigamelab.config.ApprovalService;
import com.aigamelab.domain.ChangeProposal;
import com.aigamelab.domain.ConfigVersion;
import com.aigamelab.domain.Finding;
import com.aigamelab.domain.FindingRegression;
import com.aigamelab.domain.FindingRegressionItem;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.repository.ChangeProposalRepository;
import com.aigamelab.sim.GameConfig;
import com.aigamelab.sim.GameConfigLoader;
import com.aigamelab.sim.model.Player;

/**
 * T7-4 验收：Config Proposal → Approval → Tester Regression 闭环接线。
 *
 * 验证完整闭环：
 *  - ① 落 PENDING 变更提案（afterConfigJson = 修改后的 v001 快照）；
 *  - ② ApprovalService.approve() 在当前生效版本链上创建新版本（latest）；
 *  - ③ 新旧两版分别有 Tester Finding；
 *  - ④ FindingRegressionService.diff(旧, 新) 产出回归对比，分类正确。
 *
 * 复用冻结的 ApprovalService.approve（P6-3，创建新版本+回填提案），不修改其语义；
 * 复用 T7-3 的 FindingRegressionService 做差异对比；仅消费既有接口，不新建 DB/表。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T7-4 Config Proposal → Approval → Tester Regression")
class ConfigProposalApprovalRegressionIntegrationTest {

    @Autowired ApprovalService approvalService;
    @Autowired ChangeProposalRepository proposalRepo;
    @Autowired FindingService findingService;
    @Autowired FindingRegressionService regressionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-t74-", ".db");
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
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM change_proposal");
        llm.reset();
    }

    /** 取 v001 快照，改 player.maxHp 为 1（制造一个可区分的新版本配置）。 */
    private String modifiedV001Json(String newMaxHp) {
        ConfigVersion v001 = loadV001();
        GameConfig cfg = GameConfigLoader.fromJson(v001.configJson());
        GameConfig modified = new GameConfig(
                cfg.version(),
                new Player(Double.parseDouble(newMaxHp), cfg.player().moveSpeed(),
                        cfg.player().pickupRadius(), cfg.player().armor(),
                        cfg.player().hpRegenPerSec(), cfg.player().levelUpIntervalSec(),
                        cfg.player().buildType()),
                cfg.weapons(), cfg.upgrades(), cfg.enemy(), cfg.wave(),
                cfg.difficulty(), cfg.targets(), cfg.sim());
        return GameConfigLoader.toJson(modified);
    }

    private ConfigVersion loadV001() {
        // 复用 ConfigVersionService 种子导入：v001 必存在
        // 经 DAO 直接读（避免注入 ConfigVersionService 在 @BeforeEach 前不必要）
        return jdbc.query("SELECT version, config_json FROM game_config_version WHERE version='v001'",
                (rs, i) -> new ConfigVersion(
                        rs.getString("version"), rs.getString("config_json"),
                        null, null, null, null))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("v001 种子未导入"));
    }

    private ChangeProposal insertPendingProposal(String afterConfigJson, String baseVersion) {
        return proposalRepo.insert(new ChangeProposal(
                null, null, baseVersion, null,
                "降低玩家 maxHp 以修复失衡", "v001 下 crit 过强，降低 maxHp 提升敌人威胁",
                "[]", "diff...", afterConfigJson, "PENDING", null, null, null));
    }

    private Finding makeFinding(String scenarioId, String category, String severity,
            String title, String configVersion) {
        return new Finding(
                null, null, null, null,
                scenarioId, configVersion, severity, category,
                "LLM", title, "expect", "actual",
                "seed=42", "{}", 0.5, "OPEN", null);
    }

    // ---- T7-4 Case 1：完整闭环 proposal→approve→regression ----

    @Test
    @DisplayName("Case 1 完整闭环：proposal→approve 建 v002→两版 Finding→回归 diff")
    void proposalApprovalThenTesterRegression() {
        // ① 落 PENDING 提案（afterConfigJson = 改 maxHp=1 的 v001 快照）
        ChangeProposal proposal = insertPendingProposal(modifiedV001Json("1.0"), "v001");
        assertThat(proposal.status()).isEqualTo("PENDING");

        // ② 批准 → 创建新版本（latest = 严格递增的下一版本）
        ConfigVersion created = approvalService.approve(proposal.id(), "同意降低 maxHp");
        // 提案已回填 APPROVED + targetVersion
        ChangeProposal after = proposalRepo.findById(proposal.id()).orElseThrow();
        assertThat(after.status()).isEqualTo("APPROVED");
        assertThat(after.targetVersion()).isEqualTo(created.version());
        String newVersion = created.version();

        // ③ 新旧两版分别上报 Tester Finding
        // v001 baseline（修改前）有 1 个缺陷
        findingService.report(makeFinding("scA", "BALANCE_ANOMALY", "HIGH",
                "crit 过强 winrate 过高", "v001"));
        // v002（修改后，maxHp=1）该缺陷消失，但新增 1 个新缺陷（maxHp 太低被秒杀）
        findingService.report(makeFinding("scB", "BALANCE_ANOMALY", "HIGH",
                "maxHp=1 玩家被秒杀", newVersion));

        // ④ 回归对比：v001 → 新版本
        FindingRegression result = regressionService.diff("v001", newVersion);

        assertThat(result.oldConfigVersion()).isEqualTo("v001");
        assertThat(result.newConfigVersion()).isEqualTo(newVersion);
        // v001 的缺陷在 v002 消失 → FIXED
        assertThat(result.fixedCount()).isEqualTo(1);
        // v002 的新缺陷 → NEW
        assertThat(result.newCount()).isEqualTo(1);
        assertThat(result.items()).hasSize(2);

        FindingRegressionItem fixed = result.items().stream()
                .filter(it -> it.status().equals(FindingRegressionItem.STATUS_FIXED))
                .findFirst().orElseThrow();
        assertThat(fixed.fingerprint().scenarioId()).isEqualTo("scA");
        assertThat(fixed.oldSeverity()).isEqualTo("HIGH");

        FindingRegressionItem newItem = result.items().stream()
                .filter(it -> it.status().equals(FindingRegressionItem.STATUS_NEW))
                .findFirst().orElseThrow();
        assertThat(newItem.fingerprint().scenarioId()).isEqualTo("scB");
        assertThat(newItem.newSeverity()).isEqualTo("HIGH");
    }

    // ---- T7-4 Case 2：approve 后 latest 即新版本（append-only 验证） ----

    @Test
    @DisplayName("Case 2 approve 后 findLatest 为新创建版本")
    void approveMakesNewVersionLatest() {
        ConfigVersion before = latestVersion();
        ChangeProposal proposal = insertPendingProposal(before.configJson(), before.version());
        ConfigVersion created = approvalService.approve(proposal.id(), "调参");

        // latest 指向新版本（append-only：最新即当前生效），且版本号严格递增 +1
        ConfigVersion latest = latestVersion();
        assertThat(latest.version()).isEqualTo(created.version());
        assertThat(seqOf(latest.version())).isEqualTo(seqOf(before.version()) + 1);
    }

    // ---- T7-4 Case 3：拒绝的提案不会产生新版本，回归为空 ----

    @Test
    @DisplayName("Case 3 拒绝提案不建版本、回归对比 baseline 为空")
    void rejectedProposalNoNewVersion() {
        ConfigVersion before = latestVersion();
        ChangeProposal proposal = insertPendingProposal(modifiedV001Json("1.0"), "v001");
        approvalService.reject(proposal.id(), "暂不调整");

        ChangeProposal after = proposalRepo.findById(proposal.id()).orElseThrow();
        assertThat(after.status()).isEqualTo("REJECTED");

        // 版本数不变（拒绝不建版本）
        assertThat(latestVersion().version()).isEqualTo(before.version());

        // 仅 v001 有 Finding，diff(v001, <latest+1 预期但不存>) → v001 的 Finding 为 FIXED（旧有、新无）
        String nonexistentNext = String.format("v%03d", seqOf(before.version()) + 1);
        findingService.report(makeFinding("scA", "CRASH", "HIGH", "crash", "v001"));
        FindingRegression result = regressionService.diff("v001", nonexistentNext);

        assertThat(result.fixedCount()).isEqualTo(1);
        assertThat(result.newCount()).isEqualTo(0);
    }

    private ConfigVersion latestVersion() {
        return jdbc.query("SELECT version, config_json FROM game_config_version ORDER BY version DESC LIMIT 1",
                (rs, i) -> new ConfigVersion(
                        rs.getString("version"), rs.getString("config_json"),
                        null, null, null, null))
                .stream().findFirst().orElseThrow();
    }

    private static int seqOf(String version) {
        if (version == null || !version.matches("^v(\\d+)$")) {
            return 0;
        }
        return Integer.parseInt(version.substring(1));
    }
}
