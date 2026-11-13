package com.aigamelab.llm.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 蓝图 §10 冻结契约：发往 LLM 的请求。
 * 线协议形状：model/messages/tools/temperature/max_tokens（snake_case）；
 * requestId/runId/promptVersion 为 AGL 内部追溯字段，不进线协议。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmRequest(
        @JsonIgnore String requestId,
        @JsonIgnore Long runId,
        String model,
        List<LlmMessage> messages,
        List<LlmToolSpec> tools,
        Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonIgnore String promptVersion) {
}
