package com.aigamelab.tool;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * P3-2 读类工具：get_game_design（03 文档 P3-2 / 02 文档 §11 #1）。
 *
 * <p>读取 classpath 静态游戏设计文档（PROJECT ORBITAL 设定/目标/三 Build 设计意图）。
 * 纯静态资源读取，不依赖任何服务；READ 权限、无需审批。
 */
@Component
public class GetGameDesignTool implements GameTool {

    private static final String RESOURCE = "docs/game_design.md";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "get_game_design";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        schema.put("required", List.of());
        return schema;
    }

    @Override
    public boolean requiresApproval() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String markdown = readMarkdown();
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("content", markdown);
        return ToolResult.success(payload.toString(),
                "游戏设计文档（PROJECT ORBITAL）：" + markdown.length() + " 字符已读取");
    }

    private String readMarkdown() {
        try (var in = new ClassPathResource(RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ToolException("读取游戏设计文档失败: " + RESOURCE + " (" + e.getMessage() + ")");
        }
    }
}
