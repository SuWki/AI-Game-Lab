package com.aigamelab.repository;

import java.util.Optional;

import com.aigamelab.domain.Step;

/**
 * step 表仓储（蓝图 §7 接口清单）。
 */
public interface StepRepository {

    /** 插入并回填 id/createdAt。UNIQUE(run_id,seq) 冲突抛 DuplicateKeyException。 */
    Step insert(Step step);

    Optional<Step> findById(long id);

    /** 按 seq 升序返回该 Run 全部 Step。 */
    java.util.List<Step> findByRunId(long runId);

    /**
     * P1-2：LLM 响应到达后回填 token 用量/内容/耗时。
     * 仅 UPDATE 指定列，不触碰 seq/run_id 等键列。
     */
    void updateLlmResult(long id, String phase, String llmContent,
            long promptTokens, long completionTokens, Long durationMs);

    /**
     * P1-5：最近 window 次 ToolCall 中，与 (toolName,argsJson) 完全相同的次数。
     * 用于无效循环检测（蓝图 §9.6 第四重防线）。
     */
    int countIdenticalRecentToolCalls(long runId, String toolName,
            String argumentsJson, int window);
}
