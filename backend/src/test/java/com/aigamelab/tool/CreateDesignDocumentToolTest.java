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
 * P3-4 工具 #4：create_design_document 验收。
 * 经真实 Spring 上下文 + DesignDocumentRepository。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("P3-4 create_design_document 工具")
class CreateDesignDocumentToolTest {

    @Autowired CreateDesignDocumentTool tool;
    @Autowired JdbcTemplate jdbc;

    static Path tempDb() {
        try { return Files.createTempFile("agl-p34-doc-", ".db"); }
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
        jdbc.update("DELETE FROM design_document");
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

    @Test
    void nameAndSchema() {
        assertThat(tool.name()).isEqualTo("create_design_document");
        assertThat(tool.requiresApproval()).isFalse();
        Map<String, Object> schema = tool.jsonSchema();
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(((Map<?, ?>) schema.get("properties")).get("title")).isNotNull();
        assertThat(((Map<?, ?>) schema.get("properties")).get("content")).isNotNull();
    }

    @Test
    void createDocument() throws Exception {
        ToolResult r = tool.execute(args(Map.of("title", "平衡性报告", "content", "# 报告\n\n这是测试文档")), ctx());
        assertThat(r.status()).isEqualTo("COMPLETED");
        JsonNode p = new ObjectMapper().readValue(r.payloadJson(), JsonNode.class);
        assertThat(p.get("documentId").asLong()).isPositive();
        assertThat(p.get("title").asText()).isEqualTo("平衡性报告");
        assertThat(r.summaryText()).contains("平衡性报告");
        // Verify persisted
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM design_document WHERE title='平衡性报告'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void missingTitleThrows() {
        assertThatThrownBy(() -> tool.execute(args(Map.of("content", "内容")), ctx()))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void missingContentThrows() {
        assertThatThrownBy(() -> tool.execute(args(Map.of("title", "标题")), ctx()))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void nullArgsThrows() {
        assertThatThrownBy(() -> tool.execute(null, ctx()))
                .isInstanceOf(ToolException.class);
    }
}
