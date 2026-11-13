package com.aigamelab.qa;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aigamelab.domain.Finding;
import com.aigamelab.domain.QaReleaseGate;
import com.aigamelab.domain.QaRoleAssignment;

/**
 * R5 §10 / freeze §13：Release Gate 确定性计算（阈值驱动 automatic_result）。
 *
 * <p>判定公式（MVP 阈值草案，freeze §13.3；QA-S3 可校准）：</p>
 * <ol>
 *   <li><b>FAIL</b>：critical_bugs &gt; 0 OR high_bugs ≥ 3 OR soft_lock_count &gt; 0
 *       OR dominant_build_share 失衡（MVP 0.75 → FAIL）</li>
 *   <li><b>INCONCLUSIVE</b>：任何 child TIMEOUT（红线：child timeout ≠ PASS）；
 *       任何 key role（Exploit/UX）FAILED/CANCELLED/missing → 关键 evidence 缺失；
 *       关键角色存在但未 COMPLETED → 待完成；
 *       build share 证据缺失（share=null）→ 证据缺口（Phase 3 HD-5，never PASS）</li>
 *   <li><b>CONDITIONAL_PASS</b>：仅 medium-and-below 问题存在且 lead_decision 建议；
 *       无 lead_decision 时降为 INCONCLUSIVE（pending 人确认）</li>
 *   <li><b>PASS</b>：全部阈值通过 + 关键角色全部完成（Exploit/UX 至少注册并产出或明确 N-A）</li>
 * </ol>
 *
 * <p>判定优先级：hard FAIL（缺陷统计） &gt; TIMEOUT（关键 evidence 中断） &gt;
 * FAILED/CANCELLED（缺失 role result） &gt; 关键角色未 COMPLETED &gt; medium 问题 &gt; PASS。</p>
 *
 * <p>红线（R5 §10 特别禁用）：Child timeout → Parent PASS 任何路径均不被实现。</p>
 *
 * <p><b>QA Phase 3（HD-1 A2 / HD-4 / HD-5）</b>：dominantBuildShare 由
 * {@link QaBuildShareDeriver}（注入到 QaOrchestratorService）从实验数据实时计算后作为
 * 第 6 参数传入本服务的 6-arg evaluate() 重载。5-arg evaluate() 委托 6-arg 并传 null，
 * 保持 16 个既有调用点编译兼容。</p>
 *
 * <p><b>HD-5 语义</b>：build share 证据缺失（share=null，如无实验/实验未 DONE/解析失败）→
 * 判为 INCONCLUSIVE（never PASS），作为规则链中的一个证据缺口（step 4c）：
 * <ul>
 *   <li>缺省 share 在 4b 之后、CONDITIONAL_PASS 之前判定 → 绝不 PASS/CONDITIONAL_PASS；</li>
 *   <li>不无条件强制全 Gate INCONCLUSIVE——step 1 hard FAIL / step 2 TIMEOUT / step 3
 *       FAILED/CANCELLED / step 4 missing role / step 4b key role 未完成仍按原优先级先行；</li>
 *   <li>dominantBuildShare=null 如实上报（不编造数值），statsJson 记 null。</li>
 * </ul></p>
 */
@Service
public class QaReleaseGateService {

    private static final Logger log = LoggerFactory.getLogger(QaReleaseGateService.class);

    /** 判定公式版本（QA-S3 Phase 3 接线 build-share 后 bump）。 */
    public static final String RULE_VERSION = "QA-S3-20260917";

    /** high_bugs 阈值（freeze §13.3：建议 ≥3）。 */
    static final int HIGH_BUGS_THRESHOLD = 3;

    /** dominant_build_share 失衡阈值（MVP 草案，QA-S3 可校准）。 */
    static final double DOMINANT_BUILD_IMBALANCE_THRESHOLD = 0.75;

    /** PASS 要求的关键角色集合（freeze §13.3：Exploit/UX 至少注册并产出或明确 N-A）。 */
    static final List<String> KEY_ROLES_FOR_PASS = List.of("EXPLOIT_HUNTER", "UX_REVIEWER");

    /** 完整 7 角色（排除 lead，lead 不在 assignment）。 */
    static final List<String> ALL_SEVEN_ROLES = List.of(
            "PLAYER_NOVICE", "PLAYER_AGGRESSIVE", "PLAYER_BUILD_SPECIALIST",
            "COMBAT_ANALYST", "NUMERICAL_ANALYST", "EXPLOIT_HUNTER", "UX_REVIEWER");

    /**
     * Gate 判定结果（服务层返回给 orchestrator 用以持久化）。
     */
    public record GateResult(
            String automaticResult,   // PASS / CONDITIONAL_PASS / FAIL / INCONCLUSIVE
            boolean consistent,       // auto vs lead 一致
            int inconclusiveCount,    // evidence 缺失计数
            int criticalBugs,
            int highBugs,
            int mediumBugs,
            int softLockCount,
            Double crashRate,
            Double dominantBuildShare,
            String statsJson) {
    }

    /**
     * 确定性 Gate 公式（纯函数，可单元测试）——5-arg 委托版本（保持既有调用点编译兼容）。
     * 传 null dominantBuildShare → step 4c INCONCLUSIVE（HD-5：missing share never PASS）。
     */
    public GateResult evaluate(long leadTaskId, String configVersion,
            List<QaRoleAssignment> assignments, List<Finding> findings, String leadDecision) {
        return evaluate(leadTaskId, configVersion, assignments, findings, leadDecision, null);
    }

    /**
     * 确定性 Gate 公式（纯函数，可单元测试）——6-arg 完整版本（QA Phase 3 新增）。
     *
     * <p>与 5-arg 版本逻辑完全相同，额外消费 dominantBuildShare 参数（HD-4：evaluate-time
     * 实时 Derived，无缓存/异步/SSE/API）。null 表示 build share 证据缺失（HD-5）：
     * step 4c 判 INCONCLUSIVE（never PASS），不无条件强制全 Gate——hard FAIL 及既有
     * evidence-gap 判据仍按原优先级先行。</p>
     *
     * @param dominantBuildShare 主导 build 的游戏局数占比（max(games)/sum(games)）；
     *                          null 表示数据不可用（由 QaBuildShareDeriver 在 orchestrator 层计算）
     */
    public GateResult evaluate(long leadTaskId, String configVersion,
            List<QaRoleAssignment> assignments, List<Finding> findings, String leadDecision,
            Double dominantBuildShare) {

        GateStats stats = countStats(findings, dominantBuildShare);
        boolean hasAnyTimeout = assignments.stream()
                .anyMatch(a -> "TIMEOUT".equals(a.status()));
        boolean hasAnyFailedOrCancelled = assignments.stream()
                .anyMatch(a -> "FAILED".equals(a.status()) || "CANCELLED".equals(a.status()));
        int missingRoleCount = countMissingRoles(assignments);
        boolean keyRolesComplete = areKeyRolesComplete(assignments);

        // 1. Hard FAIL：缺陷统计触发
        if (stats.criticalBugs > 0 || stats.highBugs >= HIGH_BUGS_THRESHOLD
                || stats.softLockCount > 0 || isBuildShareImbalanced(stats.dominantBuildShare)) {
            return buildResult("FAIL", false,
                    hasAnyTimeout ? 1 : 0, missingRoleCount, stats);
        }

        // 2. TIMEOUT → INCONCLUSIVE（红线：child timeout ≠ PASS）
        if (hasAnyTimeout) {
            return buildResult("INCONCLUSIVE", false,
                    1 + missingRoleCount, missingRoleCount, stats);
        }

        // 3. FAILED/CANCELLED → INCONCLUSIVE（缺失 role result ≠ PASS）
        if (hasAnyFailedOrCancelled) {
            return buildResult("INCONCLUSIVE", false,
                    missingRoleCount + countTerminalNonComplete(assignments), missingRoleCount, stats);
        }

        // 4. 缺失角色（lead 未为其 create_subtask）
        if (missingRoleCount > 0) {
            return buildResult("INCONCLUSIVE", false, missingRoleCount, missingRoleCount, stats);
        }

        // 4b. key role 未 COMPLETED → INCONCLUSIVE（freeze §13.3 #4；CONDITIONAL_PASS 前必须检查）
        if (!keyRolesComplete) {
            return buildResult("INCONCLUSIVE", false, 0, 0, stats);
        }

        // 4c. build share 证据缺失（无实验 / 非 DONE / 解析失败 → share=null）→ INCONCLUSIVE
        //     （QA Phase 3 HD-5：missing build share never PASS）。
        //     置于 4b 之后、CONDITIONAL_PASS/PASS 之前：hard FAIL（step 1）与既有
        //     evidence-gap 判据（step 2/3/4/4b）保持原优先级，不无条件强制全 Gate。
        if (dominantBuildShare == null) {
            return buildResult("INCONCLUSIVE", false, 1, 0, stats);
        }

        // 5. CONDITIONAL_PASS：仅 medium-and-below + lead 建议
        if (stats.mediumBugs > 0) {
            String normalizedLead = normalizeLeadDecision(leadDecision);
            if ("CONDITIONAL_PASS".equals(normalizedLead)) {
                return buildResult("CONDITIONAL_PASS", true, 0, 0, stats);
            }
            return buildResult("INCONCLUSIVE", false, 0, 0, stats);
        }

        // 6. PASS：全部阈值通过（step 4b 保证 keyRolesComplete=true）
        String normalizedLead2 = normalizeLeadDecision(leadDecision);
        return buildResult("PASS", "PASS".equals(normalizedLead2), 0, 0, stats);
    }

    // ==================== 内部计算 ====================

    record GateStats(int criticalBugs, int highBugs, int mediumBugs, int softLockCount,
                     Double crashRate, Double dominantBuildShare, String statsJson) {
    }

    private GateStats countStats(List<Finding> findings, Double dominantBuildShare) {
        int critical = 0, high = 0, medium = 0, softLock = 0, crash = 0;
        for (Finding f : findings) {
            switch (f.severity()) {
                case "BLOCKER" -> critical++;
                case "HIGH" -> high++;
                case "MEDIUM" -> medium++;
                default -> { /* LOW/INFO ignored for thresholds */ }
            }
            if ("CRASH".equals(f.category())) crash++;
            if ("SOFT_LOCK".equals(f.category())) softLock++;
        }
        Double crashRate = findings.isEmpty() ? null : (double) crash / findings.size();
        String shareText = dominantBuildShare == null ? "null"
                : String.format("%.6f", dominantBuildShare);
        return new GateStats(critical, high, medium, softLock, crashRate, dominantBuildShare,
                "{\"totalFindings\":" + findings.size()
                        + ",\"critical\":" + critical
                        + ",\"high\":" + high
                        + ",\"medium\":" + medium
                        + ",\"softLock\":" + softLock
                        + ",\"crashCount\":" + crash
                        + ",\"dominantBuildShare\":" + shareText + "}");
    }

    private static boolean isBuildShareImbalanced(Double share) {
        return share != null && share > DOMINANT_BUILD_IMBALANCE_THRESHOLD;
    }

    private static int countMissingRoles(List<QaRoleAssignment> assignments) {
        int count = 0;
        for (String role : ALL_SEVEN_ROLES) {
            boolean found = assignments.stream().anyMatch(a -> role.equals(a.role()));
            if (!found) count++;
        }
        return count;
    }

    private static boolean areKeyRolesComplete(List<QaRoleAssignment> assignments) {
        for (String keyRole : KEY_ROLES_FOR_PASS) {
            boolean completed = assignments.stream()
                    .anyMatch(a -> keyRole.equals(a.role()) && "COMPLETED".equals(a.status()));
            if (!completed) return false;
        }
        return true;
    }

    /** 非 COMPLETED 的终态角色计数（FAILED/CANCELLED/TIMEOUT）。 */
    private static int countTerminalNonComplete(List<QaRoleAssignment> assignments) {
        return (int) assignments.stream()
                .filter(a -> !"COMPLETED".equals(a.status()) && !"PENDING".equals(a.status())
                        && !"RUNNING".equals(a.status()))
                .count();
    }

    /**
     * Lead 决策文本归一化：识别自动判定标记。
     * 自由文本中包含的判定标签优先，忽略大小写/空白。
     */
    private static String normalizeLeadDecision(String text) {
        if (text == null || text.isBlank()) return null;
        String upper = text.toUpperCase();
        if (upper.contains("INCONCLUSIVE")) return "INCONCLUSIVE";
        if (upper.contains("CONDITIONAL_PASS") || upper.contains("CONDITIONAL PASS")
                || upper.contains("CONDITIONAL-PASS")) return "CONDITIONAL_PASS";
        if (upper.contains("PASS")) return "PASS";
        if (upper.contains("FAIL")) return "FAIL";
        return null;
    }

    private static GateResult buildResult(String automaticResult, boolean consistent,
            int inconclusiveCount, int missingCount, GateStats stats) {
        return new GateResult(automaticResult, consistent,
                Math.max(inconclusiveCount, missingCount),
                stats.criticalBugs(), stats.highBugs(), stats.mediumBugs(),
                stats.softLockCount(), stats.crashRate(), stats.dominantBuildShare(),
                stats.statsJson());
    }
}
