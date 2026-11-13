package com.aigamelab.tester;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.Finding;
import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * T3-3 Tool 7/8：finding_report —— 上报结构化 Finding 并落 finding 表。
 * 链路：Tool → FindingService（白名单/Evidence 校验）→ FindingRepository。
 *
 * Evidence 组装规则（裁决 §四 冻结契约）：
 * - sessionId 必填主锚点；runId 取 ToolContext（Agent Run 关联）；
 * - toolCallIds 由调用方传入本次证据区间（observation/events 的 tool_call id）；
 * - seed/overrides 随 args 传入保证可重放；tick 仅辅助展示。
 *
 * 事件发布：FINDING_CREATED 枚举属 T4-3（AgentEventType 追加），本 Task 只落库，
 * 不发布未注册事件类型（PersistingAgentEventPublisher 会拒绝并告警）。
 */
@Component
public class FindingReportTool extends AbstractTesterTool {

    static final String NAME = "finding_report";

    private final FindingService findingService;
    private final AgentEventPublisher publisher;

    public FindingReportTool(FindingService findingService, AgentEventPublisher publisher) {
        this.findingService = findingService;
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> jsonSchema() {
        var props = new java.util.LinkedHashMap<String, Object>();
        props.put("sessionId", Map.of("type", "string",
                "description", "证据所在会话 id"));
        props.put("scenarioId", Map.of("type", "string",
                "description", "场景 id"));
        props.put("configVersion", Map.of("type", "string",
                "description", "被测配置版本"));
        props.put("severity", Map.of("type", "string", "enum", Finding.SEVERITIES));
        props.put("category", Map.of("type", "string", "enum", Finding.CATEGORIES));
        props.put("title", Map.<String, Object>of("type", "string"));
        props.put("expected", Map.<String, Object>of("type", "string",
                "description", "预期行为（携带 Evidence 时必填）"));
        props.put("actual", Map.<String, Object>of("type", "string",
                "description", "实际行为（携带 Evidence 时必填）"));
        props.put("reproSteps", Map.of("type", "string",
                "description", "完整复现步骤：seed → game_start → 动作序列"));
        props.put("seed", Map.of("type", "integer"));
        props.put("toolCallIds", Map.of("type", "array",
                "items", Map.of("type", "integer"),
                "description", "证据 tool_call id 区间"));
        props.put("observationSnapshot", Map.of("type", "object",
                "description", "违规时刻的 Observation 快照"));
        props.put("tick", Map.of("type", "integer",
                "description", "会话内 tick（仅辅助展示）"));
        return schema("上报一条结构化缺陷/发现。必须基于真实 Observation，"
                + "reproSteps 须能让他人用相同 seed 重放。expected/actual 必须给出"
                + "有意义的对比内容（QA P1-2：Evidence 数据质量）。",
                props,
                "sessionId", "scenarioId", "configVersion", "severity",
                "category", "title", "expected", "actual", "reproSteps");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        String evidenceJson = buildEvidence(args, ctx);
        Finding saved = findingService.report(new Finding(
                null,
                ctx.runId(),
                null, // testPlanId 由 Service/API 层按 task 关联回填（T4-2 接线）
                null, // taskId 同上
                textArg(args, "scenarioId", null),
                textArg(args, "configVersion", null),
                textArg(args, "severity", null),
                textArg(args, "category", null),
                "LLM", // Agent 上报默认来源 LLM；引擎自动上报走 INVARIANT（T3-4）
                textArg(args, "title", null),
                nullableText(args, "expected"),
                nullableText(args, "actual"),
                textArg(args, "reproSteps", null),
                evidenceJson,
                null, // reproductionRate 二期统计回填
                null, // status 默认 OPEN
                null));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("findingId", saved.id());
        payload.put("status", saved.status());
        // T4-3：落库后发 FINDING_CREATED 事件（中台实时可见）
        if (ctx.runId() != null) {
            ObjectNode ev = MAPPER.createObjectNode();
            ev.put("findingId", saved.id());
            ev.put("severity", saved.severity());
            ev.put("category", saved.category());
            ev.put("source", saved.source());
            publisher.publish("FINDING_CREATED", ctx.runId(), ev.toString());
        }
        return ToolResult.success(payload.toString(),
                "Finding #" + saved.id() + " 已登记 [" + saved.severity()
                        + "/" + saved.category() + "] " + saved.title());
    }

    /**
     * Evidence JSON 组装：sessionId 主锚 + runId + toolCallIds + 快照 + 复现参数。
     * 结构与 Finding.Evidence record 一致（Service 层做反序列化校验）。
     */
    private static String buildEvidence(JsonNode args, ToolContext ctx) {
        ObjectNode ev = MAPPER.createObjectNode();
        ev.put("sessionId", textArg(args, "sessionId", null));
        if (ctx.runId() != null) {
            ev.put("runId", ctx.runId());
        }
        var ids = ev.putArray("toolCallIds");
        JsonNode tcIds = args.get("toolCallIds");
        if (tcIds != null && tcIds.isArray()) {
            tcIds.forEach(n -> {
                if (n.canConvertToLong()) {
                    ids.add(n.asLong());
                }
            });
        }
        JsonNode snapshot = args.get("observationSnapshot");
        if (snapshot != null && snapshot.isObject()) {
            ev.set("observationSnapshot", snapshot);
        } else {
            ev.putObject("observationSnapshot");
        }
        JsonNode tick = args.get("tick");
        if (tick != null && tick.canConvertToLong()) {
            ev.put("tick", tick.asLong()); // 仅辅助展示，非定位键
        }
        JsonNode seed = args.get("seed");
        if (seed != null && seed.canConvertToLong()) {
            ev.put("seed", seed.asLong());
        }
        ev.putObject("overrides"); // overrides 由 reproSteps 文本承载重放语义时可为空对象
        return ev.toString();
    }

    private static String nullableText(JsonNode args, String key) {
        JsonNode n = args.get(key);
        return n == null || n.isNull() ? null : n.asText();
    }
}
