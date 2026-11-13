package com.aigamelab.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aigamelab.common.BusinessException;
import com.aigamelab.common.ErrorCodes;
import com.aigamelab.domain.Finding;
import com.aigamelab.domain.TestPlan;
import com.aigamelab.repository.AbstractRepositoryTest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T3-1 验收测试：Plan 模板生成/DRAFT→ACTIVE 流转/编辑规则；
 * Finding 白名单校验/Evidence sessionId 主锚契约/状态流转。
 */
@DisplayName("T3-1 TestPlanService + FindingService")
class TesterServicesTest extends AbstractRepositoryTest {

    @Autowired TestPlanService planService;
    @Autowired FindingService findingService;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    private long taskId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM research_claim");
        jdbc.update("DELETE FROM research_source");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
        taskId = jdbc.queryForObject(
                "INSERT INTO task(title, goal) VALUES('t','g') RETURNING id", Long.class);
    }

    // ==================== TestPlanService ====================

    @Test
    void createDraft_generatesTemplateAndDefaultsToDraft() {
        TestPlan plan = planService.createDraft(taskId, "v001",
                List.of(Map.of("id", "smoke-1", "goal", "存活")));
        assertThat(plan.id()).isNotNull();
        assertThat(plan.status()).isEqualTo(TestPlan.STATUS_DRAFT);
        assertThat(plan.configVersion()).isEqualTo("v001");

        List<TestPlan.Scenario> scs = planService.parseScenarios(plan.scenariosJson());
        assertThat(scs).hasSize(1);
        assertThat(scs.get(0).seed()).isEqualTo(42L);           // 默认 seed
        assertThat(scs.get(0).playerProfile()).isEqualTo("SCRIPTED");
        assertThat(scs.get(0).invariants()).contains("HP>=0");  // 默认 invariant
    }

    @Test
    void createDraft_emptySkeletons_fallsBackToDefaultSmokeScenario() {
        TestPlan plan = planService.createDraft(taskId, "v001",
                List.<Map<String, Object>>of());
        assertThat(planService.parseScenarios(plan.scenariosJson())).hasSize(1);
    }

    @Test
    void activate_transitionsDraftToActive_thenRejectsReactivation() {
        TestPlan plan = planService.createDraft(taskId, "v001", null);

        TestPlan active = planService.activate(plan.id());
        assertThat(active.status()).isEqualTo(TestPlan.STATUS_ACTIVE);

        // 二次激活 = 状态机违规（40901）
        assertThatThrownBy(() -> planService.activate(plan.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
    }

    @Test
    void updateScenarios_onlyInDraft_activeIsFrozen() {
        TestPlan plan = planService.createDraft(taskId, "v001",
                List.of(Map.of("id", "s1")));

        var edited = List.<Map<String, Object>>of(Map.of("id", "s2", "type", "BOUNDARY"));
        TestPlan updated = planService.updateScenarios(plan.id(), edited);
        assertThat(planService.parseScenarios(updated.scenariosJson()).get(0).id())
                .isEqualTo("s2");

        planService.activate(plan.id());
        assertThatThrownBy(() -> planService.updateScenarios(plan.id(), edited))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
    }

    @Test
    void invalidScenarioFields_areRejectedWith40001() {
        // type 不在白名单
        assertThatThrownBy(() -> planService.createDraft(taskId, "v001",
                List.of(Map.of("id", "x", "type", "CHAOS"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT));
        // playerProfile 非法
        assertThatThrownBy(() -> planService.createDraft(taskId, "v001",
                List.of(Map.of("id", "x", "playerProfile", "AGGRESSIVE"))))
                .isInstanceOf(BusinessException.class);
        // id 缺失
        assertThatThrownBy(() -> planService.createDraft(taskId, "v001",
                List.of(Map.of("goal", "无 id"))))
                .isInstanceOf(BusinessException.class);
        // configVersion 缺失（契约 1）
        assertThatThrownBy(() -> planService.createDraft(taskId, null, null))
                .isInstanceOf(BusinessException.class);
        // repeat < 1
        Map<String, Object> zeroRepeat = new java.util.HashMap<>();
        zeroRepeat.put("id", "x");
        zeroRepeat.put("repeat", 0);
        assertThatThrownBy(() -> planService.createDraft(taskId, "v001", List.of(zeroRepeat)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void require_unknownPlan_throws40401() {
        assertThatThrownBy(() -> planService.require(999999L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.NOT_FOUND));
    }

    @Test
    void findActiveByTask_returnsOnlyActive() {
        planService.createDraft(taskId, "v001", List.of(Map.<String, Object>of("id", "d")));
        TestPlan active = planService.createDraft(taskId, "v001",
                List.of(Map.<String, Object>of("id", "a")));
        planService.activate(active.id());

        List<TestPlan> actives = planService.findActiveByTask(taskId);
        assertThat(actives).extracting(TestPlan::id).containsExactly(active.id());
    }

    // ==================== FindingService ====================

    private Finding validFinding() throws Exception {
        String evidence = mapper.writeValueAsString(new Finding.Evidence(
                "gs-abc", null, List.of(1L, 2L),
                Map.of("hp", -3), 512L, 42L, Map.of()));
        return new Finding(null, null, null, taskId, "sc-1", "v001",
                "HIGH", "INVARIANT_VIOLATION", "INVARIANT", "HP 为负",
                "HP>=0", "hp=-3", "seed=42 → game_start → …", evidence,
                null, null, null);
    }

    @Test
    void report_validFinding_persistsWithDefaults() throws Exception {
        Finding saved = findingService.report(validFinding());
        assertThat(saved.id()).isNotNull();
        assertThat(saved.status()).isEqualTo("OPEN");
        assertThat(saved.source()).isEqualTo("INVARIANT");
    }

    @Test
    void report_invalidEnumerations_rejected() throws Exception {
        assertThatThrownBy(() -> findingService.report(new Finding(null, null, null,
                null, "s", "v001", "CRITICAL", "CRASH", null, "t",
                null, null, "r", "{}", null, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> findingService.report(new Finding(null, null, null,
                null, "s", "v001", "HIGH", "BUG", null, "t",
                null, null, "r", "{}", null, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> findingService.report(new Finding(null, null, null,
                null, "s", "v001", "HIGH", "CRASH", "AUTO", "t",
                null, null, "r", "{}", null, null, null)))
                .isInstanceOf(BusinessException.class);
        // reproSteps 缺失（硬要求）
        assertThatThrownBy(() -> findingService.report(new Finding(null, null, null,
                null, "s", "v001", "HIGH", "CRASH", null, "t",
                null, null, null, "{}", null, null, null)))
                .isInstanceOf(BusinessException.class);
        // configVersion 缺失（契约 1）
        assertThatThrownBy(() -> findingService.report(new Finding(null, null, null,
                null, "s", null, "HIGH", "CRASH", null, "t",
                null, null, "r", "{}", null, null, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void report_evidenceMustAnchorOnSession_notTickOnly() throws Exception {
        // Evidence 无 sessionId → 拒绝（tick 不是唯一定位键）
        String badEvidence = mapper.writeValueAsString(new Finding.Evidence(
                null, null, List.of(), Map.of(), 512L, 42L, Map.of()));
        assertThatThrownBy(() -> findingService.report(new Finding(null, null, null,
                null, "s", "v001", "HIGH", "INVARIANT_VIOLATION", "INVARIANT", "t",
                null, null, "r", badEvidence, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> e.getMessage().contains("sessionId"));

        // Evidence JSON 结构损坏 → 拒绝
        assertThatThrownBy(() -> findingService.report(new Finding(null, null, null,
                null, "s", "v001", "HIGH", "INVARIANT_VIOLATION", "INVARIANT", "t",
                null, null, "r", "{not-json", null, null, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void manualFinding_withoutEvidence_allowed() {
        Finding manual = new Finding(null, null, null, null, "manual-1", "v001",
                "LOW", "OTHER", "MANUAL", "手感异常", null, null, "人工观察",
                "{}", null, null, null);
        assertThat(findingService.report(manual).id()).isNotNull();
    }

    @Test
    void statusTransition_openToConfirmed_illegalValueRejected() throws Exception {
        Finding f = findingService.report(validFinding());
        assertThat(findingService.updateStatus(f.id(), "CONFIRMED").status())
                .isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> findingService.updateStatus(f.id(), "WONTFIX"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void findByFilter_versionSeverityPath_works() throws Exception {
        findingService.report(validFinding());
        findingService.report(new Finding(null, null, null, null, "m", "v001",
                "BLOCKER", "SOFT_LOCK", "LLM", "卡死", null, null, "r", "{}",
                null, null, null));

        assertThat(findingService.findByFilter("v001", "HIGH", null, null)).hasSize(1);
        assertThat(findingService.findByFilter(null, "BLOCKER", null, null)).hasSize(1);
        assertThat(findingService.findByFilter(null, null, null, null)).hasSize(2);
    }

    // ---- PF-003（TD-T6-001 清偿）：configVersion 必须真实存在 ----

    @Test
    void report_unknownConfigVersion_rejected() throws Exception {
        Finding unknown = new Finding(null, null, null, null, "sc-1", "v999",
                "HIGH", "CRASH", "LLM", "虚构版本", null, null, "r", "{}",
                null, null, null);
        assertThatThrownBy(() -> findingService.report(unknown))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> {
                            assertThat(e.getCode()).isEqualTo(ErrorCodes.NOT_FOUND);
                            assertThat(e.getMessage()).contains("v999");
                        });
        // 拒绝后不得落库
        assertThat(findingService.findByFilter("v999", null, null, null)).isEmpty();
    }

    @Test
    void report_validConfigVersion_stillPasses() throws Exception {
        Finding ok = new Finding(null, null, null, null, "sc-2", "v001",
                "MEDIUM", "BALANCE_ANOMALY", "LLM", "合法版本", null, null, "r",
                "{}", null, null, null);
        assertThat(findingService.report(ok).id()).isNotNull();
    }
}
