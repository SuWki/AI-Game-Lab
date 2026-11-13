package com.aigamelab.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P2-5 验收测试（03 文档）：空库启动后 prompt_version 有 p001 且 is_active=1；
 * 再次启动不重复导入（第二次用独立 context 复用同一 DB 模拟进程重启）。
 *
 * T4-1 追加：tester_p001 以 inactive 种子共存，不抢 active 位、不重复导入；
 * R0-2 追加：research_p001 以 inactive 种子共存，不抢 active 位、不重复导入；
 * R2-1 追加：designer_p002 以 inactive 种子共存，不抢 active 位、不重复导入；
 * QA P1-1 追加：qa_p001 以 inactive 种子共存，不抢 active 位、不重复导入；
 * 故总行为 p001 + tester_p001 + research_p001 + designer_p002 + qa_p001 五行，其中恰一条 active（p001）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P2-5 Prompt 版本加载")
class PromptServiceTest {

    @Autowired PromptService promptService;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p25-", ".db");
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
        registry.add("spring.datasource.url", PromptServiceTest::dbUrl);
    }

    @Test
    void seedImportedOnceAndActiveAcrossRestarts() {
        // ---- 第一次"启动"（本 context 启动时已执行 importSeedIfEmpty + importTesterSeedIfAbsent）----
        var active = promptService.getActive();
        assertThat(active.version()).isEqualTo("p001");
        assertThat(active.active()).isTrue();
        assertThat(active.systemPrompt()).contains("AI Game Designer");
        assertThat(active.systemPrompt()).contains("simulate_game");
        assertThat(activeCount()).isEqualTo(1); // 恰一条 active
        assertThat(count()).isEqualTo(14); // 5 原有 + QA Phase 2 8 个角色 prompt（R5 §8.2）

        // ---- 第二次"启动"：独立 context 复用同一 DB → 不重复导入 ----
        SpringApplication app = new SpringApplication(com.aigamelab.Application.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext ctx2 = app.run(
                "--spring.datasource.url=" + dbUrl(),
                "--spring.sql.init.mode=always")) {
            PromptService svc2 = ctx2.getBean(PromptService.class);
            var active2 = svc2.getActive();
            assertThat(active2.version()).isEqualTo("p001");
            assertThat(activeCount()).isEqualTo(1); // 不重复导入、active 不变量保持
            assertThat(count()).isEqualTo(14);        // 5 原有 + QA Phase 2 8 角色 prompt（R5 §8.2）
        }
    }

    private int count() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM prompt_version", Integer.class);
        return n == null ? 0 : n;
    }

    private int activeCount() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM prompt_version WHERE is_active=1", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void getByVersionFindsSeededPrompt() {
        var found = promptService.getByVersion("p001");
        assertThat(found).isPresent();
        assertThat(found.get().active()).isTrue();
        assertThat(promptService.getByVersion("p999")).isEmpty();
    }

    @Test
    @DisplayName("R2-1：designer_p002 以 inactive 种子导入，内容含研究增强流程标记")
    void designerP002SeededInactiveAndResearchAware() {
        var p002 = promptService.getByVersion("designer_p002").orElseThrow();
        assertThat(p002.version()).isEqualTo("designer_p002");
        assertThat(p002.active()).isFalse(); // 不抢 designer p001 的 active 位
        assertThat(p002.systemPrompt()).contains("研究增强工作流");
        assertThat(p002.systemPrompt()).contains("get_research_context");
        assertThat(p002.systemPrompt()).contains("get_research_claims");
        assertThat(p002.systemPrompt()).contains("researchSourceTaskId");
    }

    @Test
    void seededContentMatchesBlueprintSection18() {
        List<String> markers = jdbc.queryForObject(
                "SELECT system_prompt FROM prompt_version WHERE version='p001'",
                (rs, i) -> List.of(
                        rs.getString(1).contains("Observe") ? "Observe" : "MISSING",
                        rs.getString(1).contains("create_change_proposal") ? "proposal" : "MISSING",
                        rs.getString(1).contains("targets") ? "targets" : "MISSING"));
        assertThat(markers).doesNotContain("MISSING");
    }
}
