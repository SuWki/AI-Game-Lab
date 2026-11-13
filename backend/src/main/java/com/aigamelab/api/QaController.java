package com.aigamelab.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aigamelab.common.ApiResp;
import com.aigamelab.common.BusinessException;
import com.aigamelab.common.ErrorCodes;
import com.aigamelab.domain.Finding;
import com.aigamelab.domain.FindingFilter;
import com.aigamelab.domain.QaReleaseGate;
import com.aigamelab.domain.QaRoleAssignment;
import com.aigamelab.repository.QaReleaseGateRepository;
import com.aigamelab.repository.QaRoleAssignmentRepository;
import com.aigamelab.tester.FindingService;

/**
 * QA REST API（R3-B1 QA P1-3 多维过滤 + R5 Phase 2 Gate/编排视图）。
 *
 * <p>职责边界：
 * <ul>
 *   <li><b>GET /api/qa/findings</b> — P1-3 多维过滤（冻结契约，不变）；</li>
 *   <li><b>GET /api/qa/gates</b> / <b>GET /api/qa/gates/{id}</b> — R5 Phase 2
 *       Gate 展示端点（QA-S4 "展示端点"）；</li>
 *   <li><b>POST /api/qa/gates/{id}/override</b> — R5 Phase 2 人工 final 裁决
 *       （freeze §13.1 manual_override；仅 Human，审计 override_by/reason）；</li>
 *   <li><b>GET /api/qa/orchestrations/{leadTaskId}/assignments</b> — 编排可观测性
 *       （角色分配 6 态视图，供人工审阅子 Run 结果）。</li>
 * </ul>
 *
 * <p>所有写端点（override）均视为不可逆审计操作：落库 manual_override=1 +
 * override_reason + override_by，Gate 状态终态化。</p>
 */
@RestController
@RequestMapping("/api/qa")
public class QaController {

    private final FindingService findingService;
    private final QaReleaseGateRepository gateRepo;
    private final QaRoleAssignmentRepository assignmentRepo;

    public QaController(FindingService findingService,
            QaReleaseGateRepository gateRepo, QaRoleAssignmentRepository assignmentRepo) {
        this.findingService = findingService;
        this.gateRepo = gateRepo;
        this.assignmentRepo = assignmentRepo;
    }

    /**
     * 多维过滤查询 Finding（QA 视图）。与 Tester /api/findings 并存，
     * 不改变 Tester 契约；额外暴露 category/source/status/runIds/testPlanId/taskId。
     */
    @GetMapping("/findings")
    public ApiResp<List<Finding>> listFindings(
            @RequestParam(required = false) String configVersion,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) List<Long> runIds,
            @RequestParam(required = false) Long testPlanId,
            @RequestParam(required = false) Long taskId) {
        FindingFilter filter = new FindingFilter(
                blankToNull(configVersion), blankToNull(severity), blankToNull(category),
                blankToNull(source), blankToNull(status), runIds, testPlanId, taskId);
        return ApiResp.ok(findingService.findByQaFilter(filter));
    }

    /** R5 Phase 2：Gate 列表（QA-S4 展示端点）。可选按 leadTaskId/configVersion/status 过滤。 */
    @GetMapping("/gates")
    public ApiResp<List<QaReleaseGate>> listGates(
            @RequestParam(required = false) Long leadTaskId,
            @RequestParam(required = false) String configVersion,
            @RequestParam(required = false) String status) {
        return ApiResp.ok(gateRepo.listBy(leadTaskId, blankToNull(configVersion), blankToNull(status)));
    }

    /** R5 Phase 2：单个 Gate 详情。 */
    @GetMapping("/gates/{id}")
    public ApiResp<QaReleaseGate> getGate(@PathVariable long id) {
        return ApiResp.ok(gateRepo.findById(id).orElseThrow(
                () -> BusinessException.notFound("Gate 不存在: " + id)));
    }

    /**
     * R5 Phase 2：人工 final 裁决（freeze §13.1 manual_override）。
     * 请求体：{finalResult, reason?, by}
     * - finalResult 仅允许 PENDING/PASS/CONDITIONAL_PASS/FAIL（QaReleaseGate.STATUSES）；
     * - override 后 Gate 终态化，不可再改（override_at 留痕）。
     */
    @PostMapping("/gates/{id}/override")
    public ApiResp<QaReleaseGate> overrideGate(@PathVariable long id,
            @RequestBody Map<String, Object> body) {
        String finalResult = asText(body.get("finalResult"));
        String reason = asText(body.get("reason"));
        String by = asText(body.get("by"));
        if (finalResult == null || !QaReleaseGate.STATUSES.contains(finalResult)) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "finalResult 仅允许: " + QaReleaseGate.STATUSES);
        }
        if (by == null || by.isBlank()) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT, "by（操作人）必填");
        }
        gateRepo.overrideDecision(id, finalResult, reason, by);
        return ApiResp.ok(gateRepo.findById(id).orElseThrow(
                () -> BusinessException.notFound("Gate 不存在: " + id)));
    }

    /** R5 Phase 2：一棵 QA 树的角色分配视图（按 order_no 升序）。 */
    @GetMapping("/orchestrations/{leadTaskId}/assignments")
    public ApiResp<List<QaRoleAssignment>> listAssignments(@PathVariable long leadTaskId) {
        return ApiResp.ok(assignmentRepo.findByLeadTask(leadTaskId));
    }

    /** blank ⇔ 缺席（客户端传空串等价不传）；非空串原样保留交由 Service 校验。 */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String asText(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
