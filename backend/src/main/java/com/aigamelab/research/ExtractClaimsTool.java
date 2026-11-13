package com.aigamelab.research;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aigamelab.domain.ResearchClaim;
import com.aigamelab.domain.ResearchSource;
import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.repository.ResearchClaimRepository;
import com.aigamelab.repository.ResearchSourceRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;

/**
 * R0-6：extract_claims 工具。
 *
 * 接收 source_id，从 research_source 表读取 content_summary，
 * 将摘要按句拆分为 claims，每个 claim 持久化到 research_claim 表。
 *
 * 注：真实场景中 claim 提取应由 LLM 完成；此处 R0 实现为基于句号的
 * 简单拆分 + 低初始置信度，交由 compare_sources 后续验证提升。
 */
@Component
public class ExtractClaimsTool extends AbstractResearchTool {

    private final ResearchSourceRepository sourceRepo;
    private final ResearchClaimRepository claimRepo;
    private final RunRepository runRepo;
    private final AgentEventPublisher publisher;

    public ExtractClaimsTool(ResearchSourceRepository sourceRepo,
            ResearchClaimRepository claimRepo, RunRepository runRepo,
            AgentEventPublisher publisher) {
        this.sourceRepo = sourceRepo;
        this.claimRepo = claimRepo;
        this.runRepo = runRepo;
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "extract_claims";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("从一个研究来源中提取断言（Claims）。",
                Map.of(
                        "sourceId", Map.of("type", "integer", "description", "来源 ID（research_source.id）")),
                "sourceId");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        long sourceId = requireLong(args, "sourceId");

        ResearchSource source = sourceRepo.findById(sourceId).orElseThrow(
                () -> new IllegalArgumentException("来源不存在: " + sourceId));

        String content = source.contentSummary();
        if (content == null || content.isBlank()) {
            return ToolResult.failure("来源 content_summary 为空，无法提取 claims: " + sourceId);
        }

        // 从 run 获取 taskId
        var run = runRepo.findById(ctx.runId()).orElseThrow(
                () -> new IllegalArgumentException("Run 不存在: " + ctx.runId()));

        // 简单按句号拆分（R0 Stub；真实场景由 LLM 提取）
        String[] sentences = content.split("(?<=[。！？.!?])\\s*");
        ArrayNode createdClaims = MAPPER.createArrayNode();

        // TG：幂等去重——同 run 内已存在相同文本的 claim 则跳过，不重复插入。
        List<ResearchClaim> existingClaims = claimRepo.findByRunId(ctx.runId());
        java.util.Set<String> existingTexts = new java.util.HashSet<>();
        for (ResearchClaim ec : existingClaims) {
            existingTexts.add(ec.claim());
        }
        int skipped = 0;

        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.length() < 10) {
                continue; // 跳过过短的句子
            }
            if (existingTexts.contains(trimmed)) {
                skipped++; // TG：重复文本幂等跳过
                continue;
            }

            ResearchClaim claim = claimRepo.insert(new ResearchClaim(
                    null, ctx.runId(), run.taskId(),
                    trimmed, 0.3, // 初始低置信度
                    "[" + sourceId + "]", "[]",
                    "DRAFT", null));
            existingTexts.add(trimmed); // 本次循环内也不重复插入

            ObjectNode node = MAPPER.createObjectNode();
            node.put("claimId", claim.id());
            node.put("claim", trimmed);
            node.put("confidence", claim.confidence());
            createdClaims.add(node);
        }

        // R2-2：落库后发布 CLAIM_EXTRACTED 事件（Tool → AgentEventPublisher → agent_event → SSE）
        if (ctx.runId() != null) {
            ObjectNode ev = MAPPER.createObjectNode();
            ev.put("sourceId", sourceId);
            ev.put("sourceTitle", source.title());
            ev.put("extractedCount", createdClaims.size());
            ev.put("skippedCount", skipped);
            ev.put("taskId", run.taskId());
            publisher.publish("CLAIM_EXTRACTED", ctx.runId(), ev.toString());
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("sourceId", sourceId);
        payload.put("sourceTitle", source.title());
        payload.put("extractedCount", createdClaims.size());
        payload.put("skippedCount", skipped);
        payload.set("claims", createdClaims);

        String suffix = skipped > 0 ? "（其中 " + skipped + " 条重复已跳过）" : "";
        return ToolResult.success(payload.toString(),
                "从来源「" + source.title() + "」提取了 " + createdClaims.size() + " 条 claims" + suffix);
    }
}
