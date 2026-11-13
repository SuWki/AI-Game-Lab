package com.aigamelab.research;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aigamelab.domain.ResearchSource;
import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.repository.ResearchSourceRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;

/**
 * R0-5：save_source 工具。
 *
 * 接收来源信息（url, sourceType, title, contentSummary, reliability），
 * 持久化到 research_source 表。
 *
 * TC 硬化（R2 实施审计）：
 * - sourceType 枚举校验：仅接受 Official/Developer/Academic/Community/Speculation，
 *   拒绝其它值（工具调用标记 FAILED，Run 继续，Agent 可自行修正参数重试）；
 * - reliability clamp [0, 1]：超出范围的值被 clamp 到边界，而非静默写入垃圾数据；
 * - canonical URL 去重幂等：同一 run 内对相同 URL 的多次 save_source 调用
 *   返回既有的 source 记录（deduplicated=true），不重复插入。
 */
@Component
public class SaveSourceTool extends AbstractResearchTool {

    /** research_p001.md §1 定义的 5 种来源类型（有限集，非法值应显式拒绝）。 */
    private static final Set<String> ALLOWED_SOURCE_TYPES =
            Set.of("Official", "Developer", "Academic", "Community", "Speculation");

    private final ResearchSourceRepository sourceRepo;
    private final RunRepository runRepo;
    private final AgentEventPublisher publisher;

    public SaveSourceTool(ResearchSourceRepository sourceRepo, RunRepository runRepo,
            AgentEventPublisher publisher) {
        this.sourceRepo = sourceRepo;
        this.runRepo = runRepo;
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "save_source";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("保存一个研究来源到数据库。",
                Map.of(
                        "url", Map.of("type", "string", "description", "来源 URL"),
                        "sourceType", Map.of("type", "string", "description",
                                "来源类型: Official/Developer/Academic/Community/Speculation"),
                        "title", Map.of("type", "string", "description", "来源标题"),
                        "contentSummary", Map.of("type", "string", "description", "内容摘要"),
                        "reliability", Map.of("type", "number", "description",
                                "可靠性评分 0.0-1.0（默认 0.5，超出范围自动 clamp）")),
                "url", "sourceType", "title");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        String url = requireText(args, "url");
        String sourceType = requireText(args, "sourceType");
        String title = requireText(args, "title");
        String contentSummary = textArg(args, "contentSummary", null);

        // ---- TC：sourceType 枚举校验（研究5类来源，非法值显式拒绝） ----
        if (!ALLOWED_SOURCE_TYPES.contains(sourceType)) {
            throw new IllegalArgumentException("非法来源类型: " + sourceType
                    + "（允许: " + ALLOWED_SOURCE_TYPES + "）");
        }

        // ---- TC：reliability clamp [0, 1]（垃圾输入不落库，边界自动修正） ----
        double reliability = args.has("reliability") && !args.get("reliability").isNull()
                ? args.get("reliability").asDouble(0.5) : 0.5;
        reliability = Math.max(0.0, Math.min(1.0, reliability));

        // 从 run 获取 taskId
        var run = runRepo.findById(ctx.runId()).orElseThrow(
                () -> new IllegalArgumentException("Run 不存在: " + ctx.runId()));

        // ---- TC：canonical URL 去重幂等（同 run 同 URL 不重复插入） ----
        String canonicalUrl = canonicalizeUrl(url);
        var existing = sourceRepo.findByRunIdAndUrl(ctx.runId(), canonicalUrl);
        if (existing.isPresent()) {
            ResearchSource s = existing.get();
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("sourceId", s.id());
            payload.put("url", s.url());
            payload.put("sourceType", s.sourceType());
            payload.put("title", s.title());
            payload.put("deduplicated", true);
            return ToolResult.success(payload.toString(),
                    "来源已存在，复用 (id=" + s.id() + "): " + s.title());
        }

        ResearchSource saved = sourceRepo.insert(new ResearchSource(
                null, ctx.runId(), run.taskId(),
                canonicalUrl, sourceType, title, contentSummary, reliability,
                java.time.LocalDateTime.now().withNano(0), null));

        // R2-2：落库后发布 SOURCE_FETCHED 事件（Tool → AgentEventPublisher → agent_event → SSE）
        if (ctx.runId() != null) {
            ObjectNode ev = MAPPER.createObjectNode();
            ev.put("sourceId", saved.id());
            ev.put("url", canonicalUrl);
            ev.put("sourceType", sourceType);
            ev.put("title", title);
            ev.put("taskId", run.taskId());
            publisher.publish("SOURCE_FETCHED", ctx.runId(), ev.toString());
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("sourceId", saved.id());
        payload.put("url", canonicalUrl);
        payload.put("sourceType", sourceType);
        payload.put("title", title);

        return ToolResult.success(payload.toString(),
                "来源已保存 (id=" + saved.id() + "): " + title);
    }

    /**
     * TC：URL 归一化——trim + 去尾部斜杠（保留根 "/"）+ 去 fragment（#...）。
     * 使相似 URL（如 https://a.com/page/ vs https://a.com/page）判定为同一来源。
     */
    private static String canonicalizeUrl(String url) {
        String normalized = url.trim();
        // 去 fragment
        int hash = normalized.indexOf('#');
        if (hash >= 0) {
            normalized = normalized.substring(0, hash);
        }
        // 去尾部斜杠（但保留根 "/"）
        while (normalized.endsWith("/") && normalized.length() > 1
                && !normalized.endsWith("://")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
