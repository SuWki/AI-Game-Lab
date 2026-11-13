package com.aigamelab.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.ChangeProposal;
import com.aigamelab.domain.ConfigVersion;
import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.repository.ChangeProposalRepository;
import com.aigamelab.runtime.RunControlService;

/**
 * P6-3：变更提案审批服务（蓝图 §14 / §15 / 03 文档 P6-3）。
 *
 * <p>与 P3-3（写类工具）通过 {@code change_proposal} 表解耦——本服务只读该表、
 * 调 {@link RunControlService#resume(long)}（P1-4 已冻结接口），不 import 任何 P3 内部实现。
 *
 * <p>语义（冻结，禁止扩展）：
 * <ul>
 *   <li>approve：仅接受 PENDING 提案 → 事务内以提案的 {@code after_config_json} 创建新 Config 版本
 *       （最新版本即当前生效版本）→ 回填提案状态 APPROVED + targetVersion → 提交后发布
 *       {@code CONFIG_VERSION_CREATED} 事件 → resume 关联 Run（WAITING_APPROVAL→RUNNING）；</li>
 *   <li>reject：提案置 REJECTED → resume 关联 Run（tool result 注入 REJECTED）。</li>
 * </ul>
 *
 * <p>不实现 activateVersion / rollbackVersion / 历史版本状态修改（见 Sprint P6 语义冻结裁决）。
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_REJECTED = "REJECTED";

    private final ChangeProposalRepository proposalRepo;
    private final ConfigVersionService configService;
    private final RunControlService runControl;
    private final AgentEventPublisher events;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ApprovalService(ChangeProposalRepository proposalRepo,
            ConfigVersionService configService,
            RunControlService runControl,
            AgentEventPublisher events,
            PlatformTransactionManager txManager) {
        this.proposalRepo = proposalRepo;
        this.configService = configService;
        this.runControl = runControl;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 批准提案：创建新版本 + 回填 + 发布事件 + 唤醒 Run。
     *
     * @return 新创建的 ConfigVersion（最新版本，即当前生效版本）
     * @throws BusinessException 提案不存在（40401）或非 PENDING（40901）
     */
    public ConfigVersion approve(long proposalId, String comment) {
        ChangeProposal proposal = proposalRepo.findById(proposalId)
                .orElseThrow(() -> BusinessException.notFound("变更提案不存在: " + proposalId));
        if (!STATUS_PENDING.equals(proposal.status())) {
            throw BusinessException.conflict(
                    "仅 PENDING 提案可被批准，当前: " + proposal.status());
        }
        if (proposal.afterConfigJson() == null || proposal.afterConfigJson().isBlank()) {
            // P3-3 负责落 after_config_json；若缺失属数据异常
            throw new IllegalStateException("变更提案缺少 after_config_json（P3-3 未落库）: " + proposalId);
        }

        // 事务内：建新版本 + 回填提案
        ConfigVersion created = tx.execute(status -> {
            ChangeProposal fresh=proposalRepo.findById(proposalId).orElseThrow();
            if(!STATUS_PENDING.equals(fresh.status()))throw BusinessException.conflict("提案已经裁决");
            if(!configService.getCurrent().version().equals(fresh.baseVersion()))throw BusinessException.conflict("提案基础版本已过期，请基于当前版本重建提案");
            ConfigVersion v = configService.createVersion(
                    proposal.afterConfigJson(), proposal.baseVersion(),
                    proposal.id(), "approve proposal #" + proposal.id());
            proposalRepo.decide(proposal.id(), STATUS_APPROVED, v.version(), comment);
            completeApprovalCall(proposal,STATUS_APPROVED,v.version());
            return v;
        });
        if (created == null) {
            throw new IllegalStateException("创建 Config 版本失败（事务未返回结果）: proposalId=" + proposalId);
        }

        // 事务提交后：发布事件 + 唤醒 Run（与 P1-4 解耦，仅调接口）
        events.publish("CONFIG_VERSION_CREATED",
                proposal.runId() == null ? 0L : proposal.runId(),
                "{\"version\":\"" + created.version()
                        + "\",\"proposalId\":" + proposal.id() + "}");
        log.info("变更提案 #{} 已批准，创建版本 {}（current=latest）", proposalId, created.version());

        // P8-2 修复：resumeIfLinked 必须在事务提交后执行，否则 async agent loop
        // 读到旧的 WAITING_APPROVAL 状态（未看到 RUNNING UPDATE）导致执行异常。
        if (proposal.runId() != null) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override public void afterCommit() {
                                resumeIfLinked(proposal);
                            }
                        });
            } else {
                resumeIfLinked(proposal);
            }
        }
        return created;
    }

    /**
     * 拒绝提案：置 REJECTED + 唤醒 Run（让 Agent 改方案再提）。
     */
    public void reject(long proposalId, String comment) {
        ChangeProposal proposal = proposalRepo.findById(proposalId)
                .orElseThrow(() -> BusinessException.notFound("变更提案不存在: " + proposalId));
        if (!STATUS_PENDING.equals(proposal.status())) {
            throw BusinessException.conflict(
                    "仅 PENDING 提案可被拒绝，当前: " + proposal.status());
        }
        tx.executeWithoutResult(status->{
            proposalRepo.decide(proposalId, STATUS_REJECTED, null, comment);
            completeApprovalCall(proposal,STATUS_REJECTED,null);
        });
        events.publish("PROPOSAL_REJECTED",
                proposal.runId() == null ? 0L : proposal.runId(),
                "{\"proposalId\":" + proposalId + "}");
        log.info("变更提案 #{} 已拒绝", proposalId);

        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){resumeIfLinked(proposal);}});
        else resumeIfLinked(proposal);
    }

    private void completeApprovalCall(ChangeProposal proposal,String decision,String version) {
        if(proposal.runId()==null)return;
        var result=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("proposalId",proposal.id()).put("approval",decision);
        if(version!=null)result.put("targetVersion",version);
        jdbc.update("UPDATE tool_call SET approval_status=?,status='COMPLETED',result_json=?,ended_at=datetime('now','localtime') WHERE run_id=? AND approval_status='PENDING' AND CASE WHEN json_valid(result_json) THEN json_extract(result_json,'$.proposalId') END=?",
                decision,result.toString(),proposal.runId(),proposal.id());
    }

    /**
     * 若提案关联 Run，则唤醒（WAITING_APPROVAL→RUNNING）。
     * 关联 Run 为空（如纯配置提案）时跳过。
     */
    private void resumeIfLinked(ChangeProposal proposal) {
        if (proposal.runId() == null) {
            return;
        }
        try {
            runControl.resume(proposal.runId());
        } catch (BusinessException e) {
            // resume 要求 Run 处于 WAITING_APPROVAL；若状态不符（如测试未挂起），仅记录不阻断审批
            log.warn("审批后唤醒 Run {} 失败（状态不符或 Run 不存在）: {}",
                    proposal.runId(), e.getMessage());
        }
    }
}
