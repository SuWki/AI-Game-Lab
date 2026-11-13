package com.aigamelab.llm.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 蓝图 §10 冻结契约。role∈{system,user,assistant,tool}。
 * toolCalls 仅 assistant 消息携带；toolCallId 仅 role=tool 时携带。
 * 序列化为 OpenAI 兼容线协议形状（tool_calls / tool_call_id），null 字段省略。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmMessage(
        String role,
        String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, null);
    }

    /** assistant 请求工具调用 */
    public static LlmMessage assistantToolCalls(List<ToolCall> calls) {
        return new LlmMessage("assistant", null, List.copyOf(calls), null);
    }

    /** tool 结果消息（observation 回传） */
    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage("tool", content, null, toolCallId);
    }
}
