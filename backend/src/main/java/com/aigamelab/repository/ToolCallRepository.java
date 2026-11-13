package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.ToolCall;

/**
 * tool_call 表仓储（蓝图 §7 接口清单）。
 */
public interface ToolCallRepository {

    /** 插入 STARTED 行并回填 id。 */
    ToolCall insert(ToolCall call);

    Optional<ToolCall> findById(long id);

    List<ToolCall> findByStep(long stepId);

    /** 按 id 升序返回该 Run 全部工具调用（Run Trace 时间线）。 */
    List<ToolCall> findByRun(long runId);

    /** 状态推进：COMPLETED 时回写 resultJson/endedAt；FAILED 时回写 error/endedAt。 */
    void updateStatus(long id, String status, String resultJson, String error);
}
