package com.aigamelab.tester;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.TestPlan;
import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.repository.TestPlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T3-1：TestPlan 业务逻辑（技术设计 §5）。
 *
 * 职责：模板生成 DRAFT → 用户编辑 scenarios → activate（DRAFT→ACTIVE，
 * ACTIVE 后才允许起 Run）→ COMPLETED。status 白名单与 Scenario 结构在此校验，
 * Repository 保持薄。
 *
 * Test Request = task 行，constraints_json 携带 {"configVersion":"v001"}；
 * MVP Plan 由服务端模板生成（LLM 全自动生成属二期）。
 */
@Service
public class TestPlanService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 场景 type 白名单（技术设计 §12 挑战矩阵 + §5 示例归纳） */
    static final List<String> SCENARIO_TYPES =
            List.of("SMOKE", "FUNCTIONAL", "STATE_TRANSITION", "BOUNDARY", "REGRESSION");

    /** 玩家人格白名单（GameLaunchSpec MVP 两种） */
    static final List<String> PLAYER_PROFILES =
            List.of("SCRIPTED", "RANDOM");

    private final TestPlanRepository planRepo;
    private final AgentEventPublisher publisher;

    public TestPlanService(TestPlanRepository planRepo, AgentEventPublisher publisher) {
        this.planRepo = planRepo;
        this.publisher = publisher;
    }

    /**
     * 由 task 约束生成模板 DRAFT Plan：每场景取 repeat=1、SCRIPTED 人格、
     * 默认全量 invariant 白名单。scenarios 参数由调用方（API/Agent）给定场景骨架，
     * 此处补默认字段并整体结构校验后落库。
     */
    public TestPlan createDraft(long taskId, String configVersion,
            List<Map<String, Object>> scenarioSkeletons) {
        if (configVersion == null || configVersion.isBlank()) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "configVersion 不能为空——被测对象必须是 ConfigVersion（契约 1）");
        }
        List<TestPlan.Scenario> scenarios = scenarioSkeletons == null || scenarioSkeletons.isEmpty()
                ? defaultScenarios(configVersion)
                : scenarioSkeletons.stream().map(TestPlanService::toScenario).toList();
        for (TestPlan.Scenario sc : scenarios) {
            validateScenario(sc);
        }
        TestPlan draft = planRepo.insert(new TestPlan(null, taskId, configVersion,
                TestPlan.STATUS_DRAFT, toJson(scenarios), null));
        // T4-3：DRAFT 创建后发 TEST_PLAN_CREATED 事件
        publisher.publish("TEST_PLAN_CREATED", 0L, "{\"testPlanId\":" + draft.id()
                + ",\"taskId\":" + taskId + ",\"configVersion\":\"" + configVersion + "\"}");
        return draft;
    }

    /** DRAFT → ACTIVE；此后才允许起 Run。非 DRAFT 态激活为状态机违规。 */
    public TestPlan activate(long planId) {
        TestPlan plan = require(planId);
        if (!TestPlan.STATUS_DRAFT.equals(plan.status())) {
            throw BusinessException.conflict(
                    "仅 DRAFT 可激活，当前 status=" + plan.status());
        }
        parseScenarios(plan.scenariosJson()); // 激活前强制结构合法
        planRepo.updateStatus(planId, TestPlan.STATUS_ACTIVE);
        // T4-3：激活后发 TEST_PLAN_ACTIVATED 事件
        publisher.publish("TEST_PLAN_ACTIVATED", 0L, "{\"testPlanId\":" + planId
                + ",\"taskId\":" + plan.taskId() + "}");
        return require(planId);
    }

    /** 仅 DRAFT 态允许编辑 scenarios（ACTIVE 后计划冻结）。 */
    public TestPlan updateScenarios(long planId, List<Map<String, Object>> skeletons) {
        TestPlan plan = require(planId);
        if (!TestPlan.STATUS_DRAFT.equals(plan.status())) {
            throw BusinessException.conflict(
                    "仅 DRAFT 可编辑 scenarios，当前 status=" + plan.status());
        }
        List<TestPlan.Scenario> scenarios = skeletons.stream()
                .map(TestPlanService::toScenario).toList();
        for (TestPlan.Scenario sc : scenarios) {
            validateScenario(sc);
        }
        planRepo.updateScenarios(planId, toJson(scenarios));
        return require(planId);
    }

    public TestPlan require(long planId) {
        return planRepo.findById(planId)
                .orElseThrow(() -> BusinessException.notFound("TestPlan 不存在: " + planId));
    }

    /** ACTIVE Plan 按 taskId 定位（Run 启动接线 T4-2 使用）；无则空。 */
    public List<TestPlan> findActiveByTask(long taskId) {
        return planRepo.findByTaskId(taskId).stream()
                .filter(p -> TestPlan.STATUS_ACTIVE.equals(p.status()))
                .toList();
    }

    /** scenariosJson → 结构化 Scenario 列表（非法 JSON 抛 INVALID_ARGUMENT）。 */
    public List<TestPlan.Scenario> parseScenarios(String scenariosJson) {
        try {
            return List.of(MAPPER.readValue(scenariosJson, TestPlan.Scenario[].class));
        } catch (Exception e) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "scenariosJson 非法: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 内部：骨架转换与白名单校验
    // ------------------------------------------------------------------

    private static TestPlan.Scenario toScenario(Map<String, Object> m) {
        List<String> invariants = listOfStrings(m.get("invariants"));
        return new TestPlan.Scenario(
                text(m, "id"),
                textOrDefault(m, "type", "SMOKE"),
                textOrDefault(m, "goal", ""),
                longOrDefault(m, "seed", 42L),
                intOrDefault(m, "repeat", 1),
                textOrDefault(m, "playerProfile", "SCRIPTED"),
                // 骨架未指定时补全量内置白名单（零漏报基线，技术设计 §5）
                invariants.isEmpty() ? List.copyOf(DEFAULT_INVARIANTS) : invariants,
                m.get("overrides") instanceof Map<?, ?> om
                        ? (Map<String, Object>) om : Map.of());
    }

    private void validateScenario(TestPlan.Scenario sc) {
        if (sc.id() == null || sc.id().isBlank()) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "scenario.id 必填");
        }
        if (!SCENARIO_TYPES.contains(sc.type())) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "scenario.type 非法: " + sc.type() + "，允许值 " + SCENARIO_TYPES);
        }
        if (!PLAYER_PROFILES.contains(sc.playerProfile())) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "playerProfile 非法: " + sc.playerProfile() + "，允许值 " + PLAYER_PROFILES);
        }
        if (sc.repeat() < 1) {
            throw new BusinessException(com.aigamelab.common.ErrorCodes.INVALID_ARGUMENT,
                    "repeat 必须 >=1");
        }
    }

    /** 内置谓词白名单（技术设计 §5；T3-4 InvariantEngine 的执行值域） */
    static final List<String> DEFAULT_INVARIANTS = List.of(
            "HP>=0", "HP<=MaxHP", "ENEMY_COUNT<=CAP", "TICK>=0", "PHASE_LEGAL");

    /** MVP 默认模板：一个 SMOKE 场景 + 全量内置 invariant 白名单 */
    private static List<TestPlan.Scenario> defaultScenarios(String configVersion) {
        return List.of(new TestPlan.Scenario("smoke-main-flow", "SMOKE",
                "启动→战斗→存活至结算", 42L, 1, "SCRIPTED",
                List.copyOf(DEFAULT_INVARIANTS), Map.of()));
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String textOrDefault(Map<String, Object> m, String key, String dflt) {
        Object v = m.get(key);
        return v == null || String.valueOf(v).isBlank() ? dflt : String.valueOf(v);
    }

    private static long longOrDefault(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : dflt;
    }

    private static int intOrDefault(Map<String, Object> m, String key, int dflt) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : dflt;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOfStrings(Object v) {
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        return (List<String>) list;
    }

    private static String toJson(List<TestPlan.Scenario> scenarios) {
        try {
            return MAPPER.writeValueAsString(scenarios);
        } catch (Exception e) {
            throw new IllegalStateException("Scenario 序列化失败", e); // record 序列化不应失败
        }
    }
}
