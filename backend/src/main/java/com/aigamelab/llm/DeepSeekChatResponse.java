package com.aigamelab.llm;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 蓝图 §10：DeepSeek /chat/completions 响应内部 DTO。
 * 与对外 LlmResponse 解耦——换 provider 只改这一层（P2-2）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeepSeekChatResponse(
        String id,
        String model,
        List<Choice> choices,
        Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            Integer index,
            Message message,
            String finish_reason) {
    }

    /** 响应方向消息：tool_calls 为 DeepSeek 线协议形状 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            String role,
            String content,
            List<WireToolCall> tool_calls,
            String reasoning_content) {
    }

    /** OpenAI function-calling 形状 {id, type, function:{name, arguments}} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WireToolCall(
            String id,
            String type,
            FunctionBody function) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record FunctionBody(String name, String arguments) {
        }
    }

    /** DeepSeek usage：snake_case + 缓存命中字段 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            Integer prompt_tokens,
            Integer completion_tokens,
            Integer total_tokens,
            Integer prompt_cache_hit_tokens,
            Integer prompt_cache_miss_tokens) {
    }
}
