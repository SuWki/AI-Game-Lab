package com.aigamelab.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aigamelab.domain.Finding;
import com.aigamelab.domain.QaRoleAssignment;

/**
 * R5 §10 / freeze §13.3：Release Gate 确定性公式单元测试（纯函数，无 Spring）。
 *
 * <p>验证矩阵（优先级：hard FAIL &gt; TIMEOUT &gt; FAILED/CANCELLED &gt; missing role
 * &gt; key role 未 COMPLETED &gt; medium-only &gt; PASS）：
 * <ul>
 *   <li>A. PASS：全角色 COMPLETED + 无缺陷 + lead PASS</li>
 *   <li>B. FAIL：critical&gt;0 / high≥3 / soft_lock&gt;0</li>
 *   <li>C. INCONCLUSIVE：任何 TIMEOUT（红线：child timeout ≠ PASS）</li>
 *   <li>D. INCONCLUSIVE：FAILED/CANCELLED / 缺失角色</li>
 *   <li>E. CONDITIONAL_PASS：仅 medium + lead CONDITIONAL_PASS；无 lead 时 INCONCLUSIVE</li>
 *   <li>F. is_consistent 语义（auto vs lead 一致标记）</li>
 *   <li>H. QA Phase 3 build share（HD-2/3/5）：6-arg evaluate 重载输入 share</li>
 * </ul>
 *
 * <p><b>HD-5</b>：share=null（build share 证据缺失）→ step 4c 判 INCONCLUSIVE（never PASS）；
 * 不无条件强制全 Gate——step 1 hard FAIL 与既有 evidence-gap 判据（step 2/3/4/4b）保持
 * 原优先级先行。规则链测试（A-F）显式传均衡 share（{@link #BALANCED_SHARE}）以聚焦链本身；
 * build-share 语义（HD-2/3/5）由 H 组覆盖。</p>
 */
@DisplayName("R5 §10 Release Gate 公式单元测试（QA Phase 3 build share）")
class QaReleaseGateServiceTest {

    private final QaReleaseGateService svc = new QaReleaseGateService();

    /** 3-build 均衡基准（max/Σ = 1/3）：规则链测试（A-F）用于"build share 证据存在且均衡"。 */
    private static final double BALANCED_SHARE = 1.0 / 3.0;

    // ==================== 数据工厂 ====================

    private static QaRoleAssignment done(String role) {
        return new QaRoleAssignment(null, 1L, 1L, null, null, role, null, "COMPLETED",
                1, null, null, null, null, "{}", null, null);
    }

    private static QaRoleAssignment withStatus(String role, String status) {
        return new QaRoleAssignment(null, 1L, 1L, null, null, role, null, status,
                1, null, null, null, null, "{}", null, null);
    }

    private static Finding finding(String severity, String category) {
        return new Finding(null, 1L, null, 1L, "sc-1", "v001", severity, category,
                "TEST", "t-" + severity + "-" + category, null, null,
                "desc", null, null, null, null);
    }

    /** 全 7 角色 COMPLETED 的标准 assignment 列表。 */
    private static List<QaRoleAssignment> allCompleted() {
        List<QaRoleAssignment> list = new ArrayList<>();
        for (String role : new String[]{
                "PLAYER_NOVICE", "PLAYER_AGGRESSIVE", "PLAYER_BUILD_SPECIALIST",
                "COMBAT_ANALYST", "NUMERICAL_ANALYST", "EXPLOIT_HUNTER", "UX_REVIEWER"}) {
            list.add(done(role));
        }
        return list;
    }

    // ==================== A. PASS ====================

    @Test
    @DisplayName("A1: 全角色 COMPLETED + 无缺陷 + 均衡 share + lead PASS → PASS (consistent)")
    void pass_whenCleanAndLeadPass() {
        var r = svc.evaluate(1, "v001", allCompleted(), List.of(), "建议: PASS", BALANCED_SHARE);
        assertThat(r.automaticResult()).isEqualTo("PASS");
        assertThat(r.consistent()).isTrue();
        assertThat(r.inconclusiveCount()).isZero();
    }

    @Test
    @DisplayName("A2: 全角色 COMPLETED + 无缺陷 + 均衡 share + lead 未表态 → PASS (consistent=false，audit 标记)")
    void pass_evenWithoutLeadSignalButMarkedInconsistent() {
        var r = svc.evaluate(1, "v001", allCompleted(), List.of(), null, BALANCED_SHARE);
        assertThat(r.automaticResult()).isEqualTo("PASS");
        assertThat(r.consistent()).isFalse();
    }

    @Test
    @DisplayName("A3: LOW/INFO Finding 不影响 PASS（阈值只统计 BLOCKER/HIGH/MEDIUM）")
    void pass_withOnlyLowAndInfoFindings() {
        var r = svc.evaluate(1, "v001", allCompleted(),
                List.of(finding("LOW", "BALANCE_ANOMALY"), finding("INFO", "OTHER")),
                "PASS", BALANCED_SHARE);
        assertThat(r.automaticResult()).isEqualTo("PASS");
    }

    // ==================== B. FAIL ====================

    @Test
    @DisplayName("B1: critical(BLOCKER)&gt;0 → FAIL（即使其他全绿）")
    void fail_onCriticalBug() {
        var r = svc.evaluate(1, "v001", allCompleted(),
                List.of(finding("BLOCKER", "CRASH")), "PASS");
        assertThat(r.automaticResult()).isEqualTo("FAIL");
        assertThat(r.criticalBugs()).isEqualTo(1);
        assertThat(r.consistent()).isFalse();
    }

    @Test
    @DisplayName("B2: high_bugs ≥ 3 → FAIL（阈值 3，含等于）")
    void fail_onHighBugThreshold() {
        var r = svc.evaluate(1, "v001", allCompleted(), List.of(
                finding("HIGH", "BALANCE_ANOMALY"),
                finding("HIGH", "BALANCE_ANOMALY"),
                finding("HIGH", "BALANCE_ANOMALY")), "PASS");
        assertThat(r.automaticResult()).isEqualTo("FAIL");
        assertThat(r.highBugs()).isEqualTo(3);
    }

    @Test
    @DisplayName("B3: high_bugs=2 不 FAIL（阈值以下）→ PASS（需均衡 share，避免 4c）")
    void noFail_belowHighThreshold() {
        var r = svc.evaluate(1, "v001", allCompleted(), List.of(
                finding("HIGH", "BALANCE_ANOMALY"),
                finding("HIGH", "BALANCE_ANOMALY")), "PASS", BALANCED_SHARE);
        assertThat(r.automaticResult()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("B4: soft_lock_count&gt;0 → FAIL（卡死红线）")
    void fail_onSoftLock() {
        var r = svc.evaluate(1, "v001", allCompleted(),
                List.of(finding("HIGH", "SOFT_LOCK")), "PASS");
        assertThat(r.automaticResult()).isEqualTo("FAIL");
        assertThat(r.softLockCount()).isEqualTo(1);
    }

    // ==================== C. TIMEOUT 红线 ====================

    @Test
    @DisplayName("C1: 任一 child TIMEOUT → INCONCLUSIVE（绝不 PASS，红线）")
    void inconclusive_onAnyTimeout() {
        List<QaRoleAssignment> list = allCompleted();
        list.set(5, withStatus("EXPLOIT_HUNTER", "TIMEOUT"));
        var r = svc.evaluate(1, "v001", list, List.of(), "PASS");
        assertThat(r.automaticResult()).isEqualTo("INCONCLUSIVE");
        assertThat(r.inconclusiveCount()).isGreaterThan(0);
    }

    // ==================== D. FAILED/CANCELLED/missing ====================

    @Test
    @DisplayName("D1: child FAILED → INCONCLUSIVE（缺失 role result 不 PASS）")
    void inconclusive_onAnyFailed() {
        List<QaRoleAssignment> list = allCompleted();
        list.set(0, withStatus("PLAYER_NOVICE", "FAILED"));
        var r = svc.evaluate(1, "v001", list, List.of(), "PASS");
        assertThat(r.automaticResult()).isEqualTo("INCONCLUSIVE");
    }

    @Test
    @DisplayName("D2: 缺失角色（未 create_subtask）→ INCONCLUSIVE")
    void inconclusive_onMissingRole() {
        // 只注册 6 个角色，缺 UX_REVIEWER
        List<QaRoleAssignment> list = allCompleted().subList(0, 6);
        var r = svc.evaluate(1, "v001", list, List.of(), "PASS");
        assertThat(r.automaticResult()).isEqualTo("INCONCLUSIVE");
        assertThat(r.inconclusiveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("D3: key role EXPLOIT_HUNTER 未 COMPLETED（仍在 PENDING）→ INCONCLUSIVE")
    void inconclusive_whenKeyRoleNotComplete() {
        List<QaRoleAssignment> list = allCompleted();
        list.set(5, withStatus("EXPLOIT_HUNTER", "RUNNING"));
        var r = svc.evaluate(1, "v001", list, List.of(), "PASS");
        assertThat(r.automaticResult()).isEqualTo("INCONCLUSIVE");
    }

    @Test
    @DisplayName("D4: R6 closure 红线 — key role RUNNING + MEDIUM + lead CONDITIONAL_PASS → INCONCLUSIVE（绝不 CONDITIONAL_PASS）")
    void inconclusive_whenKeyRoleRunningEvenWithMediumAndLeadSignal() {
        List<QaRoleAssignment> list = allCompleted();
        list.set(5, withStatus("EXPLOIT_HUNTER", "RUNNING"));
        var r = svc.evaluate(1, "v001", list,
                List.of(finding("MEDIUM", "BALANCE_ANOMALY")), "建议 CONDITIONAL_PASS");
        assertThat(r.automaticResult()).isEqualTo("INCONCLUSIVE");
    }

    // ==================== E. CONDITIONAL_PASS ====================

    @Test
    @DisplayName("E1: 仅 MEDIUM + 均衡 share + lead CONDITIONAL_PASS → CONDITIONAL_PASS (consistent)")
    void conditionalPass_mediumWithLeadSignal() {
        var r = svc.evaluate(1, "v001", allCompleted(),
                List.of(finding("MEDIUM", "BALANCE_ANOMALY")), "建议 CONDITIONAL_PASS", BALANCED_SHARE);
        assertThat(r.automaticResult()).isEqualTo("CONDITIONAL_PASS");
        assertThat(r.consistent()).isTrue();
    }

    @Test
    @DisplayName("E2: 仅 MEDIUM + 均衡 share + lead 未表态 → INCONCLUSIVE（待人确认，不上传 PASS）")
    void inconclusive_mediumWithoutLeadSignal() {
        var r = svc.evaluate(1, "v001", allCompleted(),
                List.of(finding("MEDIUM", "BALANCE_ANOMALY")), null, BALANCED_SHARE);
        assertThat(r.automaticResult()).isEqualTo("INCONCLUSIVE");
    }

    // ==================== F. is_consistent ====================

    @Test
    @DisplayName("F1: auto PASS 但 lead 说 FAIL → consistent=false（审计标记）")
    void inconsistent_whenLeadDisagrees() {
        var r = svc.evaluate(1, "v001", allCompleted(), List.of(), "我的判定: FAIL", BALANCED_SHARE);
        assertThat(r.automaticResult()).isEqualTo("PASS");
        assertThat(r.consistent()).isFalse();
    }

    @Test
    @DisplayName("F2: lead 文本大小写/空白不敏感（'conditional pass' 可识别）")
    void leadDecisionNormalizationCaseInsensitive() {
        var r = svc.evaluate(1, "v001", allCompleted(),
                List.of(finding("MEDIUM", "BALANCE_ANOMALY")), " 建议 conditional pass ", BALANCED_SHARE);
        assertThat(r.automaticResult()).isEqualTo("CONDITIONAL_PASS");
        assertThat(r.consistent()).isTrue();
    }

    // ==================== G. 统计留痕 ====================

    @Test
    @DisplayName("G1: statsJson 记录 total/critical/high/medium/softLock/crashCount")
    void statsJsonCapturesCounts() {
        var r = svc.evaluate(1, "v001", allCompleted(), List.of(
                finding("BLOCKER", "CRASH"),
                finding("HIGH", "SOFT_LOCK"),
                finding("MEDIUM", "BALANCE_ANOMALY")), "PASS");
        assertThat(r.statsJson())
                .contains("\"totalFindings\":3")
                .contains("\"critical\":1")
                .contains("\"high\":1")
                .contains("\"medium\":1")
                .contains("\"softLock\":1")
                .contains("\"crashCount\":1");
    }

    // ==================== H. QA Phase 3 build share（6-arg evaluate 重载） ====================

    @Test
    @DisplayName("H1: 失衡 share=0.80 &gt; 0.75 → FAIL（HD-2 阈值触发，接线后分支可验证）")
    void fail_whenBuildShareImbalanced() {
        var r = svc.evaluate(1, "v001", allCompleted(), List.of(), "PASS", 0.80);
        assertThat(r.automaticResult()).isEqualTo("FAIL");
        assertThat(r.dominantBuildShare()).isEqualTo(0.80);
        assertThat(r.consistent()).isFalse();
    }

    @Test
    @DisplayName("H2: 均衡 share=0.333 ≤ 0.75 → 不触发失衡，PASS 保持")
    void pass_whenBuildShareBalanced() {
        var r = svc.evaluate(1, "v001", allCompleted(), List.of(), "PASS", 0.333);
        assertThat(r.automaticResult()).isEqualTo("PASS");
        assertThat(r.dominantBuildShare()).isEqualTo(0.333);
    }

    @Test
    @DisplayName("H3: 恰好 share=0.75 不触发失衡（isBuildShareImbalanced 严格大于，HD-2 保留）")
    void notImbalanced_atExactThreshold() {
        var r = svc.evaluate(1, "v001", allCompleted(), List.of(), "PASS", 0.75);
        assertThat(r.automaticResult()).isEqualTo("PASS");
        assertThat(r.dominantBuildShare()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("H4: 5-arg 委托（share=null）+ 全绿 → INCONCLUSIVE（HD-5：missing share never PASS，证据缺口计数 1）")
    void inconclusive_whenShareNull_via5ArgDelegate() {
        var r = svc.evaluate(1, "v001", allCompleted(), List.of(), "PASS");
        assertThat(r.automaticResult()).isEqualTo("INCONCLUSIVE");
        assertThat(r.dominantBuildShare()).isNull();
        assertThat(r.inconclusiveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("H5: share=null 时 aggregation 优先级保留 —— TIMEOUT 仍 INCONCLUSIVE（不因 null share 改判 FAIL/PASS）")
    void inconclusive_onTimeoutEvenWithNullShare() {
        List<QaRoleAssignment> list = allCompleted();
        list.set(5, withStatus("EXPLOIT_HUNTER", "TIMEOUT"));
        var r = svc.evaluate(1, "v001", list, List.of(), "PASS", null);
        assertThat(r.automaticResult()).isEqualTo("INCONCLUSIVE");
    }

    @Test
    @DisplayName("H6: share 记录在 GateResult + statsJson（6 位精度）")
    void shareRecordedInResultAndStatsJson() {
        var r = svc.evaluate(1, "v001", allCompleted(), List.of(), "PASS", 1.0 / 3.0);
        assertThat(r.dominantBuildShare()).isEqualTo(1.0 / 3.0);
        assertThat(r.statsJson())
                .contains("\"dominantBuildShare\":0.333333");
    }

    @Test
    @DisplayName("H7: 6-arg share=null → INCONCLUSIVE + statsJson 记 null（不编造数值，可审计）")
    void nullShareRecordedInStatsJson() {
        var r = svc.evaluate(1, "v001", allCompleted(), List.of(), "PASS", null);
        assertThat(r.automaticResult()).isEqualTo("INCONCLUSIVE");
        assertThat(r.statsJson()).contains("\"dominantBuildShare\":null");
    }

    @Test
    @DisplayName("H8: BLOCKER + share=null → FAIL（step 1 hard FAIL 优先于 4c，聚合优先级不变）")
    void fail_whenCriticalBugEvenWithNullShare() {
        var r = svc.evaluate(1, "v001", allCompleted(),
                List.of(finding("BLOCKER", "CRASH")), "PASS", null);
        assertThat(r.automaticResult()).isEqualTo("FAIL");
        assertThat(r.criticalBugs()).isEqualTo(1);
        assertThat(r.dominantBuildShare()).isNull();
    }

    @Test
    @DisplayName("H9: CONDITIONAL_PASS 路径不因 4c 前置而失效——MEDIUM + 均衡 share + lead 建议 → CONDITIONAL_PASS")
    void conditionalPass_stillWorksWithBalancedShare() {
        var r = svc.evaluate(1, "v001", allCompleted(),
                List.of(finding("MEDIUM", "BALANCE_ANOMALY")), "建议 CONDITIONAL_PASS", BALANCED_SHARE);
        assertThat(r.automaticResult()).isEqualTo("CONDITIONAL_PASS");
        assertThat(r.consistent()).isTrue();
    }
}