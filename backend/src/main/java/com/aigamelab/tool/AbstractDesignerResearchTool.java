package com.aigamelab.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * R2-1：Designer 研究消费工具公共基类。
 *
 * <p>收敛 R2-1 两个只读研究工具（get_research_context / get_research_claims）的样板：
 * <ul>
 *   <li>function schema 组装 + 异常 → ToolException 统一翻译（镜像 AbstractResearchTool）</li>
 *   <li>{@link #resolveResearchSourceTaskId(ToolContext)}：从当前 Run 的 Task.constraintsJson
 *       解析 {@code researchSourceTaskId}——即该 Designer Run 被授权消费的研究 Task。</li>
 * </ul>
 *
 * <p>关联解析约束：AgentLoop / AgentTypeResolver 均冻结，ToolContext 只携带 runId；
 * 因此「哪个研究 Task 被消费」以 Task.constraintsJson.researchSourceTaskId 显式声明，
 * 工具经 run → taskId → constraintsJson 解析，不依赖任何新的运行时接线。
 *
 * <p>未关联 researchSourceTaskId 时工具返回明确的「未关联」结果（不抛错），
 * 保证 Designer 无研究上下文时的行为与 R2 之前完全一致（向后兼容）。
 */
public abstract class AbstractDesignerResearchTool implements GameTool {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunRepository runRepo;
    private final TaskRepository taskRepo;

    AbstractDesignerResearchTool(RunRepository runRepo, TaskRepository taskRepo) {
        this.runRepo = runRepo;
        this.taskRepo = taskRepo;
    }

    /** R2-1 只读研究工具：不修改任何状态，无需审批、不挂起 Run。 */
    @Override
    public boolean requiresApproval() {
        return false;
    }

    @Override
    public final ToolResult execute(JsonNode args, ToolContext ctx) {
        try {
            return doExecute(args, ctx);
        } catch (ToolException e) {
            throw e; // 已是契约异常，交 ToolExecutor 翻译
        } catch (IllegalArgumentException e) {
            throw new ToolException("非法参数: " + e.getMessage());
        } catch (Exception e) {
            throw new ToolException("Designer Research Tool 异常: " + e.getMessage());
        }
    }

    /** 子类实现：业务逻辑（只读，不得写入任何表）。 */
    protected abstract ToolResult doExecute(JsonNode args, ToolContext ctx);

    /**
     * 解析当前 Designer Run 关联的研究 Task id（researchSourceTaskId）。
     *
     * @return 关联的研究 Task id；未关联 / constraintsJson 缺失或非法时返回 null
     * @throws ToolException Run/Task 不存在或 researchSourceTaskId 非法时
     */
    protected final Long resolveResearchSourceTaskId(ToolContext ctx) {
        Run run = runRepo.findById(ctx.runId()).orElseThrow(
                () -> new ToolException("Run 不存在: " + ctx.runId()));
        Task task = taskRepo.findById(run.taskId()).orElseThrow(
                () -> new ToolException("Task 不存在: " + run.taskId()));
        return parseResearchSourceTaskId(task.constraintsJson());
    }

    /** 从 constraintsJson 提取 researchSourceTaskId；缺失/非法返回 null。 */
    private Long parseResearchSourceTaskId(String constraintsJson) {
        if (constraintsJson == null || constraintsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(constraintsJson);
            JsonNode node = root.get("researchSourceTaskId");
            if (node == null || node.isNull()) {
                return null;
            }
            if (!node.canConvertToLong()) {
                throw new ToolException("constraintsJson.researchSourceTaskId 必须是整数");
            }
            return node.asLong();
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return null; // constraintsJson 非 JSON → 视为未关联
        }
    }

    // ---- function schema 组装（镜像 AbstractResearchTool） ----

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

    // ---- 参数提取小工具 ----

    protected static String textArg(JsonNode args, String key, String dflt) {
        JsonNode n = args.get(key);
        return n == null || n.isNull() ? dflt : n.asText();
    }

    protected static Long longArgOrNull(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.canConvertToLong()) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
        return n.asLong();
    }

    protected static int intArgOrDefault(JsonNode args, String key, int dflt, int min, int max) {
        Long v = longArgOrNull(args, key);
        if (v == null) {
            return dflt;
        }
        return (int) Math.max(min, Math.min(max, v));
    }

    protected static Double doubleArgOrNull(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.asDouble();
    }

    /**
     * 从 research_claim.supporting_source_ids / contradicting_source_ids 的存储文本
     * （如 {@code ["source:1]", "[1,2]"}）提取整数 id 列表。
     */
    protected static List<Long> parseSourceIds(String raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(raw);
        while (m.find()) {
            ids.add(Long.parseLong(m.group()));
        }
        return ids;
    }

    /** 整数 id 列表 → JSON 数组节点。 */
    protected static ArrayNode toJsonArray(List<Long> ids) {
        ArrayNode arr = MAPPER.createArrayNode();
        ids.forEach(arr::add);
        return arr;
    }
}
