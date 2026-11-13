package com.aigamelab.tester;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.aigamelab.tool.GameTool;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolException;
import com.aigamelab.tool.ToolResult;

/**
 * T3-2：Tester Tool 公共基类。
 *
 * 仍是 {@link GameTool}（无平行契约体系）；只收敛三件样板事：
 * ObjectMapper、OpenAI function schema 组装、异常 → ToolResult 统一翻译
 * （IllegalArgumentException / UnknownSessionException / BusinessException
 *   → 非 critical ToolException → tool_call FAILED + observation 回传 LLM，Run 继续）。
 * 业务逻辑全部在 Service 层——Tool 只做参数提取与结果序列化。
 */
abstract class AbstractTesterTool implements GameTool {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 蓝图 §11 模式：输入/输出明确的 function schema 组装 */
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
        return false; // MVP 全部无需审批（技术设计 §4）
    }

    @Override
    public final ToolResult execute(JsonNode args, ToolContext ctx) {
        try {
            return doExecute(args, ctx);
        } catch (ToolException e) {
            throw e; // 已是契约异常，交 ToolExecutor 翻译
        } catch (com.aigamelab.common.BusinessException e) {
            // 业务校验失败（40401/40901/40001）→ 非致命工具失败
            throw new ToolException("[" + e.getCode() + "] " + e.getMessage());
        } catch (com.aigamelab.gameenv.UnknownSessionException e) {
            throw new ToolException("未知 sessionId: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ToolException("非法参数: " + e.getMessage());
        }
    }

    /** 子类实现：参数已可信提取前的原始入口。 */
    protected abstract ToolResult doExecute(JsonNode args, ToolContext ctx);

    // ---- 参数提取小工具 ----

    protected static String textArg(JsonNode args, String key, String dflt) {
        JsonNode n = args.get(key);
        return n == null || n.isNull() ? dflt : n.asText();
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

    protected static int intArgOrDefault(JsonNode args, String key, int dflt) {
        JsonNode n = args.get(key);
        return n == null || n.isNull() ? dflt : n.asInt();
    }

    protected static ArrayNode requireArray(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || !n.isArray() || n.isEmpty()) {
            throw new IllegalArgumentException("缺少必填数组参数: " + key);
        }
        return (ArrayNode) n;
    }
}
