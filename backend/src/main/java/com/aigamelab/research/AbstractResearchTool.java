package com.aigamelab.research;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aigamelab.tool.GameTool;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolException;
import com.aigamelab.tool.ToolResult;

/**
 * R0-4：Research Tool 公共基类（镜像 AbstractTesterTool 模式）。
 *
 * 收敛样板：ObjectMapper、function schema 组装、异常 → ToolResult 统一翻译。
 * 业务逻辑全部在子类 doExecute() 中。
 */
public abstract class AbstractResearchTool implements GameTool {

    static final ObjectMapper MAPPER = new ObjectMapper();

    protected static Map<String, Object> schema(String description,
            Map<String, Object> properties, String... required) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "",
                        "description", description,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", properties,
                                "required", java.util.Arrays.asList(required))));
    }

    @Override
    public boolean requiresApproval() {
        return false; // R0-4：Research Tool 全部无需审批
    }

    @Override
    public final ToolResult execute(JsonNode args, ToolContext ctx) {
        try {
            return doExecute(args, ctx);
        } catch (ToolException e) {
            throw e; // 已是契约异常，交 ToolExecutor 翻译
        } catch (IllegalArgumentException e) {
            throw new ToolException("非法参数: " + e.getMessage());
        } catch (Exception e) {
            throw new ToolException("Research Tool 异常: " + e.getMessage());
        }
    }

    /** 子类实现：参数已可信提取前的原始入口。 */
    protected abstract ToolResult doExecute(JsonNode args, ToolContext ctx);

    // ---- 参数提取小工具 ----

    protected static String textArg(JsonNode args, String key, String dflt) {
        JsonNode n = args.get(key);
        return n == null || n.isNull() ? dflt : n.asText();
    }

    protected static String requireText(JsonNode args, String key) {
        String v = textArg(args, key, null);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: " + key);
        }
        return v;
    }

    protected static Long longArgOrNull(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.canConvertToLong()) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
        return n.asLong();
    }

    protected static long requireLong(JsonNode args, String key) {
        Long v = longArgOrNull(args, key);
        if (v == null) {
            throw new IllegalArgumentException("缺少必填整数参数: " + key);
        }
        return v;
    }

    protected static double doubleArgOrDefault(JsonNode args, String key, double dflt) {
        JsonNode n = args.get(key);
        return n == null || n.isNull() ? dflt : n.asDouble();
    }
}
