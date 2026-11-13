package com.aigamelab.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aigamelab.common.ApiResp;
import com.aigamelab.config.ApprovalService;
import com.aigamelab.config.ConfigVersionService;
import com.aigamelab.domain.ChangeProposal;
import com.aigamelab.domain.ConfigVersion;
import com.aigamelab.repository.ChangeProposalRepository;

/**
 * P6-4：Config 版本 / 提案 REST Adapter（03 文档 P6-4，TD-005 已解除）。
 *
 * <p>本 Controller 仅做 HTTP 适配，<b>不实现任何 Config 业务逻辑</b>：
 * <ul>
 *   <li>GET /api/config/versions — 历史版本列表（最新优先，复用 ConfigVersionService.findAll）</li>
 *   <li>GET /api/config/proposals — 变更提案列表（按 status 可选过滤，复用 ChangeProposalRepository.findByStatus）</li>
 *   <li>POST /api/config/proposals/{id}/approve — 复用 ApprovalService.approve()</li>
 *   <li>POST /api/config/proposals/{id}/reject — 复用 ApprovalService.reject()</li>
 * </ul>
 *
 * <p>红线（P6-4 裁决）：
 * <ul>
 *   <li>不实现 diff / merge / 状态转换 / 版本创建 / 审批逻辑 / Run resume / 事件发布——全部委托既有 Service；</li>
 *   <li>不改 schema、不改 Repository 接口契约、不改 Error Contract；</li>
 *   <li>ConfigVersion 语义冻结：append-only 全量快照，current = findLatest()，无 active / activateVersion / rollbackVersion；</li>
 *   <li>排序在 Controller 层处理（findAll 返回升序，这里倒序），不修改 Repository 契约；</li>
 *   <li>无 status 查询：按既有 closed 状态集合（PENDING/APPROVED/REJECTED，见 schema 注释）逐一 findByStatus 并集，
 *       不新增 Repository 方法。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    /** change_proposal.status 的 closed 集合（schema 注释：PENDING/APPROVED/REJECTED）。 */
    private static final List<String> ALL_STATUSES = List.of("PENDING", "APPROVED", "REJECTED");

    private final ConfigVersionService configService;
    private final ChangeProposalRepository proposalRepo;
    private final ApprovalService approval;

    public ConfigController(ConfigVersionService configService,
            ChangeProposalRepository proposalRepo,
            ApprovalService approval) {
        this.configService = configService;
        this.proposalRepo = proposalRepo;
        this.approval = approval;
    }

    /**
     * 历史版本列表（最新优先）。
     * 复用 ConfigVersionService.findAll()（Repository 返回升序），Controller 层倒序以满足"最新优先"。
     */
    @GetMapping("/versions")
    @SuppressWarnings("null")
    public ApiResp<List<ConfigVersion>> versions() {
        List<ConfigVersion> all = new ArrayList<>(configService.findAll());
        @SuppressWarnings("null")
        Comparator<ConfigVersion> byVersionDesc = Comparator.comparing(ConfigVersion::version).reversed();
        all.sort(byVersionDesc);
        return ApiResp.ok(all);
    }

    /**
     * 变更提案列表。
     * 支持 status 过滤（PENDING 等）；无 status 时返回全部（按 closed 状态集合并集，复用既有 findByStatus）。
     * 不新增 Repository 方法。
     */
    @GetMapping("/proposals")
    public ApiResp<List<ChangeProposal>> proposals(
            @RequestParam(required = false) String status) {
        List<ChangeProposal> list;
        if (status == null || status.isBlank()) {
            list = new ArrayList<>();
            for (String s : ALL_STATUSES) {
                list.addAll(proposalRepo.findByStatus(s));
            }
        } else {
            list = proposalRepo.findByStatus(status);
        }
        return ApiResp.ok(list);
    }

    /**
     * 批准提案：仅委托 ApprovalService.approve()。
     * 由 P6-3 已验收逻辑完成：创建新 ConfigVersion（current=latest）、回填、发布事件、唤醒 Run。
     * 异常（不存在 40401 / 非 PENDING 40901）由 GlobalExceptionHandler 原样透传。
     */
    @PostMapping("/proposals/{id}/approve")
    public ApiResp<ConfigVersion> approve(
            @PathVariable long id,
            @RequestBody(required = false) DecisionRequest body) {
        String comment = body == null ? null : body.comment();
        ConfigVersion created = approval.approve(id, comment);
        return ApiResp.ok(created);
    }

    /**
     * 拒绝提案：仅委托 ApprovalService.reject()。
     * 由 P6-3 已验收语义完成：REJECTED、targetVersion=null、不产生新版本、不修改历史。
     */
    @PostMapping("/proposals/{id}/reject")
    public ApiResp<Void> reject(
            @PathVariable long id,
            @RequestBody(required = false) DecisionRequest body) {
        String comment = body == null ? null : body.comment();
        approval.reject(id, comment);
        return ApiResp.ok();
    }

    /** approve/reject 可选请求体（仅携带审批意见）。 */
    public record DecisionRequest(String comment) {
    }
}
