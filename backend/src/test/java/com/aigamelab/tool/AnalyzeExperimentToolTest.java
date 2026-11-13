package com.aigamelab.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P3-4 工具 #2：analyze_experiment 验收。
 * 经真实 Spring 上下文 + ExperimentRepository + EvaluationService。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P3-4 analyze_experiment 工具")
class AnalyzeExperimentToolTest {

    @Autowired AnalyzeExperimentTool tool;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try { return Files.createTempFile("agl-p34-analyze-", ".db"); }
        catch (Exception e) { throw new IllegalStateException(e); }
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
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
        jdbc.update("INSERT INTO task(id, title, goal, constraints_json) VALUES(1, 'test', 'test', '{}')");
        jdbc.update("INSERT INTO run(id, task_id, status, model, current_step, max_steps, token_budget, tokens_used) VALUES(1, 1, 'RUNNING', 'deepseek-chat', 0, 30, 200000, 0)");
    }

    private ToolContext ctx() { return ToolContext.of(1L, 1L); }
    private JsonNode args(Map<String, Object> m) {
        try { return new ObjectMapper().valueToTree(m); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private long insertExperiment(String resultJson) {
        jdbc.update("INSERT INTO experiment(run_id, config_version, seed, simulation_count, scenario, status, win_rate, result_json) VALUES(1, 'v001', 42, 1000, 'crit', 'DONE', 0.55, ?)", resultJson);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private static String buildResultJson() {
        return "{\"buildDistribution\":{"
                + "\"crit\":{\"winRate\":0.55,\"ciLow\":0.52,\"ciHigh\":0.58},"
                + "\"penetration\":{\"winRate\":0.30,\"ciLow\":0.27,\"ciHigh\":0.33},"
                + "\"attack_speed\":{\"winRate\":0.15,\"ciLow\":0.12,\"ciHigh\":0.18}"
                + "}}";
    }

    @Test
    void nameAndSchema() {
        assertThat(tool.name()).isEqualTo("analyze_experiment");
        assertThat(tool.requiresApproval()).isFalse();
        Map<String, Object> schema = tool.jsonSchema();
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(((Map<?, ?>) schema.get("properties")).get("experimentId")).isNotNull();
    }

    @Test
    void analyzeExistingExperiment() throws Exception {
        long expId = insertExperiment(buildResultJson());
        ToolResult r = tool.execute(args(Map.of("experimentId", expId)), ctx());
        assertThat(r.status()).isEqualTo("COMPLETED");
        JsonNode p = new ObjectMapper().readValue(r.payloadJson(), JsonNode.class);
        assertThat(p.get("experimentId").asLong()).isEqualTo(expId);
        assertThat(p.get("configVersion").asText()).isEqualTo("v001");
        assertThat(p.get("winRate").asDouble()).isEqualTo(0.55);
        assertThat(r.summaryText()).contains("crit");
        assertThat(r.summaryText()).contains("penetration");
    }

    @Test
    void analyzeNonexistentThrows() {
        assertThatThrownBy(() -> tool.execute(args(Map.of("experimentId", 999999L)), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void missingExperimentIdThrows() {
        assertThatThrownBy(() -> tool.execute(args(Map.of()), ctx()))
                .isInstanceOf(ToolException.class);
    }
}
