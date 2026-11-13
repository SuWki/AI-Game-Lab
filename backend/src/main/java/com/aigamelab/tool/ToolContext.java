package com.aigamelab.tool;

/**
 * 蓝图 §11 冻结契约：工具执行上下文（ctx 带 runId/stepId）。
 */
public record ToolContext(Long runId, Long stepId) {

    public static ToolContext of(Long runId, Long stepId) {
        return new ToolContext(runId, stepId);
    }
}
