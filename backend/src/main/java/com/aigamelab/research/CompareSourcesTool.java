package com.aigamelab.research;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aigamelab.domain.ResearchClaim;
import com.aigamelab.domain.ResearchSource;
import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.repository.ResearchClaimRepository;
import com.aigamelab.repository.ResearchSourceRepository;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;

/**
 * R0-6：compare_sources 工具。
 *
 * 接收 claim_id，查询该 claim 的 supporting/contradicting sources，
 * 重新计算 confidence 分数，更新 research_claim 表。
 *
 * Confidence 计算规则（R0 简化版）：
 * - 初始 0.3
 * - 每个 supporting source +0.2（上限 1.0）
 * - 每个 contradicting source -0.3（下限 0.0）
 * - 交叉验证（多个独立来源支持）额外 +0.1
 *
 * TB 硬化（R2 实施审计）：
 * - 来源存在性校验：supporting/contradicting 中每个 source id 必须已落库；
 * - 所有权校验：来源必须属于同一 research run（runId）与 task（taskId），
 *   防止跨研究任务引用其它研究产出的来源（证据链污染）；
 * - 互斥校验：同一来源不得同时出现在 supporting 与 contradicting；
 * - 原子性：sourceIds / confidence / status 三个 UPDATE 在一个事务内提交，
 *   任一步失败整体回滚，避免中间态（改了一半的断言）。
 */
@Component
public class CompareSourcesTool extends AbstractResearchTool {

    private final ResearchClaimRepository claimRepo;
    private final ResearchSourceRepository sourceRepo;
    private final TransactionTemplate tx;
    private final AgentEventPublisher publisher;

    public CompareSourcesTool(ResearchClaimRepository claimRepo,
            ResearchSourceRepository sourceRepo,
            PlatformTransactionManager txManager,
            AgentEventPublisher publisher) {
        this.claimRepo = claimRepo;
        this.sourceRepo = sourceRepo;
        this.tx = new TransactionTemplate(txManager);
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "compare_sources";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("交叉验证一个断言：重新计算置信度并更新。",
                Map.of(
                        "claimId", Map.of("type", "integer", "description", "断言 ID（research_claim.id）"),
                        "supportingSourceIds", Map.of("type", "array",
                                "items", Map.of("type", "integer"),
                                "description", "支持此断言的来源 ID 列表（须为同一研究 run/task 内已保存的来源）"),
                        "contradictingSourceIds", Map.of("type", "array",
                                "items", Map.of("type", "integer"),
                                "description", "反驳此断言的来源 ID 列表（须为同一研究 run/task 内已保存的来源）")),
                "claimId");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        long claimId = requireLong(args, "claimId");

        ResearchClaim claim = claimRepo.findById(claimId).orElseThrow(
                () -> new IllegalArgumentException("断言不存在: " + claimId));

        // 解析支持/反对来源 ID
        List<Long> supportingIds = parseLongArray(args, "supportingSourceIds");
        List<Long> contradictingIds = parseLongArray(args, "contradictingSourceIds");

        // ---- TB：来源存在性 + 所有权（同 run/task）校验 ----
        for (long sid : supportingIds) {
            ResearchSource s = sourceRepo.findById(sid).orElseThrow(
                    () -> new IllegalArgumentException("支持来源不存在: " + sid));
            requireOwnership(s, claim);
        }
        for (long sid : contradictingIds) {
            ResearchSource s = sourceRepo.findById(sid).orElseThrow(
                    () -> new IllegalArgumentException("反驳来源不存在: " + sid));
            requireOwnership(s, claim);
        }

        // ---- TB：互斥校验——同一来源不得同时支持与反驳 ----
        Set<Long> supportingSet = new HashSet<>(supportingIds);
        for (long sid : contradictingIds) {
            if (supportingSet.contains(sid)) {
                throw new IllegalArgumentException("来源 " + sid + " 不能同时支持与反驳同一断言");
            }
        }

        // 计算新 confidence
        double confidence = 0.3; // 基准
        confidence += supportingIds.size() * 0.2;
        confidence -= contradictingIds.size() * 0.3;

        // 交叉验证奖励：>=2 个独立来源支持
        if (supportingIds.size() >= 2) {
            confidence += 0.1;
        }

        // 限制范围 [0.0, 1.0]
        confidence = Math.max(0.0, Math.min(1.0, confidence));

        // 更新状态
        String newStatus;
        if (confidence >= 0.7) {
            newStatus = "VALIDATED";
        } else if (confidence <= 0.2 && contradictingIds.size() > 0) {
            newStatus = "DISPUTED";
        } else {
            newStatus = "DRAFT";
        }

        // ---- TB：三个 UPDATE 原子提交（任一步失败整体回滚，不留中间态） ----
        final double finalConfidence = confidence;
        final String finalStatus = newStatus;
        tx.executeWithoutResult(txStatus -> {
            claimRepo.updateSourceIds(claimId, listToJson(supportingIds), listToJson(contradictingIds));
            claimRepo.updateConfidence(claimId, finalConfidence);
            claimRepo.updateStatus(claimId, finalStatus);
        });

        // R2-2：交叉验证落库后发布 CLAIM_VALIDATED 事件（Tool → AgentEventPublisher → agent_event → SSE）
        if (ctx.runId() != null) {
            ObjectNode ev = MAPPER.createObjectNode();
            ev.put("claimId", claimId);
            ev.put("status", finalStatus);
            ev.put("previousConfidence", claim.confidence());
            ev.put("newConfidence", finalConfidence);
            ev.put("supportingCount", supportingIds.size());
            ev.put("contradictingCount", contradictingIds.size());
            publisher.publish("CLAIM_VALIDATED", ctx.runId(), ev.toString());
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("claimId", claimId);
        payload.put("claim", claim.claim());
        payload.put("previousConfidence", claim.confidence());
        payload.put("newConfidence", finalConfidence);
        payload.put("supportingCount", supportingIds.size());
        payload.put("contradictingCount", contradictingIds.size());
        payload.put("status", finalStatus);

        return ToolResult.success(payload.toString(),
                "断言置信度更新: " + claim.claim()
                        + " | " + claim.confidence() + " → " + finalConfidence
                        + " | " + finalStatus);
    }

    /** TB：来源必须属于同一研究 run/task（跨研究证据链污染防护）。 */
    private void requireOwnership(ResearchSource source, ResearchClaim claim) {
        boolean owned = source.runId().equals(claim.runId())
                && source.taskId().equals(claim.taskId());
        if (!owned) {
            throw new IllegalArgumentException("来源 " + source.id()
                    + " 不属于当前研究 run/task（run=" + claim.runId()
                    + ", task=" + claim.taskId() + "），拒绝引用");
        }
    }

    private List<Long> parseLongArray(JsonNode args, String key) {
        if (!args.has(key) || args.get(key).isNull() || !args.get(key).isArray()) {
            return List.of();
        }
        return new java.util.ArrayList<>(
                new java.util.ArrayList<>() {{ // avoid IDE warning
                    for (JsonNode item : args.get(key)) {
                        add(item.asLong());
                    }
                }});
    }

    private String listToJson(List<Long> ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i));
        }
        sb.append("]");
        return sb.toString();
    }
}
