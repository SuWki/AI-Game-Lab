package com.aigamelab.runtime;

import org.springframework.stereotype.Component;

import com.aigamelab.repository.StepRepository;

/**
 * P1-5：无效循环检测（蓝图 §9.6 第四重防线）。
 * 从 DB 取最近 3 次 ToolCall，(toolName|argumentsJson) 连续相同即判无效循环。
 * 基于 DB 派生，进程重启后仍有效。
 */
@Component
public class LoopDetector {

    private static final int WINDOW = 3;

    private final StepRepository stepRepo;

    public LoopDetector(StepRepository stepRepo) {
        this.stepRepo = stepRepo;
    }

    /**
     * 最近 3 次 ToolCall 是否全部相同（次数 < WINDOW 不可能构成循环）。
     */
    public boolean detect(long runId, String lastToolName, String lastArgsJson) {
        if (lastToolName == null) {
            return false;
        }
        return stepRepo.countIdenticalRecentToolCalls(
                runId, lastToolName, lastArgsJson, WINDOW) >= WINDOW;
    }
}
