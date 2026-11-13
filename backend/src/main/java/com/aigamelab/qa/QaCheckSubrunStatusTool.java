package com.aigamelab.qa;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * R5 §6 / §11：qa_check_subrun_status —— Lead 轮询子 Run 状态（纯只读）。
 *
 * <p><b>poll_seq（R5 §11，必填）</b>：每次轮询必须携带递增的 pollSeq，
 * 使 (tool_name, argsJson) 三元组各不相同，规避 LoopDetector WINDOW=3
 * 对「连续三次完全相同参数」的误判。服务端对 pollSeq 仅做合法性校验，不存储。</p>
 *
 * <p>观察子 Run 终态时由 {@link QaOrchestratorService} 回写 assignment
 * （freeze §8 A7：以 run 行最新状态为准）。</p>
 */
@Component
public class QaCheckSubrunStatusTool extends AbstractQaTool {

    private final QaOrchestratorService orchestrator;

    public QaCheckSubrunStatusTool(QaOrchestratorService orchestrator,
            RunRepository runRepo, TaskRepository taskRepo) {
        super(runRepo, taskRepo);
        this.orchestrator = orchestrator;
    }

    @Override
    public String name() {
        return "qa_check_subrun_status";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("轮询子 Run 状态（只读）。pollSeq 必须逐次递增（1,2,3,...），"
                        + "禁止用相同 pollSeq 连续轮询，否则触发无效循环检测。",
                Map.of(
                        "subRunId", Map.of("type", "integer",
                                "description", "qa_start_subrun 返回的 subRunId"),
                        "pollSeq", Map.of("type", "integer",
                                "description", "递增轮询序号（1,2,3,...）")),
                "subRunId", "pollSeq");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        long subRunId = requireLong(args, "subRunId");
        long pollSeq = requireLong(args, "pollSeq");
        if (pollSeq <= 0) {
            throw new IllegalArgumentException("pollSeq 必须为正整数");
        }
        LeadContext lc = leadContext(ctx);
        Run subRun = orchestrator.checkSubrunStatus(lc.leadTask(), subRunId);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("subRunId", subRunId);
        out.put("status", subRun.status());
        out.put("currentStep", subRun.currentStep());
        out.put("tokensUsed", subRun.tokensUsed());
        if (subRun.finalResult() != null) {
            out.put("resultSummary", subRun.finalResult());
        }
        if (subRun.errorMessage() != null) {
            out.put("error", subRun.errorMessage());
        }
        out.put("pollSeq", pollSeq);
        return ok(out);
    }
}
