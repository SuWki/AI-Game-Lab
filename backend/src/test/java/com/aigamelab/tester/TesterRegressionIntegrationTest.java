package com.aigamelab.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

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

/**
 * T7-7 验收：Challenge Matrix × Regression —— 端到端完整闭环。
 *
 * 挑战矩阵（技术设计 §12）T7 扩展：
 *  - #7  两版均有同一逻辑缺陷（PERSISTED）→ fingerprint 匹配、severity 不变；
 *  - #8  两版均有同一逻辑缺陷但 severity 变化（CHANGED）→ fingerprint 匹配、severity 变化；
 *  - #9  新版本修复了旧缺陷（FIXED）→ 旧版有、新版无；
 *  - #10 新版本引入了新缺陷（NEW）→ 旧版无、新版有；
 *  - #11 四类齐全的完整回归对比（NEW+FIXED+PERSISTED+CHANGED 同时出现）。
 *
 * 复用 T7-2/T7-3 服务层，经真实 DB 验证；不修改冻结文件。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T7-7 Challenge Matrix × Regression Integration")
class TesterRegressionIntegrationTest {

    @Autowired FindingService findingService;
    @Autowired FindingRegressionService regressionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeLlmGateway llm;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-t77-", ".db");
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
        return new Finding(null, null, null, null,
                scenarioId, configVersion, severity, category,
                "LLM", title, "expect", "actual",
                "seed=42", "{}", 0.5, "OPEN", null);
    }

    // ---- #7 PERSISTED：两版同逻辑缺陷、severity 不变 ----

    @Test
    @DisplayName("#7 PERSISTED：同逻辑缺陷、severity 不变")
    void persistedSameSeverity() {
        findingService.report(makeFinding("scA", "CRASH", "HIGH", "wave crash", "v001"));
        findingService.report(makeFinding("scA", "CRASH", "HIGH", "wave crash", "v002"));

        FindingRegression result = regressionService.diff("v001", "v002");
        assertThat(result.persistedCount()).isEqualTo(1);
        assertThat(result.newCount()).isEqualTo(0);
        assertThat(result.fixedCount()).isEqualTo(0);
        assertThat(result.changedCount()).isEqualTo(0);

        FindingRegressionItem item = result.items().get(0);
        assertThat(item.fingerprint().scenarioId()).isEqualTo("scA");
        assertThat(item.fingerprint().category()).isEqualTo("CRASH");
        assertThat(item.oldSeverity()).isEqualTo("HIGH");
        assertThat(item.newSeverity()).isEqualTo("HIGH");
    }

    // ---- #8 CHANGED：同逻辑缺陷、severity 变化 ----

    @Test
    @DisplayName("#8 CHANGED：同逻辑缺陷、severity 从 LOW→HIGH")
    void changedSeverity() {
        findingService.report(makeFinding("scA", "SOFT_LOCK", "LOW", "soft lock", "v001"));
        findingService.report(makeFinding("scA", "SOFT_LOCK", "HIGH", "soft lock", "v002"));

        FindingRegression result = regressionService.diff("v001", "v002");
        assertThat(result.changedCount()).isEqualTo(1);
        FindingRegressionItem item = result.items().get(0);
        assertThat(item.status()).isEqualTo(FindingRegressionItem.STATUS_CHANGED);
        assertThat(item.oldSeverity()).isEqualTo("LOW");
        assertThat(item.newSeverity()).isEqualTo("HIGH");
    }

    // ---- #9 FIXED：旧版有、新版无 ----

    @Test
    @DisplayName("#9 FIXED：旧版缺陷在新版消失")
    void fixedDefectGone() {
        findingService.report(makeFinding("scA", "CRASH", "HIGH", "crash", "v001"));
        // v002 无该缺陷

        FindingRegression result = regressionService.diff("v001", "v002");
        assertThat(result.fixedCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo(FindingRegressionItem.STATUS_FIXED);
        assertThat(result.items().get(0).oldSeverity()).isEqualTo("HIGH");
        assertThat(result.items().get(0).newSeverity()).isNull();
    }

    // ---- #10 NEW：旧版无、新版引入 ----

    @Test
    @DisplayName("#10 NEW：新版引入新缺陷")
    void newDefectIntroduced() {
        // v001 无缺陷
        findingService.report(makeFinding("scB", "BALANCE_ANOMALY", "MEDIUM", "hp 失衡", "v002"));

        FindingRegression result = regressionService.diff("v001", "v002");
        assertThat(result.newCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo(FindingRegressionItem.STATUS_NEW);
        assertThat(result.items().get(0).newSeverity()).isEqualTo("MEDIUM");
        assertThat(result.items().get(0).oldSeverity()).isNull();
    }

    // ---- #11 四类齐全完整回归 ----

    @Test
    @DisplayName("#11 四类齐全完整回归：NEW+FIXED+PERSISTED+CHANGED")
    void allFourCategoriesComplete() {
        // v001: scA(HIGH) 将 FIXED；scB(MEDIUM) PERSISTED；scC(LOW) → CHANGED
        findingService.report(makeFinding("scA", "CRASH", "HIGH", "crash", "v001"));
        findingService.report(makeFinding("scB", "BALANCE_ANOMALY", "MEDIUM", "hp 高", "v001"));
        findingService.report(makeFinding("scC", "SOFT_LOCK", "LOW", "lock", "v001"));

        // v002: scB(MEDIUM) PERSISTED；scC(HIGH) CHANGED；scD(BLOCKER) NEW
        findingService.report(makeFinding("scB", "BALANCE_ANOMALY", "MEDIUM", "hp 高", "v002"));
        findingService.report(makeFinding("scC", "SOFT_LOCK", "HIGH", "lock", "v002"));
        findingService.report(makeFinding("scD", "INVARIANT_VIOLATION", "BLOCKER", "null ref", "v002"));

        FindingRegression result = regressionService.diff("v001", "v002");
        assertThat(result.newCount()).isEqualTo(1);
        assertThat(result.fixedCount()).isEqualTo(1);
        assertThat(result.persistedCount()).isEqualTo(1);
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(result.items()).hasSize(4);

        // 验证各类存在
        Set<String> statuses = Set.of(
                result.items().get(0).status(),
                result.items().get(1).status(),
                result.items().get(2).status(),
                result.items().get(3).status());
        assertThat(statuses).contains("NEW", "FIXED", "PERSISTED", "CHANGED");
    }
}
