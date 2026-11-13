package com.aigamelab.tool;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.ResearchClaim;
import com.aigamelab.repository.ResearchClaimRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * R2-1：Designer 只读研究工具 #2 —— get_research_claims。
 *
 * <p>按状态 / 最低置信度 / 条数上限，细粒度读取当前 Designer Run 被授权消费
 * 的研究任务断言（Research Claim，含可追溯 id 与支持/反驳来源 id）。
 * 用于设计判断需要引用具体 claim 证据时的精确查询。只读，不修改任何表。
 *
 * <p>设计约束（R2 授权）：
 * <ul>
 *   <li>无参数时默认读取全部断言（按置信度降序）；</li>
 *   <li>limit 必须有界（默认 20，硬上限 100），禁止无界 dump 进 LLM 上下文；</li>
 *   <li>未关联研究任务时返回 linked=false，行为向后兼容。</li>
 * </ul>
 */
@Component
public class GetResearchClaimsTool extends AbstractDesignerResearchTool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ResearchClaimRepository claimRepo;

    public GetResearchClaimsTool(RunRepository runRepo, TaskRepository taskRepo,
            ResearchClaimRepository claimRepo) {
        super(runRepo, taskRepo);
        this.claimRepo = claimRepo;
    }

    @Override
    public String name() {
        return "get_research_claims";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema(
                "读取当前 Designer Run 关联研究任务（constraintsJson.researchSourceTaskId）的研究断言（Claims）。"
                + "可按状态/最低置信度/条数上限过滤；返回带可追溯 id 的断言明细。"
                + "若任务未关联研究任务则返回 linked=false。只读工具，不修改任何数据。",
                Map.of(
                        "status", Map.of("type", "string",
                                "description", "按状态过滤: DRAFT/VALIDATED/DISPUTED（可空）"),
                        "confidenceMin", Map.of("type", "number",
                                "description", "最低置信度（0-1，可空）"),
                        "limit", Map.of("type", "integer",
                                "description", "返回条数上限（默认 20，最大 100）")));
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        Long researchSourceTaskId = resolveResearchSourceTaskId(ctx);

        ObjectNode payload = MAPPER.createObjectNode();
        if (researchSourceTaskId == null) {
            payload.put("linked", false);
            payload.put("hasData", false);
            payload.put("message",
                    "当前任务未通过 constraintsJson.researchSourceTaskId 关联研究任务，无研究断言可消费。");
            return ToolResult.success(payload.toString(),
                    "未关联研究任务，无研究断言");
        }

        String status = textArg(args, "status", null);
        Double confidenceMin = doubleArgOrNull(args, "confidenceMin");
        int limit = intArgOrDefault(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);

        List<ResearchClaim> claims = claimRepo.findByTaskId(researchSourceTaskId).stream()
                .filter(c -> status == null || status.equalsIgnoreCase(c.status()))
                .filter(c -> confidenceMin == null || c.confidence() >= confidenceMin)
                .sorted(Comparator
                        .comparingDouble((ResearchClaim c) -> c.confidence()).reversed())
                .limit(limit)
                .toList();

        payload.put("linked", true);
        payload.put("researchSourceTaskId", researchSourceTaskId);
        payload.put("totalMatched", claims.size());
        payload.put("limit", limit);

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

        return ToolResult.success(payload.toString(),
                "研究断言已读取：匹配 " + claims.size() + " 条（limit=" + limit + "）");
    }
}