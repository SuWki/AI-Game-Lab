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
 * P3-4 工具 #3：compare_experiments 验收。
 * 经真实 Spring 上下文 + ExperimentRepository。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P3-4 compare_experiments 工具")
class CompareExperimentsToolTest {

    @Autowired CompareExperimentsTool tool;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try { return Files.createTempFile("agl-p34-compare-", ".db"); }
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
    private JsonNode args(java.util.List<Object> ids) {
        try { return new ObjectMapper().valueToTree(Map.of("ids", ids)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private long insertExperiment(long seed, double winRate, int count) {
        jdbc.update("INSERT INTO experiment(run_id, config_version, seed, simulation_count, scenario, status, win_rate) VALUES(1, 'v001', ?, ?, 'crit', 'DONE', ?)", seed, count, winRate);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    @Test
    void nameAndSchema() {
        assertThat(tool.name()).isEqualTo("compare_experiments");
        assertThat(tool.requiresApproval()).isFalse();
        Map<String, Object> schema = tool.jsonSchema();
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(((Map<?, ?>) schema.get("properties")).get("ids")).isNotNull();
    }

    @Test
    void compareSignificantDifference() throws Exception {
        long id1 = insertExperiment(1L, 0.70, 1000);
        long id2 = insertExperiment(2L, 0.30, 1000);
        ToolResult r = tool.execute(args(java.util.List.of(id1, id2)), ctx());
        assertThat(r.status()).isEqualTo("COMPLETED");
        JsonNode p = new ObjectMapper().readValue(r.payloadJson(), JsonNode.class);
        assertThat(p.get("pairs").isArray()).isTrue();
        JsonNode pair = p.get("pairs").get(0);
        assertThat(pair.get("significant").asBoolean()).isTrue();
        assertThat(Math.abs(pair.get("zScore").asDouble())).isGreaterThan(1.96);
        assertThat(r.summaryText()).contains("显著=true");
    }

    @Test
    void compareInsignificantDifference() throws Exception {
        long id1 = insertExperiment(10L, 0.50, 1000);
        long id2 = insertExperiment(11L, 0.51, 1000);
        ToolResult r = tool.execute(args(java.util.List.of(id1, id2)), ctx());
        JsonNode p = new ObjectMapper().readValue(r.payloadJson(), JsonNode.class);
        JsonNode pair = p.get("pairs").get(0);
        assertThat(pair.get("significant").asBoolean()).isFalse();
    }

    @Test
    void tooFewIdsThrows() {
        assertThatThrownBy(() -> tool.execute(args(java.util.List.of(1L)), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("至少");
    }

    @Test
    void missingIdsThrows() {
        assertThatThrownBy(() -> tool.execute(new ObjectMapper().valueToTree(Map.of()), ctx()))
                .isInstanceOf(ToolException.class);
    }
}
