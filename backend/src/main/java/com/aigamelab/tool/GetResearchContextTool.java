package com.aigamelab.tool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.DesignDocument;
import com.aigamelab.domain.ResearchClaim;
import com.aigamelab.domain.ResearchSource;
import com.aigamelab.domain.Run;
import com.aigamelab.repository.DesignDocumentRepository;
import com.aigamelab.repository.ResearchClaimRepository;
import com.aigamelab.repository.ResearchSourceRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * R2-1：Designer 只读研究工具 #1 —— get_research_context。
 *
 * <p>读取当前 Designer Run 被授权消费的研究任务（constraintsJson.researchSourceTaskId）
 * 的完整上下文：研究 Run、来源、断言、报告概览（含可追溯 id），供 Designer 将
 * 研究证据纳入设计判断。只读，不修改任何表。
 *
 * <p>输出有界（来源/报告仅带摘录，不 dump 整库），并携带全部 traceable id
 * （researchSourceTaskId / researchRunIds / source id / claim id / report id），
 * 使「Research Claim → Designer Reasoning → Design Decision」链条可被 UI 追溯。
 */
@Component
public class GetResearchContextTool extends AbstractDesignerResearchTool {

    private static final String REPORT_PREFIX = "[RESEARCH REPORT]";
    private static final int SOURCE_EXCERPT = 300;
    private static final int REPORT_EXCERPT = 800;

    private final RunRepository runRepo;
    private final ResearchSourceRepository sourceRepo;
    private final ResearchClaimRepository claimRepo;
    private final DesignDocumentRepository docRepo;

    public GetResearchContextTool(RunRepository runRepo, TaskRepository taskRepo,
            ResearchSourceRepository sourceRepo, ResearchClaimRepository claimRepo,
            DesignDocumentRepository docRepo) {
        super(runRepo, taskRepo);
        this.runRepo = runRepo;
        this.sourceRepo = sourceRepo;
        this.claimRepo = claimRepo;
        this.docRepo = docRepo;
    }

    @Override
    public String name() {
        return "get_research_context";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema(
                "读取当前 Designer Run 关联研究任务（constraintsJson.researchSourceTaskId）的研究上下文："
                + "研究来源、断言、报告概览（含 traceable id）。"
                + "若任务未关联研究任务则返回 linked=false。只读工具，不修改任何数据。",
                Map.of());
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        Long researchSourceTaskId = resolveResearchSourceTaskId(ctx);

        ObjectNode payload = MAPPER.createObjectNode();
        if (researchSourceTaskId == null) {
            payload.put("linked", false);
            payload.put("hasData", false);
            payload.put("message",
                    "当前任务未通过 constraintsJson.researchSourceTaskId 关联研究任务，无研究上下文可消费。");
            return ToolResult.success(payload.toString(),
                    "未关联研究任务，无研究上下文");
        }

        payload.put("linked", true);
        payload.put("researchSourceTaskId", researchSourceTaskId);

        // 研究任务产出的所有 Run（该 Task 下多次研究 Run）
        ArrayNode researchRunIds = payload.putArray("researchRunIds");
        for (Run r : runRepo.findByTask(researchSourceTaskId)) {
            researchRunIds.add(r.id());
        }

        List<ResearchSource> sources = sourceRepo.findByTaskId(researchSourceTaskId);
        List<ResearchClaim> claims = claimRepo.findByTaskId(researchSourceTaskId);
        List<DesignDocument> reports = docRepo.findByTaskId(researchSourceTaskId).stream()
                .filter(d -> d.title() != null && d.title().startsWith(REPORT_PREFIX))
                .toList();

        if (sources.isEmpty() && claims.isEmpty() && reports.isEmpty()) {
            payload.put("hasData", false);
            payload.put("message",
                    "研究任务 #" + researchSourceTaskId + " 暂无产出（无来源/断言/报告）。");
            return ToolResult.success(payload.toString(),
                    "研究任务无产出，无研究上下文可消费");
        }

        payload.put("hasData", true);

        // 概览计数
        ObjectNode summary = payload.putObject("summary");
        summary.put("sourceCount", sources.size());
        summary.put("claimCount", claims.size());
        summary.put("validatedClaimCount", claims.stream()
                .filter(c -> "VALIDATED".equals(c.status())).count());
        summary.put("reportCount", reports.size());
        payload.set("summary", summary);

        // 来源（id + 摘录）
        ArrayNode sourcesNode = payload.putArray("sources");
        for (ResearchSource s : sources) {
            ObjectNode n = sourcesNode.addObject();
            n.put("id", s.id());
            n.put("title", s.title());
            n.put("sourceType", s.sourceType());
            n.put("url", s.url());
            if (s.reliability() != null) {
                n.put("reliability", s.reliability());
            }
            n.put("contentExcerpt", excerpt(s.contentSummary(), SOURCE_EXCERPT));
        }

        // 断言（可追溯：claim id + 来源 id）
        ArrayNode claimsNode = payload.putArray("claims");
        for (ResearchClaim c : claims) {
            ObjectNode n = claimsNode.addObject();
            n.put("id", c.id());
            n.put("claim", c.claim());
            n.put("confidence", c.confidence());
            n.put("status", c.status());
            n.set("supportingSourceIds", toJsonArray(parseSourceIds(c.supportingSourceIds())));
            n.set("contradictingSourceIds", toJsonArray(parseSourceIds(c.contradictingSourceIds())));
        }

        // 报告（id + 标题 + 摘录）
        ArrayNode reportsNode = payload.putArray("reports");
        for (DesignDocument d : reports) {
            ObjectNode n = reportsNode.addObject();
            n.put("id", d.id());
            n.put("title", d.title());
            n.put("contentExcerpt", excerpt(d.contentMd(), REPORT_EXCERPT));
        }

        return ToolResult.success(payload.toString(),
                "研究上下文已读取：来源 " + sources.size() + " / 断言 " + claims.size()
                        + " / 报告 " + reports.size());
    }

    private static String excerpt(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
