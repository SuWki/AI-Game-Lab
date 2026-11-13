package com.aigamelab.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aigamelab.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P3-1 验收测试（03 文档）：注册 8 个后 size()==8；未知名 dispatch 抛 BusinessException；
 * 重名导致启动失败（构造抛 IllegalStateException）；dispatch 正常转发；
 * 另单测 JSON Schema 校验（合法参数通过、非法参数/缺 required 被拦截）。
 */
@DisplayName("P3-1 ToolRegistry + Schema 校验")
class ToolRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private GameTool tool(String name, Map<String, Object> schema, boolean approval) {
        return new GameTool() {
            @Override public String name() { return name; }
            @Override public Map<String, Object> jsonSchema() { return schema; }
            @Override public boolean requiresApproval() { return approval; }
            @Override public ToolResult execute(JsonNode a, ToolContext c) {
                return ToolResult.success("{}", "ok");
            }
        };
    }

    private Map<String, Object> schema(String... required) {
        return Map.of("type", "object",
                "properties", Map.of("x", Map.of("type", "string")),
                "required", List.of(required));
    }

    @Test
    void registersAllAndReportsSize() {
        ToolRegistry reg = new ToolRegistry(List.of(
                tool("a", schema(), false), tool("b", schema(), false),
                tool("c", schema(), false), tool("d", schema(), false),
                tool("e", schema(), false), tool("f", schema(), false),
                tool("g", schema(), false), tool("h", schema(), false)));
        assertThat(reg.size()).isEqualTo(8);
        assertThat(reg.contains("a")).isTrue();
        assertThat(reg.contains("z")).isFalse();
    }

    @Test
    void duplicateNameFailsAtConstruction() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(
                tool("dup", schema(), false), tool("dup", schema(), false))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("重复的工具名");
    }

    @Test
    void unknownToolThrowsBusinessException() {
        ToolRegistry reg = new ToolRegistry(List.of(tool("known", schema(), false)));
        assertThatThrownBy(() -> reg.dispatch("nope", mapper.createObjectNode(),
                ToolContext.of(1L, 1L)))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(40401));
    }

    @Test
    void dispatchForwardsToTool() {
        ToolRegistry reg = new ToolRegistry(List.of(tool("known", schema(), false)));
        ToolResult r = reg.dispatch("known", mapper.createObjectNode(), ToolContext.of(1L, 1L));
        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(reg.get("known")).isNotNull();
    }

    @Test
    void schemaValidationRejectsMissingRequired() {
        var errors = JsonSchemaValidator.validate(
                schema("x"), mapper.createObjectNode()); // 缺 x
        assertThat(errors).anyMatch(e -> e.contains("x"));
    }

    @Test
    void schemaValidationRejectsWrongType() {
        var args = mapper.createObjectNode().put("x", 42); // x 应为 string
        var errors = JsonSchemaValidator.validate(schema("x"), args);
        assertThat(errors).anyMatch(e -> e.contains("x") && e.contains("类型"));
    }

    @Test
    void schemaValidationPassesValidArgs() {
        var args = mapper.createObjectNode().put("x", "hello");
        assertThat(JsonSchemaValidator.validate(schema("x"), args)).isEmpty();
    }

    @Test
    void schemaValidationEmptySchemaSkips() {
        assertThat(JsonSchemaValidator.validate(Map.of(), mapper.createObjectNode())).isEmpty();
    }
}
