package com.aigamelab.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T4-1 验收：tester_p001 经现有 PromptService 机制导入。
 * 冻结契约（技术设计 §8）逐字复制，不自行扩充；与设计师 p001 共存互不破坏。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("T4-1 tester_p001 Prompt 导入")
class TesterPromptImportTest {

    @Autowired PromptService promptService;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-t41-", ".db");
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
        registry.add("spring.datasource.url", TesterPromptImportTest::dbUrl);
    }

    /** 冻结 Prompt 必须包含的设计 §8 标记（逐字核对，不扩充） */
    private static final List<String> FROZEN_MARKERS = List.of(
            "AI Game Tester",
            "PLAN",
            "SETUP",
            "SCENARIO_LOOP",
            "ANALYZE",
            "REPORT",
            "test_plan_get",
            "game_start",
            "game_observe",
            "invariantViolations",
            "finding_report",
            "test_report_create");

    @Test
    void testerPromptImportedAndInactive() {
        var tester = promptService.getByVersion("tester_p001").orElseThrow();
        assertThat(tester.version()).isEqualTo("tester_p001");
        assertThat(tester.active()).isFalse(); // 默认 inactive，不抢设计师 p001 的生效位
        assertThat(tester.systemPrompt()).isNotEmpty();
    }

    @Test
    void testerPromptContentMatchesFrozenDesignSection8() {
        var tester = promptService.getByVersion("tester_p001").orElseThrow();
        for (String marker : FROZEN_MARKERS) {
            assertThat(tester.systemPrompt()).contains(marker);
        }
    }

    @Test
    void designerPromptRemainsActiveAndUntouched() {
        // 设计师 p001 仍为唯一 active 版本（active 不变量不被破坏）
        var active = promptService.getActive();
        assertThat(active.version()).isEqualTo("p001");
        assertThat(active.active()).isTrue();
        assertThat(active.systemPrompt()).contains("AI Game Designer");
    }

    @Test
    void bothPromptsPresentAndExactlyOneActive() {
        assertThat(countRows()).isEqualTo(14); // 5 原有 + QA Phase 2 8 个角色 prompt（R5 §8.2）
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM prompt_version WHERE is_active=1", Integer.class);
        assertThat(activeCount).isEqualTo(1); // 全表至多一条 active
    }

    @Test
    void importIsIdempotentAcrossRestarts() {
        // 复用同一 DB 二次启动，tester_p001 不重复插入
        SpringApplication app = new SpringApplication(com.aigamelab.Application.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext ctx2 = app.run(
                "--spring.datasource.url=" + dbUrl(),
                "--spring.sql.init.mode=always")) {
            PromptService svc2 = ctx2.getBean(PromptService.class);
            assertThat(svc2.getByVersion("tester_p001")).isPresent();
            assertThat(countRows()).isEqualTo(14); // 仍仅 13 行，无重复（5 原有 + QA Phase 2 8 角色）
        }
    }

    private int countRows() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM prompt_version", Integer.class);
        return n == null ? 0 : n;
    }
}
