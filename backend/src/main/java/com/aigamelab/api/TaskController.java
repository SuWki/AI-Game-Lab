package com.aigamelab.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aigamelab.common.ApiResp;
import com.aigamelab.common.BusinessException;
import com.aigamelab.common.ErrorCodes;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.event.PersistingAgentEventPublisher;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.AgentType;
import com.aigamelab.runtime.RunControlService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * R2-3：Task 生命周期端点（用户驱动跨 Agent 工作流）。
 *
 * <p>在既有 T6-1 Run 启动端点基础上，补齐 Task 创建与列表：
 * <ul>
 *   <li>POST /api/tasks — 创建 Task（title/goal 必填；agentType 决定 constraintsJson.agent_type；
 *       可选 researchSourceTaskId 用于「以该研究发起 Designer」，不复制 ID、前端只传引用）；</li>
 *   <li>GET /api/tasks — Task 列表（供工作流页展示 + 驱动 Run 启动/状态）</li>
 *   <li>POST /api/tasks/{id}/runs — 既有端点（T6-1），从 Task 启动 Run</li>
 * </ul>
 *
 * <p>不引入 parent_run_id/child_run_id/workflow/DAG 等编排结构；
 * 研究→设计链靠 Task 级 researchSourceTaskId 引用 + 可追溯产物，
 * 由人工在 UI 触发，非自主编排。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunControlService runControl;
    private final TaskRepository taskRepo;
    private final PersistingAgentEventPublisher events;

    public TaskController(RunControlService runControl, TaskRepository taskRepo,
            PersistingAgentEventPublisher events) {
        this.runControl = runControl;
        this.taskRepo = taskRepo;
        this.events = events;
    }

    /**
     * R2-3：创建 Task。
     * 请求体：{title, goal, agentType, researchSourceTaskId?, maxSteps?}
     * - agentType 必须为 DESIGNER/TESTER/RESEARCH/QA/QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER，
     *   写入 constraintsJson.agent_type（QA 任务显式指定 agent_type=QA，由 Resolver 路由到 AgentType.QA；
     *   R5 Phase 2 新增 4 个 QA 团队类型）；
     * - researchSourceTaskId 仅允许 DESIGNER 任务携带（研究→设计链），并校验引用任务存在；
     * - maxSteps 默认 50；approvalRequired 默认 false。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResp<Task> createTask(@RequestBody Map<String, Object> body) {
        String title = asText(body.get("title"));
        String goal = asText(body.get("goal"));
        String agentType = asText(body.get("agentType"));
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT, "title 必填");
        }
        if (goal == null || goal.isBlank()) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT, "goal 必填");
        }
        if (agentType == null || agentType.isBlank()) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "agentType 必填 (DESIGNER/TESTER/RESEARCH/QA/QA_LEAD/QA_TESTER/QA_ANALYST/QA_RESEARCHER)");
        }

        AgentType type;
        try {
            type = AgentType.valueOf(agentType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "agent_type 值非法: " + agentType
                            + "，允许值: DESIGNER, TESTER, RESEARCH, QA, "
                            + "QA_LEAD, QA_TESTER, QA_ANALYST, QA_RESEARCHER");
        }

        Long researchSourceTaskId = asLong(body.get("researchSourceTaskId"));
        if(type==AgentType.BUILDER)throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,"Create Builder tasks from a fixed production specification");
        if (researchSourceTaskId != null && type != AgentType.DESIGNER) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "researchSourceTaskId 仅允许 DESIGNER 任务携带（研究→设计链引用）");
        }
        if (researchSourceTaskId != null) {
            Task refTask = taskRepo.findById(researchSourceTaskId).orElseThrow(
                    () -> BusinessException.notFound("引用的研究任务不存在: " + researchSourceTaskId));
            // TI：引用任务必须是 RESEARCH 类型
            String refAgentType = extractAgentType(refTask.constraintsJson());
            if (!"RESEARCH".equals(refAgentType)) {
                throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                        "researchSourceTaskId 必须指向 RESEARCH 类型任务，实际类型: " + refAgentType);
            }
        }

        int maxSteps = defaultMaxSteps(body);
        String constraintsJson = buildConstraints(agentType, researchSourceTaskId);

        Task created = taskRepo.insert(new Task(
                null, title, goal, constraintsJson, maxSteps, false, "ACTIVE", null));

        // R2-2/R2-3：任务创建即发布（runId=null，走 /sse/global 全局流），工作流页实时可见
        ObjectNode ev = MAPPER.createObjectNode();
        ev.put("taskId", created.id());
        ev.put("title", title);
        ev.put("agentType", agentType);
        if (researchSourceTaskId != null) {
            ev.put("researchSourceTaskId", researchSourceTaskId);
        }
        events.publish("TASK_CREATED", null, created.id(), ev.toString());

        return ApiResp.ok(created);
    }

    /** R2-3：Task 列表（工作流页展示 + 驱动 Run 启动）。 */
    @GetMapping
    public ApiResp<List<Task>> listTasks() {
        return ApiResp.ok(taskRepo.findAll());
    }

    /**
     * T6-1：从 Task 启动新 Run。
     * 返回 201 + 新 Run id；Run 异步执行，状态通过 SSE 或 GET /api/runs/{id} 观测。
     */
    @PostMapping("/{id}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResp<Long> startRun(@PathVariable long id,@org.springframework.web.bind.annotation.RequestHeader(value="X-AGL-Local-Test",required=false)String marker,jakarta.servlet.http.HttpServletRequest request) {
        Task task=taskRepo.findById(id).orElseThrow(()->BusinessException.notFound("Task 不存在"));
        if(task.constraintsJson()!=null&&(task.constraintsJson().contains("\"localTargetId\"")||task.constraintsJson().contains("\"productionSpecId\"")))LocalTestController.requireLocal(marker,request);
        Run run = runControl.start(id);
        return ApiResp.ok(run.id());
    }

    /**
     * 组装 constraintsJson：{"agent_type":"X", "researchSourceTaskId":N?}。
     * researchSourceTaskId 为研究→设计链引用（非复制，仅存任务 id 引用）。
     */
    private static String buildConstraints(String agentType, Long researchSourceTaskId) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("agent_type", agentType);
        if (researchSourceTaskId != null) {
            n.put("researchSourceTaskId", researchSourceTaskId);
        }
        return n.toString();
    }

    private static String extractAgentType(String constraintsJson) {
        try {
            JsonNode node = MAPPER.readTree(constraintsJson);
            JsonNode type = node.get("agent_type");
            return type == null || type.isNull() ? "UNKNOWN" : type.asText();
        } catch (Exception e) {
            return "UNKNOWN"; // 解析失败视为未知类型
        }
    }

    private static int defaultMaxSteps(Map<String, Object> body) {
        Object v = body.get("maxSteps");
        if (v instanceof Number n) {
            return n.intValue();
        }
        return 50;
    }

    private static String asText(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(o);
        return s.isBlank() ? null : Long.parseLong(s);
    }
}
