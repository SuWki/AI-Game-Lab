package com.aigamelab.qa;

import java.util.Map;

import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tool.GameTool;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolException;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * R5 §6：QA_LEAD 编排工具公共基类。
 *
 * <p>与 Tester 的 AbstractTesterTool 同构：仍是 {@link GameTool}（无平行契约体系）；
 * 只收敛样板事：ObjectMapper、OpenAI function schema 组装、异常 → ToolResult 统一翻译、
 * 以及**编排上下文解析**（由 ToolContext.runId 反查 Lead Task/Run 并校验 agent_type=QA_LEAD）。
 *
 * <p><b>权限边界（R5 §6）</b>：工具必须在合法 QA orchestration context 中调用。
 * AgentLoop 白名单（Resolver toolWhitelist switch）已保证仅 QA_LEAD 暴露本组工具
 * （非 Lead 角色绝不接触编排面，freeze §17）；本基类再做防御性复核——
 * 调用 run 所属 task 的 agent_type 必须为 QA_LEAD，否则拒绝执行。
 */
abstract class AbstractQaTool implements GameTool {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 由 AbstractQaTool 注入（Spring 构造注入到子类再传回）。 */
    protected final RunRepository runRepo;
    protected final TaskRepository taskRepo;

    AbstractQaTool(RunRepository runRepo, TaskRepository taskRepo) {
        this.runRepo = runRepo;
        this.taskRepo = taskRepo;
    }

    protected static Map<String, Object> schema(String description,
            Map<String, Object> properties, String... required) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "",
                        "description", description,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", properties,
                                "required", java.util.Arrays.asList(required))));
    }

    @Override
    public boolean requiresApproval() {
        return false; // MVP 编排工具 EXECUTE/READ 均无审批（freeze §17）
    }

    @Override
    public final ToolResult execute(JsonNode args, ToolContext ctx) {
        try {
            return doExecute(args, ctx);
        } catch (ToolException e) {
            throw e; // 已是契约异常，交 ToolExecutor 翻译
        } catch (BusinessException e) {
            // 业务校验失败（40401/40901/40001）→ 非致命工具失败
            throw new ToolException("[" + e.getCode() + "] " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ToolException("非法参数: " + e.getMessage());
        }
    }

    /** 子类实现。 */
    protected abstract ToolResult doExecute(JsonNode args, ToolContext ctx);

    // ==================== 编排上下文解析 ====================

    /**
     * 解析 Lead Run + Lead Task（由 ToolContext.runId 反查）。
     * 校验调用 Run 属于 QA_LEAD Task —— 防御性权限复核（freeze §17）。
     */
    protected LeadContext leadContext(ToolContext ctx) {
        if (ctx == null || ctx.runId() == null) {
            throw new IllegalArgumentException("缺少编排上下文 runId");
        }
        Run leadRun = runRepo.findById(ctx.runId()).orElseThrow(
                () -> BusinessException.notFound("Lead Run 不存在: " + ctx.runId()));
        Task leadTask = taskRepo.findById(leadRun.taskId()).orElseThrow(
                () -> BusinessException.notFound("Lead Task 不存在: " + leadRun.taskId()));
        if (!"QA_LEAD".equals(extractAgentType(leadTask.constraintsJson()))) {
            throw BusinessException.conflict(
                    "QA 编排工具只能在 QA_LEAD 上下文调用（当前 task agent_type="
                            + extractAgentType(leadTask.constraintsJson()) + "）");
        }
        return new LeadContext(leadTask, leadRun);
    }

    record LeadContext(Task leadTask, Run leadRun) {
    }

    private static String extractAgentType(String constraintsJson) {
        try {
            JsonNode n = MAPPER.readTree(constraintsJson);
            JsonNode t = n.get("agent_type");
            return t == null || t.isNull() ? "UNKNOWN" : t.asText();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    // ---- 参数提取小工具（同 AbstractTesterTool 风格） ----

    protected static String textArg(JsonNode args, String key, String dflt) {
        JsonNode n = args.get(key);
        return n == null || n.isNull() ? dflt : n.asText();
    }

    protected static String requireText(JsonNode args, String key) {
        String v = textArg(args, key, null);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少必填字符串参数: " + key);
        }
        return v;
    }

    protected static long requireLong(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.canConvertToLong()) {
            throw new IllegalArgumentException("缺少必填整数参数: " + key);
        }
        return n.asLong();
    }

    protected static long longArgOrZero(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.canConvertToLong()) {
            return 0L;
        }
        return n.asLong();
    }

    /** 结果序列化：{"status":"...", ...}。 */
    protected static ToolResult ok(ObjectNode node) {
        return ToolResult.success(node.toString(), node.toString());
    }

    protected static ToolResult okJson(String json) {
        return ToolResult.success(json, json);
    }
}
