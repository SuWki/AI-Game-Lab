package com.aigamelab.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aigamelab.common.ApiResp;
import com.aigamelab.common.BusinessException;
import com.aigamelab.common.ErrorCodes;
import com.aigamelab.domain.Finding;
import com.aigamelab.domain.FindingRegression;
import com.aigamelab.domain.TestPlan;
import com.aigamelab.repository.FindingRepository;
import com.aigamelab.repository.TestPlanRepository;
import com.aigamelab.tester.FindingRegressionService;
import com.aigamelab.tester.FindingService;
import com.aigamelab.tester.TestPlanService;

/**
 * T4-5：AI Game Tester REST API（技术设计 §9 七个端点 + 包络/错误码契约）。
 * 不含 Run 启动端点（走现有 POST /api/tasks/{id}/runs，T4-2 已接入 tester prompt+tools）。
 */
@RestController
@RequestMapping("/api")
public class TesterController {

    private final TestPlanService planService;
    private final TestPlanRepository planRepo;
    private final FindingService findingService;
    private final FindingRepository findingRepo;
    private final FindingRegressionService regressionService;

    public TesterController(TestPlanService planService, TestPlanRepository planRepo,
            FindingService findingService, FindingRepository findingRepo,
            FindingRegressionService regressionService) {
        this.planService = planService;
        this.planRepo = planRepo;
        this.findingService = findingService;
        this.findingRepo = findingRepo;
        this.regressionService = regressionService;
    }

    /** 由 task 约束生成 DRAFT Plan（依据 task.constraints 的 configVersion）。 */
    @PostMapping("/tasks/{id}/test-plan")
    public ApiResp<TestPlan> createTestPlan(@PathVariable long id,
            @RequestBody(required = false) Map<String, Object> body) {
        String configVersion = body == null ? null
                : asText(body.get("configVersion"));
        if (configVersion == null || configVersion.isBlank()) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT,
                    "configVersion 必填（契约 1：被测对象是 ConfigVersion）");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenarios = body == null ? null
                : (List<Map<String, Object>>) body.get("scenarios");
        TestPlan plan = planService.createDraft(id, configVersion, scenarios);
        return ApiResp.ok(plan);
    }

    @GetMapping("/test-plans/{id}")
    public ApiResp<TestPlan> getTestPlan(@PathVariable long id) {
        return ApiResp.ok(planRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("TestPlan 不存在: " + id)));
    }

    /** 用户编辑 scenarios/invariants（仅 DRAFT 态）。 */
    @PutMapping("/test-plans/{id}")
    public ApiResp<TestPlan> updateTestPlan(@PathVariable long id,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) body.get("scenarios");
        if (scenarios == null) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT, "scenarios 必填");
        }
        return ApiResp.ok(planService.updateScenarios(id, scenarios));
    }

    /** DRAFT→ACTIVE（此后才允许起 Run）。 */
    @PostMapping("/test-plans/{id}/activate")
    public ApiResp<TestPlan> activateTestPlan(@PathVariable long id) {
        return ApiResp.ok(planService.activate(id));
    }

    @GetMapping("/findings")
    public ApiResp<List<Finding>> listFindings(
            @RequestParam(required = false) String configVersion,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Long runId) {
        return ApiResp.ok(findingService.findByFilter(configVersion, severity, runId, null));
    }

    @GetMapping("/findings/{id}")
    public ApiResp<Finding> getFinding(@PathVariable long id) {
        return ApiResp.ok(findingRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Finding 不存在: " + id)));
    }

    /** 人工确认/驳回 {status,comment}。 */
    @PostMapping("/findings/{id}/status")
    public ApiResp<Finding> updateFindingStatus(@PathVariable long id,
            @RequestBody Map<String, Object> body) {
        String status = asText(body.get("status"));
        if (status == null || status.isBlank()) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT, "status 必填");
        }
        return ApiResp.ok(findingService.updateStatus(id, status));
    }

    /** T7-6：版本间 Finding 回归对比。oldVersion/newVersion 均必填。 */
    @GetMapping("/findings/regression")
    public ApiResp<FindingRegression> getFindingRegression(
            @RequestParam String oldVersion,
            @RequestParam String newVersion) {
        if (oldVersion == null || oldVersion.isBlank()) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT, "oldVersion 必填");
        }
        if (newVersion == null || newVersion.isBlank()) {
            throw new BusinessException(ErrorCodes.INVALID_ARGUMENT, "newVersion 必填");
        }
        return ApiResp.ok(regressionService.diff(oldVersion, newVersion));
    }

    private static String asText(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
