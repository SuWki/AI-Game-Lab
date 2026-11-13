package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * 工具调用（tool_call 表）。字段与蓝图 §9.1 一致。
 * 一个 Step 可含 0~N 个 ToolCall。status: STARTED/COMPLETED/FAILED；
 * approvalStatus: NOT_REQUIRED/PENDING/APPROVED/REJECTED。
 */
public record ToolCall(
        Long id,
        long stepId,
        long runId,
        String toolName,
        String argumentsJson,
        String resultJson,
        String status,
        String approvalStatus,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        String error) {
}
