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
 * R5 §6：qa_start_subrun —— Lead 依序启动子 Run（普通 run 行，RUNNING）。
 *
 * <p>子 Run 创建、prompt/tools 注入、回填 sub_run_id 全部由
 * {@link QaOrchestratorService} 确定性执行；本工具只解析 {subTaskId} 意图。</p>
 */
@Component
public class QaStartSubrunTool extends AbstractQaTool {

    private final QaOrchestratorService orchestrator;

    public QaStartSubrunTool(QaOrchestratorService orchestrator,
            RunRepository runRepo, TaskRepository taskRepo) {
        super(runRepo, taskRepo);
        this.orchestrator = orchestrator;
    }

    @Override
    public String name() {
        return "qa_start_subrun";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("启动指定子任务的子 Run（经 qa_create_subtask 返回的 subtaskId）。"
                        + " 仅 QA_LEAD 可调用；deadline 超时后拒绝启动。",
                Map.of("subTaskId", Map.of("type", "integer",
                        "description", "qa_create_subtask 返回的子任务 id")),
                "subTaskId");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        long subTaskId = requireLong(args, "subTaskId");
        LeadContext lc = leadContext(ctx);
        Run subRun = orchestrator.startSubrun(lc.leadTask(), subTaskId);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", subRun.status());
        out.put("subRunId", subRun.id());
        out.put("currentStep", subRun.currentStep());
        return ok(out);
    }
}
