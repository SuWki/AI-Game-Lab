package com.aigamelab.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aigamelab.common.BusinessException;
import com.aigamelab.common.ErrorCodes;
import com.aigamelab.domain.QaRoleAssignment;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.domain.Task;
import com.aigamelab.repository.QaReleaseGateRepository;
import com.aigamelab.repository.QaRoleAssignmentRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.AgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * R5 §2 / §8 / §9：QA Phase 2 唯一确定性编排服务（Option A — Service-layer orchestration）。
 *
 * <p><b>LLM 只表达意图，本服务确定性执行</b>：Task 创建、Run 创建、状态机、
 * timeout、budget、role assignment、final gate result 全部由本服务（或其委托的
 * {@link QaReleaseGateService}）决定。QA_LEAD 的 5 个编排工具只做参数提取 +
 * 合法性校验，随后把意图交给本服务。</p>
 *
 * <p><b>Sequential orchestration（freeze §7，只此一种）</b>：Lead → Player1(NOVICE)
 * → Player2(AGGRESSIVE) → Player3(BUILD_SPECIALIST) → Combat → Numerical → Exploit
 * → UX → Aggregate → Release Gate。MVP 不实现 DAG / parallel / dynamic workflow。</p>
 *
 * <p><b>Wall-clock budget（R5 §9 = 300s）</b>：deadline 由本服务管理（AgentLoop.timeoutMs
 * 保持 -1 冻结不动）。deadline 超时 → 不再启动新的 role；RUNNING role 标记 TIMEOUT；
 * gate 不得 PASS。deadline 以 lead_task 为键维护在进程内映射（MVP 单 JVM 语义，
 * 重启丢失为已知限制，见实现报告 Known Limitations）。</p>
 *
 * <p>子 Run 是普通 run 行（freeze §8），父子关联由 qa_role_assignment 承载
 * （run 表零改动，R5 §3 硬约束）。</p>
 */
@Service
public class QaOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(QaOrchestratorService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** R5 §9：编排层 wall-clock 预算（毫秒）。 */
    public static final long WALL_CLOCK_BUDGET_MS = 300_000;

    /**
     * 角色 → (AgentType, PromptVersion, PlayerProfile) 映射（freeze §B / §8.2）。
     * 顺序即编排顺序（order_no 1..7），与 freeeze §7 唯一执行顺序一致。
     */
    public static final List<String> ROLE_ORDER = List.of(
            "PLAYER_NOVICE", "PLAYER_AGGRESSIVE", "PLAYER_BUILD_SPECIALIST",
            "COMBAT_ANALYST", "NUMERICAL_ANALYST", "EXPLOIT_HUNTER", "UX_REVIEWER");

    static final Map<String, RoleSpec> ROLE_SPECS = Map.of(
            "PLAYER_NOVICE", new RoleSpec("QA_TESTER", "qa_player_novice_p001", "NOVICE"),
            "PLAYER_AGGRESSIVE", new RoleSpec("QA_TESTER", "qa_player_aggressive_p001", "AGGRESSIVE"),
            "PLAYER_BUILD_SPECIALIST", new RoleSpec("QA_TESTER", "qa_player_build_specialist_p001", "BUILD_SPECIALIST"),
            "COMBAT_ANALYST", new RoleSpec("QA_ANALYST", "qa_combat_analyst_p001", null),
            "NUMERICAL_ANALYST", new RoleSpec("QA_ANALYST", "qa_numerical_analyst_p001", null),
            "EXPLOIT_HUNTER", new RoleSpec("QA_TESTER", "qa_exploit_hunter_p001", null),
            "UX_REVIEWER", new RoleSpec("QA_RESEARCHER", "qa_ux_reviewer_p001", null));

    /** 角色规格：AgentType + Prompt 版本 + PlayerProfile（人格仅 Prompt 层表达，freeze §B）。 */
    record RoleSpec(String agentType, String promptVersion, String playerProfile) {
    }

    /** 编排 deadline 映射：leadTaskId → deadline epoch ms（首次 start_subrun 时固化）。 */
    private final Map<Long, Long> deadlines = new ConcurrentHashMap<>();

    private final TaskRepository taskRepo;
    private final RunRepository runRepo;
    private final QaRoleAssignmentRepository assignmentRepo;
    private final QaReleaseGateRepository gateRepo;
    private final QaReleaseGateService gateService;
    /** QA Phase 3（HD-1 A2）：实验数据 → dominant build share 实时派生。 */
    private final QaBuildShareDeriver buildShareDeriver;
    /** ObjectProvider 延迟注入：打破 QaOrchestratorService ↔ AgentLoop ↔ ToolRegistry 循环依赖 */
    private final org.springframework.beans.factory.ObjectProvider<AgentLoop> agentLoopProvider;

    /** 子 Run 执行器：cached pool（同 RunControlService 守护线程模式）。 */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "qa-subrun-worker");
        t.setDaemon(true);
        return t;
    });

    public QaOrchestratorService(TaskRepository taskRepo, RunRepository runRepo,
            QaRoleAssignmentRepository assignmentRepo, QaReleaseGateRepository gateRepo,
            QaReleaseGateService gateService, QaBuildShareDeriver buildShareDeriver,
            org.springframework.beans.factory.ObjectProvider<AgentLoop> agentLoopProvider) {
        this.taskRepo = taskRepo;
        this.runRepo = runRepo;
        this.assignmentRepo = assignmentRepo;
        this.gateRepo = gateRepo;
        this.gateService = gateService;
        this.buildShareDeriver = buildShareDeriver;
        this.agentLoopProvider = agentLoopProvider;
    }

    // ==================== 编排生命周期 ====================

    /**
     * R5 §8 步骤 1：为指定角色创建子任务 + assignment（PENDING）。
     *
     * @param leadTask   父 QA_LEAD Task（LLM 无权创建，由本服务承接）
     * @param leadRun    Lead Run（父子关联锚点）
     * @param role       {@link #ROLE_ORDER} 内角色
     * @param configVersion 被测配置版本
     */
    public QaRoleAssignment createSubtask(Task leadTask, Run leadRun, String role,
            String configVersion) {
        RoleSpec spec = ROLE_SPECS.get(role);
        if (spec == null) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "role 非法: " + role + "，允许值 " + ROLE_ORDER);
        }
        assignmentRepo.findByLeadTaskAndRole(leadTask.id(), role).ifPresent(existing -> {
            throw BusinessException.conflict(
                    "角色 " + role + " 已存在 assignment（leadTask=" + leadTask.id() + "）");
        });

        String constraintsJson = buildSubtaskConstraints(leadTask, role, spec, configVersion);
        String goal = roleGoal(role, leadTask.goal());
        Task sub = taskRepo.insert(new Task(null,
                "QA-[" + role + "] " + leadTask.title(), goal, constraintsJson,
                50, false, "ACTIVE", null));

        QaRoleAssignment a = assignmentRepo.insert(new QaRoleAssignment(
                null, leadRun.id(), leadTask.id(), sub.id(), null,
                role, spec.playerProfile(), "PENDING",
                ROLE_ORDER.indexOf(role) + 1, null, null, null, null, "{}", null, null));
        log.info("QA orchestration: leadTask={} role={} subtask={} assignment={}",
                leadTask.id(), role, sub.id(), a.id());
        return a;
    }

    /**
     * R5 §8 步骤 2：启动子 Run（普通 run 行，RUNNING），回填 sub_run_id。
     * deadline 超时 → 拒绝启动新 role（R5 §9）。
     */
    public Run startSubrun(Task leadTask, long subTaskId) {
        QaRoleAssignment a = assignmentRepo.findBySubTask(subTaskId).orElseThrow(
                () -> BusinessException.notFound("子任务不存在或未登记 assignment: " + subTaskId));
        if (!a.leadTaskId().equals(leadTask.id())) {
            throw BusinessException.conflict("子任务 " + subTaskId + " 不属于 leadTask " + leadTask.id());
        }
        if (!"PENDING".equals(a.status())) {
            throw BusinessException.conflict("assignment 状态非法: " + a.status() + "（仅 PENDING 可启动）");
        }
        if (deadlineExceeded(leadTask.id())) {
            throw BusinessException.conflict("编排 wall-clock deadline 已超时，不再启动新的 role");
        }

        Run subRun = runRepo.insert(new Run(null, subTaskId, "PENDING", null,
                "deepseek-chat", 0, 50, 100000, 0, null, null, null, null, null));
        // 固化 deadline（首次 start_subrun 起算）
        deadlines.putIfAbsent(leadTask.id(), System.currentTimeMillis() + WALL_CLOCK_BUDGET_MS);
        assignmentRepo.bindSubRun(a.id(), subTaskId, subRun.id(), "RUNNING", 0, 0);

        final long subRunId = subRun.id();
        executor.submit(() -> {
            try {
                agentLoopProvider.getObject().run(subRunId);
            } catch (RuntimeException e) {
                log.error("QA 子 Run {} 执行失败", subRunId, e);
            }
        });
        log.info("QA orchestration: leadTask={} role={} subRun={} 已启动",
                leadTask.id(), a.role(), subRunId);
        return subRun;
    }

    /**
     * R5 §8 步骤 3：轮询子 Run 状态（read-only）。
     * 观察到终态时回写 assignment（COMPLETED/FAILED/TIMEOUT/CANCELLED + result_summary，
     * freeze §8 A7：以 run 行最新状态为准）。
     * poll_seq 由工具层传入递增参数，避免 LoopDetector WINDOW=3 误判（R5 §11）。
     *
     * <p><b>归属校验</b>（Phase 2 Isolation Hardening）：回写前验证 assignment.leadTaskId
     * 与调用者 leadTask 一致，防止跨 lead 写回。</p>
     */
    public Run checkSubrunStatus(Task leadTask, long subRunId) {
        Run subRun = runRepo.findById(subRunId).orElseThrow(
                () -> BusinessException.notFound("子 Run 不存在: " + subRunId));
        // 子 Run 是普通 run 行；经 sub_run_id 或子 task 反查 assignment
        QaRoleAssignment a = assignmentRepo.findBySubTask(subRun.taskId()).orElse(null);
        if (a == null) {
            // fallback: 通过 leadTask 关联查找（R7 QA-03 修正：用 findByLeadTask 而非 findByParentRun）
            a = assignmentRepo.findByLeadTask(leadTask.id())
                    .stream().filter(x -> subRunId == (x.subRunId() == null ? -1 : x.subRunId()))
                    .findFirst().orElse(null);
        }
        // Phase 2 归属校验：assignment 必须属于当前 leadTask（§14 隔离）
        if (a != null && !leadTask.id().equals(a.leadTaskId())) {
            throw BusinessException.notFound("子 Run " + subRunId + " 不属于 leadTask " + leadTask.id());
        }

        // 终态回写 assignment（若尚未回写）
        if (a != null && isTerminal(subRun.status()) && !subRun.status().equals(a.status())) {
            assignmentRepo.updateStatus(a.id(), subRun.status(),
                    subRun.finalResult(), subRun.errorMessage());
            log.info("QA orchestration: role={} assignment 回写 → {}", a.role(), subRun.status());
        }
        return subRun;
    }

    /**
     * R5 §8 步骤 5：聚合跨角色 Finding（复用 FindingService.findByQaFilter）。
     * 只汇总有产出的角色（部分结果语义，freeze §10）。
     */
    public List<com.aigamelab.domain.Finding> collectFindings(
            com.aigamelab.tester.FindingService findingService, Task leadTask) {
        List<Long> runIds = new ArrayList<>();
        for (QaRoleAssignment a : assignmentRepo.findByLeadTask(leadTask.id())) {
            if (a.subRunId() != null && "COMPLETED".equals(a.status())) {
                runIds.add(a.subRunId());
            }
        }
        if (runIds.isEmpty()) {
            return List.of();
        }
        return findingService.findByQaFilter(new com.aigamelab.domain.FindingFilter(
                null, null, null, null, null, runIds, null, null));
    }

    /**
     * R5 §8 步骤 6/7 + §10：触发 Release Gate 计算并落库。
     * deadline 超时时先冻结 RUNNING assignment 为 TIMEOUT（gate 不得 PASS）。
     */
    public com.aigamelab.domain.QaReleaseGate completeOrchestration(Task leadTask, Run leadRun,
            com.aigamelab.tester.FindingService findingService,
            String configVersion, String leadDecision, String leadDecisionEvidenceJson) {
        if (deadlineExceeded(leadTask.id())) {
            freezeRunningToTimeout(leadTask.id());
        }
        List<QaRoleAssignment> assignments = assignmentRepo.findByLeadTask(leadTask.id());
        List<com.aigamelab.domain.Finding> findings = collectFindings(findingService, leadTask);
        // QA Phase 3（HD-1 A2 / HD-4）：evaluate-time 实时派生 dominant build share，
        // 作为第 6 参数传入 6-arg evaluate（纯函数保持；缺失 → null，Gate 聚合不变）。
        Double dominantBuildShare = buildShareDeriver.deriveDominantBuildShare(configVersion);
        QaReleaseGateService.GateResult result = gateService.evaluate(
                leadTask.id(), configVersion, assignments, findings, leadDecision,
                dominantBuildShare);

        com.aigamelab.domain.QaReleaseGate gate = gateRepo
                .findByLeadTaskAndConfig(leadTask.id(), configVersion)
                .orElseGet(() -> gateRepo.insert(new com.aigamelab.domain.QaReleaseGate(
                        null, leadTask.id(), leadRun.id(), configVersion, "PENDING",
                        null, gateService.RULE_VERSION, null, "{}", null,
                        1, 0, 0, null, null, null,
                        0, 0, 0, 0, null, null, "{}", null, null, null)));

        gateRepo.decide(gate.id(), result.automaticResult(), result.automaticResult(),
                gateService.RULE_VERSION, leadDecision, leadDecisionEvidenceJson,
                result.consistent() ? 1 : 0, result.inconclusiveCount(),
                result.criticalBugs(), result.highBugs(), result.mediumBugs(),
                result.softLockCount(), result.crashRate(), result.dominantBuildShare(),
                result.statsJson());
        log.info("QA orchestration: leadTask={} gate={} automatic={}",
                leadTask.id(), gate.id(), result.automaticResult());
        return gateRepo.findById(gate.id()).orElseThrow();
    }

    // ==================== 内部工具 ====================

    private boolean deadlineExceeded(long leadTaskId) {
        Long deadline = deadlines.get(leadTaskId);
        return deadline != null && System.currentTimeMillis() > deadline;
    }

    /**
     * 测试专用：固化/改写编排 deadline（Phase 2 deadline 治理测试）。
     * 生产路径首次 start_subrun 即固化 deadline，本钩子仅用于测试提前构造超时场景，
     * 不改变生产语义（deadline 仍由 start_subrun 首次写入）。
     */
    void setDeadlineForTest(long leadTaskId, long deadlineEpochMs) {
        deadlines.put(leadTaskId, deadlineEpochMs);
    }

    /** deadline 超时：RUNNING assignment → TIMEOUT（编排视图；子 Run 自身状态不改，freeze §9）。 */
    private void freezeRunningToTimeout(long leadTaskId) {
        for (QaRoleAssignment a : assignmentRepo.findByLeadTask(leadTaskId)) {
            if ("RUNNING".equals(a.status())) {
                assignmentRepo.updateStatus(a.id(), "TIMEOUT", null, "编排 wall-clock deadline 超时");
                log.warn("QA orchestration: role={} 因 deadline 超时标记 TIMEOUT", a.role());
            }
        }
    }

    private static boolean isTerminal(String s) {
        return "COMPLETED".equals(s) || "FAILED".equals(s)
                || "TIMEOUT".equals(s) || "CANCELLED".equals(s);
    }

    /** 子任务 constraintsJson：agent_type + prompt_version + qa_role + config_version + 溯源。 */
    private String buildSubtaskConstraints(Task leadTask, String role, RoleSpec spec,
            String configVersion) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("agent_type", spec.agentType());
        n.put("prompt_version", spec.promptVersion());
        n.put("qa_role", role);
        n.put("config_version", configVersion);
        n.put("lead_task_id", leadTask.id());
        if (spec.playerProfile() != null) {
            n.put("player_profile", spec.playerProfile());
        }
        return n.toString();
    }

    private static String roleGoal(String role, String leadGoal) {
        return switch (role) {
            case "PLAYER_NOVICE" -> "以保守/常规玩家方式验证: " + leadGoal;
            case "PLAYER_AGGRESSIVE" -> "以激进/压力操作方式验证: " + leadGoal;
            case "PLAYER_BUILD_SPECIALIST" -> "专精单一 Build 深度挖掘: " + leadGoal;
            case "COMBAT_ANALYST" -> "战斗数值/平衡分析: " + leadGoal;
            case "NUMERICAL_ANALYST" -> "数值/统计/实验分析: " + leadGoal;
            case "EXPLOIT_HUNTER" -> "漏洞/伪造/负值/NaN/卡死挖掘: " + leadGoal;
            case "UX_REVIEWER" -> "基于 Finding+Experiment 的 UX 评估: " + leadGoal;
            default -> leadGoal;
        };
    }
}
