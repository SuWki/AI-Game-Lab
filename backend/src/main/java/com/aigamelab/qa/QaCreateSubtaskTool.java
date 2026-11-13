package com.aigamelab.qa;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.QaRoleAssignment;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * R5 §6：qa_create_subtask —— Lead 为角色创建子任务 + assignment（PENDING）。
 *
 * <p>LLM 仅提供 {role, configVersion} 意图；子 Task 创建、constraintsJson 组装、
 * assignment 落库全部由 {@link QaOrchestratorService} 确定性执行。</p>
 */
@Component
public class QaCreateSubtaskTool extends AbstractQaTool {

    private final QaOrchestratorService orchestrator;

    public QaCreateSubtaskTool(QaOrchestratorService orchestrator,
            RunRepository runRepo, TaskRepository taskRepo) {
        super(runRepo, taskRepo);
        this.orchestrator = orchestrator;
    }

    @Override
    public String name() {
        return "qa_create_subtask";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("为 QA 编排创建指定角色的子任务与角色分配（PENDING）。"
                        + " role 必填，合法值: " + QaOrchestratorService.ROLE_ORDER
                        + "。仅 QA_LEAD 可调用。",
                Map.of(
                        "role", Map.of("type", "string",
                                "description", "子角色（PLAYER_NOVICE/PLAYER_AGGRESSIVE/"
                                        + "PLAYER_BUILD_SPECIALIST/COMBAT_ANALYST/"
                                        + "NUMERICAL_ANALYST/EXPLOIT_HUNTER/UX_REVIEWER）"),
                        "configVersion", Map.of("type", "string",
                                "description", "被测 ConfigVersion（如 v001）")),
                "role", "configVersion");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        String role = requireText(args, "role");
        String configVersion = requireText(args, "configVersion");
        LeadContext lc = leadContext(ctx);
        QaRoleAssignment a = orchestrator.createSubtask(lc.leadTask(), lc.leadRun(), role, configVersion);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "PENDING");
        out.put("subtaskId", a.subTaskId());
        out.put("assignmentId", a.id());
        out.put("role", a.role());
        out.put("orderNo", a.orderNo());
        return ok(out);
    }
}
