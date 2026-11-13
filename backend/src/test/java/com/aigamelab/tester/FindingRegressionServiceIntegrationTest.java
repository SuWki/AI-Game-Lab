package com.aigamelab.tester;

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

import com.aigamelab.domain.Finding;
import com.aigamelab.domain.FindingRegression;
import com.aigamelab.domain.FindingRegressionItem;
import com.aigamelab.llm.FakeLlmGateway;
import com.aigamelab.repository.FindingRepository;

/**
 * T7-3 验收：版本间 Finding 回归对比（NEW/FIXED/PERSISTED/CHANGED）。
 *
 * 验证核心 diff 算法：
 *  - NEW：v002 新增、v001 无 → 升级引入的新缺陷；
 *  - FIXED：v001 有、v002 无 → 升级修复的旧缺陷；
 *  - PERSISTED：两组都有、severity 不变 → 持续；
 *  - CHANGED：两组都有、severity 变化 → 持续但严重程度变化。
 *
 * 使用真实 DB 落库 Finding，经 FindingService.findByFilter 查询两组集合后做对比。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T7-3 Finding 回归对比 diff")
class FindingRegressionServiceIntegrationTest {

    @Autowired FindingService findingService;
    @Autowired FindingRegressionService regressionService;
    @Autowired FindingRepository findingRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-t73-", ".db");
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
        // PF-003：回归 diff 需真实 v002（report 现在校验 configVersion 存在）
        TestConfigSeeds.seedV002FromV001(jdbc);
        llm.reset();
    }

    private Finding makeFinding(String scenarioId, String category, String severity,
            String title, String configVersion) {
        return new Finding(
                null, null, null, null,
                scenarioId, configVersion, severity, category,
                "LLM", title, "expect", "actual",
                "seed=42", "{}", 0.5, "OPEN", null);
    }

    // ---- Case 1：完整 4 类对比 ----

    @Test
    @DisplayName("Case 1 NEW/FIXED/PERSISTED/CHANGED 四类齐全")
    void fourCategoriesRegression() {
        // v001 baseline
        findingService.report(makeFinding("scA", "CRASH", "HIGH", "crash on wave 1", "v001")); // → FIXED
        findingService.report(makeFinding("scB", "BALANCE_ANOMALY", "MEDIUM", "hp too high", "v001")); // → PERSISTED
        findingService.report(makeFinding("scC", "SOFT_LOCK", "LOW", "soft lock", "v001")); // → CHANGED

        // v002
        findingService.report(makeFinding("scB", "BALANCE_ANOMALY", "MEDIUM", "hp too high", "v002")); // PERSISTED
        findingService.report(makeFinding("scC", "SOFT_LOCK", "HIGH", "soft lock", "v002")); // CHANGED (LOW→HIGH)
        findingService.report(makeFinding("scD", "CRASH", "BLOCKER", "null on restart", "v002")); // → NEW

        FindingRegression result = regressionService.diff("v001", "v002");

        assertThat(result.oldConfigVersion()).isEqualTo("v001");
        assertThat(result.newConfigVersion()).isEqualTo("v002");
        assertThat(result.newCount()).isEqualTo(1);
        assertThat(result.fixedCount()).isEqualTo(1);
        assertThat(result.persistedCount()).isEqualTo(1);
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(result.items()).hasSize(4);

        // 验证具体分类
        FindingRegressionItem newItem = find(result.items(), FindingRegressionItem.STATUS_NEW);
        assertThat(newItem.fingerprint().scenarioId()).isEqualTo("scD");
        assertThat(newItem.newSeverity()).isEqualTo("BLOCKER");
        assertThat(newItem.oldSeverity()).isNull();

        FindingRegressionItem fixedItem = find(result.items(), FindingRegressionItem.STATUS_FIXED);
        assertThat(fixedItem.fingerprint().scenarioId()).isEqualTo("scA");
        assertThat(fixedItem.oldSeverity()).isEqualTo("HIGH");
        assertThat(fixedItem.newSeverity()).isNull();

        FindingRegressionItem persistedItem = find(result.items(), FindingRegressionItem.STATUS_PERSISTED);
        assertThat(persistedItem.fingerprint().scenarioId()).isEqualTo("scB");
        assertThat(persistedItem.oldSeverity()).isEqualTo("MEDIUM");
        assertThat(persistedItem.newSeverity()).isEqualTo("MEDIUM");

        FindingRegressionItem changedItem = find(result.items(), FindingRegressionItem.STATUS_CHANGED);
        assertThat(changedItem.fingerprint().scenarioId()).isEqualTo("scC");
        assertThat(changedItem.oldSeverity()).isEqualTo("LOW");
        assertThat(changedItem.newSeverity()).isEqualTo("HIGH");
    }

    // ---- Case 2：空基线（全是 NEW） ----

    @Test
    @DisplayName("Case 2 空基线 v000 → 所有 v001 为 NEW")
    void emptyBaselineAllNew() {
        findingService.report(makeFinding("scA", "CRASH", "HIGH", "crash 1", "v001"));
        findingService.report(makeFinding("scB", "CRASH", "HIGH", "crash 2", "v001"));

        FindingRegression result = regressionService.diff("v000", "v001");

        assertThat(result.newCount()).isEqualTo(2);
        assertThat(result.fixedCount()).isEqualTo(0);
        assertThat(result.persistedCount()).isEqualTo(0);
        assertThat(result.changedCount()).isEqualTo(0);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).allMatch(it -> it.status().equals(FindingRegressionItem.STATUS_NEW));
    }

    // ---- Case 3：完全相同（全 PERSISTED） ----

    @Test
    @DisplayName("Case 3 两版本完全相同 → 全 PERSISTED")
    void identicalVersionsAllPersisted() {
        findingService.report(makeFinding("scA", "CRASH", "HIGH", "crash 1", "v001"));
        findingService.report(makeFinding("scA", "CRASH", "HIGH", "crash 1", "v002"));

        FindingRegression result = regressionService.diff("v001", "v002");

        assertThat(result.persistedCount()).isEqualTo(1);
        assertThat(result.newCount()).isEqualTo(0);
        assertThat(result.fixedCount()).isEqualTo(0);
        assertThat(result.changedCount()).isEqualTo(0);
    }

    // ---- Case 4：标题归一化使变体匹配为 PERSISTED ----

    @Test
    @DisplayName("Case 4 标题大小写/空格变体 → 匹配为 PERSISTED 而非 NEW+FIXED")
    void normalizedTitleVariantsMatchPersisted() {
        findingService.report(makeFinding("scA", "CRASH", "HIGH", "HP 越界", "v001"));
        findingService.report(makeFinding("scA", "CRASH", "HIGH", "  hp   越界  ", "v002"));

        FindingRegression result = regressionService.diff("v001", "v002");

        assertThat(result.persistedCount()).isEqualTo(1);
        assertThat(result.newCount()).isEqualTo(0);
        assertThat(result.fixedCount()).isEqualTo(0);
        assertThat(result.changedCount()).isEqualTo(0);
    }

    // ---- Case 5：同逻辑键重复上报只取最高 severity ----

    @Test
    @DisplayName("Case 5 同逻辑键重复上报 → 取最高 severity")
    void dedupByFingerprintPicksMaxSeverity() {
        // v001 重复上报同一逻辑缺陷（LOW 与 HIGH），CHANGED 到 v002 的 MEDIUM
        findingService.report(makeFinding("scA", "CRASH", "LOW", "crash", "v001"));
        findingService.report(makeFinding("scA", "CRASH", "HIGH", "crash", "v001"));
        findingService.report(makeFinding("scA", "CRASH", "MEDIUM", "crash", "v002"));

        FindingRegression result = regressionService.diff("v001", "v002");

        // 旧取 HIGH，新取 MEDIUM → CHANGED（HIGH→MEDIUM），不应出现 2 条
        assertThat(result.items()).hasSize(1);
        FindingRegressionItem it = result.items().get(0);
        assertThat(it.status()).isEqualTo(FindingRegressionItem.STATUS_CHANGED);
        assertThat(it.oldSeverity()).isEqualTo("HIGH");
        assertThat(it.newSeverity()).isEqualTo("MEDIUM");
    }

    // ---- helpers ----

    private FindingRegressionItem find(List<FindingRegressionItem> items, String status) {
        return items.stream()
                .filter(it -> it.status().equals(status))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 status=" + status));
    }
}
