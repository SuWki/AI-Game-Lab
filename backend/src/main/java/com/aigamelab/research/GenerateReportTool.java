package com.aigamelab.research;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aigamelab.domain.ResearchClaim;
import com.aigamelab.domain.ResearchSource;
import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.repository.DesignDocumentRepository;
import com.aigamelab.repository.ResearchClaimRepository;
import com.aigamelab.repository.ResearchSourceRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;

/**
 * R0-7：generate_report 工具。
 *
 * 接收研究问题，汇总所有 claims + sources，生成 Markdown 格式报告，
 * 持久化到 design_document 表（title 前缀 [RESEARCH REPORT]）。
 */
@Component
public class GenerateReportTool extends AbstractResearchTool {

    private static final String TITLE_PREFIX = "[RESEARCH REPORT]";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ResearchClaimRepository claimRepo;
    private final ResearchSourceRepository sourceRepo;
    private final RunRepository runRepo;
    private final DesignDocumentRepository docRepo;
    private final AgentEventPublisher publisher;

    public GenerateReportTool(ResearchClaimRepository claimRepo,
            ResearchSourceRepository sourceRepo,
            RunRepository runRepo,
            DesignDocumentRepository docRepo,
            AgentEventPublisher publisher) {
        this.claimRepo = claimRepo;
        this.sourceRepo = sourceRepo;
        this.runRepo = runRepo;
        this.docRepo = docRepo;
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "generate_report";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("生成研究报告并持久化。",
                Map.of(
                        "question", Map.of("type", "string", "description", "研究问题"),
                        "summary", Map.of("type", "string", "description", "研究摘要/结论")),
                "question", "summary");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        String question = requireText(args, "question");
        String summary = requireText(args, "summary");

        var run = runRepo.findById(ctx.runId()).orElseThrow(
                () -> new IllegalArgumentException("Run 不存在: " + ctx.runId()));

        List<ResearchClaim> claims = claimRepo.findByRunId(ctx.runId());
        List<ResearchSource> sources = sourceRepo.findByRunId(ctx.runId());

        // ---- TG：generate_report 幂等——同 run 同问题不重复生成报告 ----
        String title = TITLE_PREFIX + " " + question;
        var existing = docRepo.findByRunId(ctx.runId()).stream()
                .filter(d -> title.equals(d.title()))
                .findFirst();
        if (existing.isPresent()) {
            if (ctx.runId() != null) {
                ObjectNode ev = MAPPER.createObjectNode();
                ev.put("documentId", existing.get().id());
                ev.put("title", title);
                ev.put("deduplicated", true);
                ev.put("taskId", run.taskId());
                publisher.publish("RESEARCH_REPORT_CREATED", ctx.runId(), ev.toString());
            }
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("documentId", existing.get().id());
            payload.put("title", title);
            payload.put("claimsCount", claims.size());
            payload.put("sourcesCount", sources.size());
            payload.put("deduplicated", true);
            return ToolResult.success(payload.toString(),
                    "报告已存在，复用 (id=" + existing.get().id() + "): " + title);
        }

        // TF：生成符合 research_p001.md 7 节契约的报告
        String md = buildMarkdown(question, summary, claims, sources);

        // 持久化到 design_document 表（复用现有表，title 前缀 [RESEARCH REPORT]）
        var saved = docRepo.insert(new com.aigamelab.domain.DesignDocument(
                null, ctx.runId(), run.taskId(), title, md, null));

        // R2-2：报告落库后发布 RESEARCH_REPORT_CREATED 事件（Tool → AgentEventPublisher → agent_event → SSE）
        if (ctx.runId() != null) {
            ObjectNode ev = MAPPER.createObjectNode();
            ev.put("documentId", saved.id());
            ev.put("title", title);
            ev.put("claimsCount", claims.size());
            ev.put("sourcesCount", sources.size());
            ev.put("taskId", run.taskId());
            publisher.publish("RESEARCH_REPORT_CREATED", ctx.runId(), ev.toString());
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("documentId", saved.id());
        payload.put("title", title);
        payload.put("claimsCount", claims.size());
        payload.put("sourcesCount", sources.size());

        return ToolResult.success(payload.toString(),
                "研究报告已保存 (id=" + saved.id() + "): " + title);
    }

    /**
     * TF：按 research_p001.md 输出格式契约渲染 7 节 Markdown 报告：
     * 1 研究问题 → 2 结论(含 Confidence Score) → 3 支持证据 → 4 反对证据
     * → 5 最合理模型/假设 → 6 不确定性说明 → 7 后续研究建议。
     *
     * <p>可追溯性增强：每条证据注明 [claim#N]，来源注明 [source#M]，
     * 支持/反驳的来源 id 也能从 claim 关联中反查到具体来源标题。
     * DRAFT（待验证）与 VALIDATED 分离展示，不混入「支持证据」。
     */
    private String buildMarkdown(String question, String summary,
            List<ResearchClaim> claims, List<ResearchSource> sources) {
        StringBuilder md = new StringBuilder();
        String now = LocalDateTime.now().format(FMT);

        List<ResearchClaim> validated = claims.stream()
                .filter(c -> "VALIDATED".equals(c.status())).toList();
        List<ResearchClaim> disputed = claims.stream()
                .filter(c -> "DISPUTED".equals(c.status())).toList();
        List<ResearchClaim> pending = claims.stream()
                .filter(c -> "DRAFT".equals(c.status())).toList();

        double overallConfidence = validated.isEmpty() ? 0.0
                : validated.stream().mapToDouble(ResearchClaim::confidence).average().orElse(0.0);

        md.append("# Research Report\n\n");
        md.append("**研究问题：** ").append(question).append("\n\n");
        md.append("**生成时间：** ").append(now).append("\n\n");
        md.append("---\n\n");

        // 1. 研究问题
        md.append("## 1. 研究问题\n\n").append(question).append("\n\n");

        // 2. 结论（含 Confidence Score）
        md.append("## 2. 结论（含 Confidence Score）\n\n");
        md.append(summary).append("\n\n");
        md.append("**综合 Confidence Score：** ")
                .append(String.format("%.2f", overallConfidence)).append("\n\n");

        // 3. 支持证据（仅 VALIDATED，来源 + 可靠性 + 可追溯 ids）
        md.append("## 3. 支持证据（来源 + 可靠性）\n\n");
        if (validated.isEmpty()) {
            md.append("无已验证的断言。\n\n");
        } else {
            for (ResearchClaim c : validated) {
                md.append("- ✅ ").append(c.claim())
                        .append(" [claim#").append(c.id()).append("]")
                        .append(" (Confidence: ").append(String.format("%.2f", c.confidence()))
                        .append(")\n");
                String refs = describeClaimSources(c, sources);
                if (!refs.isEmpty()) {
                    md.append("  - 依据来源：").append(refs).append("\n");
                }
            }
            md.append("\n");
        }

        // 4. 反对证据
        md.append("## 4. 反对证据（来源 + 可靠性）\n\n");
        if (disputed.isEmpty()) {
            md.append("无反对证据。\n\n");
        } else {
            for (ResearchClaim c : disputed) {
                md.append("- ❌ ").append(c.claim())
                        .append(" [claim#").append(c.id()).append("]")
                        .append(" (Confidence: ").append(String.format("%.2f", c.confidence()))
                        .append(")\n");
                String refs = describeClaimSources(c, sources);
                if (!refs.isEmpty()) {
                    md.append("  - 依据来源：").append(refs).append("\n");
                }
            }
            md.append("\n");
        }

        // 5. 最合理模型/假设：取置信度最高的已验证断言；无则提示待研究
        md.append("## 5. 最合理模型/假设\n\n");
        ResearchClaim best = validated.stream()
                .max(java.util.Comparator.comparingDouble(ResearchClaim::confidence))
                .orElse(null);
        if (best != null) {
            md.append("- 假设：").append(best.claim())
                    .append(" [claim#").append(best.id()).append("]")
                    .append(" (Confidence: ").append(String.format("%.2f", best.confidence()))
                    .append(")\n\n");
        } else {
            md.append("当前证据不足以形成高置信度假设（无 VALIDATED 断言）。\n\n");
        }

        // 6. 不确定性说明：DRAFT（待验证）+ 有争议 + 缺少独立来源的说明
        md.append("## 6. 不确定性说明\n\n");
        if (pending.isEmpty() && disputed.isEmpty()) {
            md.append("无未验证或有争议的断言。\n\n");
        } else {
            for (ResearchClaim c : pending) {
                md.append("- ⏳ ").append(c.claim())
                        .append(" [claim#").append(c.id()).append("]")
                        .append(" (待验证，Confidence: ")
                        .append(String.format("%.2f", c.confidence())).append(")\n");
            }
            for (ResearchClaim c : disputed) {
                md.append("- ⚠️ ").append(c.claim())
                        .append(" [claim#").append(c.id()).append("]")
                        .append(" (有争议，Confidence: ")
                        .append(String.format("%.2f", c.confidence())).append(")\n");
            }
            md.append("\n");
        }

        // 7. 后续研究建议：按需补查独立来源 / 验证待定 claim
        md.append("## 7. 后续研究建议\n\n");
        if (pending.isEmpty() && disputed.isEmpty()) {
            md.append("建议将此报告交付下游 Designer 使用。\n\n");
        } else {
            if (!pending.isEmpty()) {
                md.append("- 对 ").append(pending.size())
                        .append(" 条待验证断言：补足 ≥2 个独立来源并再次交叉验证。\n");
            }
            if (!disputed.isEmpty()) {
                md.append("- 对 ").append(disputed.size())
                        .append(" 条有争议断言：追加反驳来源核查与证据链补强。\n");
            }
            md.append("\n");
        }

        // 来源列表（含可追溯 [source#M]）
        md.append("## 来源\n\n");
        if (sources.isEmpty()) {
            md.append("无来源。\n\n");
        } else {
            for (ResearchSource s : sources) {
                md.append("- [source#").append(s.id()).append("] ").append(s.title())
                        .append(" [").append(s.sourceType()).append("]");
                if (s.reliability() != null) {
                    md.append(" (reliability: ").append(String.format("%.2f", s.reliability())).append(")");
                }
                md.append("\n  ").append(s.url()).append("\n");
            }
            md.append("\n");
        }

        // 元数据
        md.append("---\n\n");
        md.append("**总断言数：** ").append(claims.size()).append("\n");
        md.append("**总来源数：** ").append(sources.size()).append("\n");
        md.append("**已验证：** ").append(validated.size()).append("\n");
        md.append("**有争议：** ").append(disputed.size()).append("\n");
        md.append("**待验证：** ").append(pending.size()).append("\n");

        return md.toString();
    }

    /** TF：将 claim 的 supporting/contradicting source ids 解析为 "[source#N] 标题(类型)" 片段。 */
    private String describeClaimSources(ResearchClaim claim, List<ResearchSource> sources) {
        List<Long> supIds = parseSourceIdsR(claim.supportingSourceIds());
        List<Long> conIds = parseSourceIdsR(claim.contradictingSourceIds());
        StringBuilder sb = new StringBuilder();
        for (long id : supIds) {
            appendSourceRef(sb, id, sources);
        }
        for (long id : conIds) {
            if (sb.length() > 0) sb.append("、");
            sb.append("反驳 ");
            appendSourceRef(sb, id, sources);
        }
        return sb.toString();
    }

    private void appendSourceRef(StringBuilder sb, long id, List<ResearchSource> sources) {
        if (sb.length() > 0) sb.append("、");
        String title = sources.stream().filter(s -> s.id().equals(id))
                .map(ResearchSource::title).findFirst().orElse("来源#" + id);
        String type = sources.stream().filter(s -> s.id().equals(id))
                .map(ResearchSource::sourceType).findFirst().orElse("");
        sb.append("[source#").append(id).append("] ").append(title);
        if (!type.isEmpty()) {
            sb.append("(").append(type).append(")");
        }
    }

    /** 解析 "JSON array of ids" 软引用文本为 id 列表（容忍 legacy ["source:N] 与 canonical [N]）。 */
    private List<Long> parseSourceIdsR(String raw) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(raw);
        while (m.find()) {
            ids.add(Long.parseLong(m.group()));
        }
        return ids;
    }
}
