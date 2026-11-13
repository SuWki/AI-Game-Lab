package com.aigamelab.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 蓝图 §10 冻结契约：token 用量（AgentLoop 累加进 Step/Run）。
 * DeepSeek usage 含 prompt_cache_hit_tokens/prompt_cache_miss_tokens；
 * 反序列化需容忍未知字段（不同 provider 差异）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens,
        Integer promptCacheHitTokens,
        Integer promptCacheMissTokens) {

    public static TokenUsage of(int promptTokens, int completionTokens) {
        return new TokenUsage(promptTokens, completionTokens,
                promptTokens + completionTokens, null, null);
    }
}
