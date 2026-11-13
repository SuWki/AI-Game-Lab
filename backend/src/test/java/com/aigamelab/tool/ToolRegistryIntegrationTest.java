package com.aigamelab.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P3-4 Registry 集成验收：验证 4 个新工具已注册到 ToolRegistry，
 * 无重名、无 Tester 泳道污染、总数正确。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P3-4 ToolRegistry 集成验收")
class ToolRegistryIntegrationTest {

    @Autowired ToolRegistry registry;

    static Path tempDb() {
        try { return Files.createTempFile("agl-p34-reg-", ".db"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static final Path DB = tempDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:"
                + DB.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=ON");
    }

    @Test
    void p34ToolsRegistered() {
        assertThat(registry.contains("simulate_game")).isTrue();
        assertThat(registry.contains("analyze_experiment")).isTrue();
        assertThat(registry.contains("compare_experiments")).isTrue();
        assertThat(registry.contains("create_design_document")).isTrue();
    }

    @Test
    void noDuplication() {
        Set<String> names = registry.all().stream()
                .map(GameTool::name)
                .collect(Collectors.toSet());
        assertThat(names.size()).isEqualTo(registry.size());
    }

    @Test
    void totalToolCount() {
        // 10 Designer tools (R2-1 +2) + 8 Tester tools + 6 Research tools + 5 QA Phase 2 tools (R5 §6) = 29
        assertThat(registry.size()).isEqualTo(37);
        assertThat(registry.contains("local_test_start")).isTrue();
        assertThat(registry.contains("local_source_read")).isTrue();
    }

    @Test
    void p34ToolsNotApprovalRequired() {
        assertThat(registry.get("simulate_game").requiresApproval()).isFalse();
        assertThat(registry.get("analyze_experiment").requiresApproval()).isFalse();
        assertThat(registry.get("compare_experiments").requiresApproval()).isFalse();
        assertThat(registry.get("create_design_document").requiresApproval()).isFalse();
    }

    @Test
    void originalToolsStillPresent() {
        // P3-1/P3-2 tools not broken by P3-4 additions
        assertThat(registry.contains("get_game_config")).isTrue();
        assertThat(registry.contains("get_game_design")).isTrue();
        assertThat(registry.contains("create_change_proposal")).isTrue();
        assertThat(registry.contains("modify_game_config")).isTrue();
    }
}
