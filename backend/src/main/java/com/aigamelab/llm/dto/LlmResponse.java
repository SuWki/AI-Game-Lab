package com.aigamelab.llm.dto;

import java.util.List;

/**
 * 蓝图 §10 冻结契约：LLM 响应。
 *
 * @param finishReason stop / tool_calls / length
 */
public record LlmResponse(
        String requestId,
        String finishReason,
        String content,
        List<ToolCall> toolCalls,
        TokenUsage usage,
        long latencyMs,
        String rawModelId) {

    public static LlmResponse stop(String requestId, String content) {
        return new LlmResponse(requestId, "stop", content, null, null, 0, "fake");
    }

    public static LlmResponse toolCalls(String requestId, List<ToolCall> calls) {
        return new LlmResponse(requestId, "tool_calls", null, List.copyOf(calls), null, 0, "fake");
    }

    public static LlmResponse length(String requestId, String content) {
        return new LlmResponse(requestId, "length", content, null, null, 0, "fake");
    }
}
