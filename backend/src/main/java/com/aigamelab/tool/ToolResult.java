package com.aigamelab.tool;

/**
 * 蓝图 §11 冻结契约：工具执行结果。
 *
 * @param payloadJson 结果 JSON（写 tool_call.result_json）
 * @param summaryText 回传 LLM 的紧凑文本摘要
 */
public record ToolResult(String status, String payloadJson, String summaryText) {

    public static ToolResult success(String payloadJson, String summaryText) {
        return new ToolResult("COMPLETED", payloadJson, summaryText);
    }

    public static ToolResult failure(String error) {
        return new ToolResult("FAILED", null, error);
    }
}
