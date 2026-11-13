package com.aigamelab.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aigamelab.common.ApiResp;
import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.DesignDocument;
import com.aigamelab.domain.ResearchClaim;
import com.aigamelab.domain.ResearchSource;
import com.aigamelab.repository.DesignDocumentRepository;
import com.aigamelab.repository.ResearchClaimRepository;
import com.aigamelab.repository.ResearchSourceRepository;

/**
 * R1-2：Research Agent REST API。
 *
 * <p>使 R0 产出的研究数据（来源 / 断言 / 报告）可通过 REST 查询，
 * 补全 R0 Goal #5（研究产出可通过 API 查询、通过 SSE 观测）。
 *
 * <p>端点均为只读查询，不修改冻结契约；研究过程由 Agent 工具写入，
 * 本 Controller 只做安全只读暴露。
 */
@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private static final String REPORT_PREFIX = "[RESEARCH REPORT]";

    private final ResearchSourceRepository sourceRepo;
    private final ResearchClaimRepository claimRepo;
    private final DesignDocumentRepository docRepo;

    public ResearchController(ResearchSourceRepository sourceRepo,
            ResearchClaimRepository claimRepo,
            DesignDocumentRepository docRepo) {
        this.sourceRepo = sourceRepo;
        this.claimRepo = claimRepo;
        this.docRepo = docRepo;
    }

    // ==================== Sources ====================

    /** 按 run 或 task 列出研究来源（二选一过滤）。 */
    @GetMapping("/sources")
    public ApiResp<List<ResearchSource>> listSources(
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) Long taskId) {
        requireScoped(runId, taskId);
        List<ResearchSource> list = runId != null
                ? sourceRepo.findByRunId(runId)
                : sourceRepo.findByTaskId(taskId);
        return ApiResp.ok(list);
    }

    @GetMapping("/sources/{id}")
    public ApiResp<ResearchSource> getSource(@PathVariable long id) {
        return ApiResp.ok(sourceRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("ResearchSource 不存在: " + id)));
    }

    // ==================== Claims ====================

    /** 按 run 或 task 列出研究断言，可选 status 过滤（DRAFT/VALIDATED/DISPUTED）。 */
    @GetMapping("/claims")
    public ApiResp<List<ResearchClaim>> listClaims(
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String status) {
        requireScoped(runId, taskId);
        List<ResearchClaim> list = runId != null
                ? claimRepo.findByRunId(runId)
                : claimRepo.findByTaskId(taskId);
        if (status != null && !status.isBlank()) {
            String expected = status;
            list = list.stream()
                    .filter(c -> expected.equalsIgnoreCase(c.status()))
                    .toList();
        }
        return ApiResp.ok(list);
    }

    @GetMapping("/claims/{id}")
    public ApiResp<ResearchClaim> getClaim(@PathVariable long id) {
        return ApiResp.ok(claimRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("ResearchClaim 不存在: " + id)));
    }

    /**
     * TD：断言 → 证据链 drill-down。
     *
     * 返回断言及其支持/反驳来源对象（完整 research_source 行）。
     * 数据模型：supporting_source_ids / contradicting_source_ids 是
     * research_source.id 的软引用（JSON 数组文本），此处解析并回填来源详情。
     *
     * 安全约束：
     * - 断言不存在 → 40401；
     * - 来源解析严格限定在断言所属 run/task 内（所有权过滤），
     *   杜绝跨研究引用的来源被回显（证据链污染防护，与 TB 写路径一致）；
     * - 若引用的 source id 已不存在，静默跳过该条（软引用无 FK，允许孤儿）。
     */
    @GetMapping("/claims/{id}/sources")
    public ApiResp<ClaimSources> getClaimSources(@PathVariable long id) {
        ResearchClaim claim = claimRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("ResearchClaim 不存在: " + id));

        List<ResearchSource> supporting = resolveSources(claim, claim.supportingSourceIds());
        List<ResearchSource> contradicting = resolveSources(claim, claim.contradictingSourceIds());
        return ApiResp.ok(new ClaimSources(claim, supporting, contradicting));
    }

    /** TD：将 claim 的来源 id JSON 文本解析为同一 run/task 内的来源对象。 */
    private List<ResearchSource> resolveSources(ResearchClaim claim, String rawIds) {
        List<ResearchSource> result = new ArrayList<>();
        if (rawIds == null || rawIds.isBlank()) {
            return result;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(rawIds);
        while (m.find()) {
            long sid = Long.parseLong(m.group());
            sourceRepo.findById(sid)
                    // 所有权过滤：仅回显属于同一研究 run/task 的来源
                    .filter(s -> s.runId().equals(claim.runId())
                            && s.taskId().equals(claim.taskId()))
                    .ifPresent(result::add);
        }
        return result;
    }

    /** TD：断言 + 证据来源详情。 */
    public record ClaimSources(
            ResearchClaim claim,
            List<ResearchSource> supporting,
            List<ResearchSource> contradicting) {
    }

    // ==================== Reports ====================

    /**
     * 列出研究报告（design_document 中 title 前缀 {@code [RESEARCH REPORT]} 的行）。
     * 按 run 或 task 二选一过滤。
     */
    @GetMapping("/reports")
    public ApiResp<List<DesignDocument>> listReports(
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) Long taskId) {
        requireScoped(runId, taskId);
        List<DesignDocument> docs = runId != null
                ? docRepo.findByRunId(runId)
                : docRepo.findByTaskId(taskId);
        List<DesignDocument> reports = docs.stream()
                .filter(d -> d.title() != null && d.title().startsWith(REPORT_PREFIX))
                .toList();
        return ApiResp.ok(reports);
    }

    private static void requireScoped(Long runId, Long taskId) {
        if (runId == null && taskId == null) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "runId 或 taskId 至少提供一个（二选一过滤）");
        }
    }
}