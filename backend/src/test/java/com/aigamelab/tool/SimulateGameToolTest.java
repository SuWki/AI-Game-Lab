package com.aigamelab.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.aigamelab.experiment.ExperimentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P3-4 工具 #1：simulate_game 验收。
 * 经真实 Spring 上下文 + ExperimentService（P5-1 同步落库）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P3-4 simulate_game 工具")
class SimulateGameToolTest {

    @Autowired SimulateGameTool tool;
    @Autowired ExperimentService experimentService;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try {
            return Files.createTempFile("agl-p34-sim-", ".db");
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
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
        // Insert task + run so experiment FK constraints pass
        jdbc.update("INSERT INTO task(id, title, goal, constraints_json) VALUES(1, 'test', 'test', '{}')");
        jdbc.update("INSERT INTO run(id, task_id, status, model, current_step, max_steps, token_budget, tokens_used) VALUES(1, 1, 'RUNNING', 'deepseek-chat', 0, 30, 200000, 0)");
    }

    private JsonNode args(Map<String, Object> m) {
        try {
            return new ObjectMapper().valueToTree(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ToolContext ctx() {
        return ToolContext.of(1L, 1L);
    }

    private JsonNode parse(String json) {
        try {
            return new ObjectMapper().readValue(json, JsonNode.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void nameAndSchema() {
        assertThat(tool.name()).isEqualTo("simulate_game");
        assertThat(tool.requiresApproval()).isFalse();
        Map<String, Object> schema = tool.jsonSchema();
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat((Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("count")).isNotNull();
    }

    @Test
    void normalParamsCreatesExperiment() {
        ToolResult r = tool.execute(args(Map.of("seed", 12345L, "count", 100, "scenario", "crit")), ctx());
        assertThat(r.status()).isEqualTo("COMPLETED");
        JsonNode p = parse(r.payloadJson());
        assertThat(p.get("experimentId").asLong()).isPositive();
        assertThat(p.get("reused").asBoolean()).isFalse();
        assertThat(p.get("status").asText()).isEqualTo("DONE");
        assertThat(p.get("winRate").asDouble()).isBetween(0.0, 1.0);
        assertThat(r.summaryText()).contains("实验完成");
    }

    @Test
    void nullScenarioAggregatesAllBuilds() {
        ToolResult r = tool.execute(args(Map.of("seed", 999L, "count", 80)), ctx());
        assertThat(r.status()).isEqualTo("COMPLETED");
        JsonNode p = parse(r.payloadJson());
        assertThat(p.get("scenario").asText()).isEqualTo("ALL_BUILDS");
    }

    @Test
    void idempotentSameParamsReuses() {
        Map<String, Object> a = Map.of("seed", 555L, "count", 60, "scenario", "penetration");
        ToolResult r1 = tool.execute(args(a), ctx());
        ToolResult r2 = tool.execute(args(a), ctx());
        JsonNode p1 = parse(r1.payloadJson());
        JsonNode p2 = parse(r2.payloadJson());
        assertThat(p2.get("reused").asBoolean()).isTrue();
        assertThat(p2.get("experimentId").asLong()).isEqualTo(p1.get("experimentId").asLong());
    }

    @Test
    void missingCountThrows() {
        assertThatThrownBy(() -> tool.execute(args(Map.of("seed", 1L)), ctx()))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void countExceedsLimitThrows() {
        assertThatThrownBy(() -> tool.execute(
                args(Map.of("seed", 1L, "count", 100001)), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("上限");
    }

    @Test
    void zeroCountThrows() {
        assertThatThrownBy(() -> tool.execute(
                args(Map.of("seed", 1L, "count", 0)), ctx()))
                .isInstanceOf(ToolException.class);
    }
}
