package com.aigamelab.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aigamelab.common.BusinessException;
import com.aigamelab.common.ErrorCodes;
import com.aigamelab.domain.Finding;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.qa.support.QaTestFixture;
import com.aigamelab.repository.FindingRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tester.FindingReportTool;
import com.aigamelab.tester.FindingService;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * QA P1-2 验收测试：Finding Evidence 数据质量 + Finding 最小生命周期迁移。
 *
 * <p>验证矩阵（P1-2 §十六 完成判定）：
 * - A. Evidence expected/actual 非 null 非 blank（携带 Evidence 时）→ DENY，无半落库
 * - B. Lifecycle 合法迁移（OPEN→CONFIRMED/DISMISSED、CONFIRMED→FIXED/DISMISSED）→ ALLOW
 * - C. Lifecycle 非法迁移（OPEN→FIXED、CONFIRMED→OPEN、终态→*、自环）→ 40901 且 DB 状态不被篡改
 * - D. 事务一致性：校验失败路径不产生任何 finding 行/状态更新
 * - P1-1 回归：QA Tool（finding_report）→ FindingService → Finding+Evidence → DB 仍成功
 *
 * <p>使用 QaTestFixture per-class DB 隔离；仅清 finding/tool_call/step/agent_event/
 * test_plan/run/task（prompt_version / game_config_version 保持 context 种子）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DisplayName("QA P1-2 Evidence 数据质量 + Finding Lifecycle")
class QaP1_2Test {

    @Autowired FindingService findingService;
    @Autowired FindingRepository findingRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired RunRepository runRepo;
    @Autowired FindingReportTool findingReportTool;
    @Autowired JdbcTemplate jdbc;

    static final ObjectMapper MAPPER = new ObjectMapper();

    // ——— DB 隔离（QaTestFixture per-class 模式）———

    static final Path DB = QaTestFixture.tempDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        QaTestFixture.datasourceProps(registry, DB);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM test_plan");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    // ——— 测试数据工厂 ———

    /** 合法 evidenceJson（sessionId 必填主锚点）。 */
    private String evidence(String sessionId) {
        try {
            return MAPPER.writeValueAsString(new Finding.Evidence(
                    sessionId, null, List.of(1L), Map.of("hp", -3), 10L, 42L, Map.of()));
        } catch (Exception e) {
            throw new IllegalStateException("cannot write evidence json", e);
        }
    }

    /** 经 FindingService.report 上报一条 Finding（evidenceJson 由调用方给定）。 */
    private Finding report(String expected, String actual, String evidenceJson) {
        return findingService.report(new Finding(null, null, null, null,
                "sc-1", "v001", "HIGH", "INVARIANT_VIOLATION", "LLM", "title",
                expected, actual, "seed=42 → game_start → observe",
                evidenceJson, null, null, null));
    }

    private int findingCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM finding", Integer.class);
        return n == null ? 0 : n;
    }

    // ==================================================================
    // A. Evidence 数据质量：expected / actual 携带 Evidence 时非空校验
    // ==================================================================

    @Test
    @DisplayName("A1 valid expected + valid actual + Evidence → PASS")
    void validExpectedAndActual_accepted() {
        Finding saved = report("HP>=0", "hp=-3", evidence("gs-a1"));
        assertThat(saved.id()).isNotNull();
        assertThat(findingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("A2 expected=null + Evidence → DENY 40001，不落库")
    void expectedNull_denied() {
        assertThatThrownBy(() -> report(null, "hp=-3", evidence("gs-a2")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT);
                    assertThat(e.getMessage()).contains("expected");
                });
        assertThat(findingCount()).isZero();
    }

    @Test
    @DisplayName("A3 expected=\"\" + Evidence → DENY 40001，不落库")
    void expectedBlank_denied() {
        assertThatThrownBy(() -> report("", "hp=-3", evidence("gs-a3")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT));
        assertThat(findingCount()).isZero();
    }

    @Test
    @DisplayName("A4 expected=\"   \" + Evidence → DENY 40001（whitespace 视为 blank）")
    void expectedWhitespace_denied() {
        assertThatThrownBy(() -> report("   ", "hp=-3", evidence("gs-a4")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT));
        assertThat(findingCount()).isZero();
    }

    @Test
    @DisplayName("A5 actual=null + Evidence → DENY 40001，不落库")
    void actualNull_denied() {
        assertThatThrownBy(() -> report("HP>=0", null, evidence("gs-a5")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT);
                    assertThat(e.getMessage()).contains("actual");
                });
        assertThat(findingCount()).isZero();
    }

    @Test
    @DisplayName("A6 actual=\"\" + Evidence → DENY 40001，不落库")
    void actualBlank_denied() {
        assertThatThrownBy(() -> report("HP>=0", "", evidence("gs-a6")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT));
        assertThat(findingCount()).isZero();
    }

    @Test
    @DisplayName("A7 actual=\"   \" + Evidence → DENY 40001（whitespace 视为 blank）")
    void actualWhitespace_denied() {
        assertThatThrownBy(() -> report("HP>=0", "   ", evidence("gs-a7")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT));
        assertThat(findingCount()).isZero();
    }

    @Test
    @DisplayName("A8 无 Evidence 的 MANUAL 登记（expected/actual 为 null）仍允许——既有 MVP 路径回归")
    void manualWithoutEvidence_stillAllowed() {
        Finding manual = findingService.report(new Finding(null, null, null, null,
                "manual-1", "v001", "LOW", "OTHER", "MANUAL", "手感异常",
                null, null, "人工观察", "{}", null, null, null));
        assertThat(manual.id()).isNotNull();
        assertThat(findingCount()).isEqualTo(1); // 无 Evidence 路径不触发 expected/actual 校验
    }

    @Test
    @DisplayName("A9 evidenceJson 为 null 的登记不受 expected/actual 校验影响（无 Evidence 路径）")
    void nullEvidenceJson_allowed() {
        Finding saved = report("HP>=0", "hp=-3", null);
        assertThat(saved.id()).isNotNull();
    }

    // ==================================================================
    // B. Lifecycle 合法迁移（QA P1 Audit 权威矩阵）
    // ==================================================================

    @Test
    @DisplayName("B1 OPEN → CONFIRMED ALLOW，DB 状态同步更新")
    void openToConfirmed_allowed() {
        Finding f = report("e", "a", "{}");
        Finding updated = findingService.updateStatus(f.id(), "CONFIRMED");
        assertThat(updated.status()).isEqualTo("CONFIRMED");
        assertThat(findingRepo.findById(f.id()).orElseThrow().status()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("B2 OPEN → DISMISSED ALLOW")
    void openToDismissed_allowed() {
        Finding f = report("e", "a", "{}");
        assertThat(findingService.updateStatus(f.id(), "DISMISSED").status())
                .isEqualTo("DISMISSED");
    }

    @Test
    @DisplayName("B3 CONFIRMED → FIXED ALLOW")
    void confirmedToFixed_allowed() {
        Finding f = report("e", "a", "{}");
        findingService.updateStatus(f.id(), "CONFIRMED");
        assertThat(findingService.updateStatus(f.id(), "FIXED").status()).isEqualTo("FIXED");
    }

    @Test
    @DisplayName("B4 CONFIRMED → DISMISSED ALLOW（驳回已确认项）")
    void confirmedToDismissed_allowed() {
        Finding f = report("e", "a", "{}");
        findingService.updateStatus(f.id(), "CONFIRMED");
        assertThat(findingService.updateStatus(f.id(), "DISMISSED").status())
                .isEqualTo("DISMISSED");
    }

    @Test
    @DisplayName("B5 状态值不在白名单 → 40001（既有行为回归）")
    void illegalStatusValue_rejected40001() {
        Finding f = report("e", "a", "{}");
        assertThatThrownBy(() -> findingService.updateStatus(f.id(), "WONTFIX"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT));
        assertThat(findingRepo.findById(f.id()).orElseThrow().status()).isEqualTo("OPEN");
    }

    // ==================================================================
    // C. Lifecycle 非法迁移：40901 + DB 状态不被篡改
    // ==================================================================

    @Test
    @DisplayName("C1 OPEN → FIXED DENY 40901（未确认不得直接修复），DB 状态不变")
    void openToFixed_denied_andDbUnchanged() {
        Finding f = report("e", "a", "{}");
        assertThatThrownBy(() -> findingService.updateStatus(f.id(), "FIXED"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT);
                    assertThat(e.getMessage()).contains("OPEN", "FIXED");
                });
        assertThat(findingRepo.findById(f.id()).orElseThrow().status()).isEqualTo("OPEN");
        assertThat(findingCount()).isEqualTo(1); // 行数与字段均未被部分更新
    }

    @Test
    @DisplayName("C2 CONFIRMED → OPEN DENY 40901（已确认不可回退），DB 状态不变")
    void confirmedToOpen_denied_andDbUnchanged() {
        Finding f = report("e", "a", "{}");
        findingService.updateStatus(f.id(), "CONFIRMED");
        assertThatThrownBy(() -> findingService.updateStatus(f.id(), "OPEN"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
        assertThat(findingRepo.findById(f.id()).orElseThrow().status()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("C3 DISMISSED 为终态：→OPEN/→FIXED/→CONFIRMED 全 DENY 40901")
    void dismissed_terminal_allDenied() {
        Finding f = report("e", "a", "{}");
        findingService.updateStatus(f.id(), "DISMISSED");
        for (String target : List.of("OPEN", "FIXED", "CONFIRMED", "DISMISSED")) {
            assertThatThrownBy(() -> findingService.updateStatus(f.id(), target))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
        }
        assertThat(findingRepo.findById(f.id()).orElseThrow().status()).isEqualTo("DISMISSED");
        assertThat(findingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("C4 FIXED 为终态：→OPEN/→CONFIRMED/→DISMISSED/→FIXED 全 DENY 40901")
    void fixed_terminal_allDenied() {
        Finding f = report("e", "a", "{}");
        findingService.updateStatus(f.id(), "CONFIRMED");
        findingService.updateStatus(f.id(), "FIXED");
        for (String target : List.of("OPEN", "CONFIRMED", "DISMISSED", "FIXED")) {
            assertThatThrownBy(() -> findingService.updateStatus(f.id(), target))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
        }
        assertThat(findingRepo.findById(f.id()).orElseThrow().status()).isEqualTo("FIXED");
        assertThat(findingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("C5 自环迁移（OPEN→OPEN / CONFIRMED→CONFIRMED）DENY 40901")
    void selfTransitions_denied() {
        Finding f = report("e", "a", "{}");
        assertThatThrownBy(() -> findingService.updateStatus(f.id(), "OPEN"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
        findingService.updateStatus(f.id(), "CONFIRMED");
        assertThatThrownBy(() -> findingService.updateStatus(f.id(), "CONFIRMED"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
        assertThat(findingRepo.findById(f.id()).orElseThrow().status()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("C6 更新不存在的 Finding → 40401，不落任何状态")
    void updateStatus_unknownFinding_notFound() {
        assertThatThrownBy(() -> findingService.updateStatus(999999L, "CONFIRMED"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.NOT_FOUND));
    }

    // ==================================================================
    // D. 事务一致性 + P1-1 QA Tool 闭环回归
    // ==================================================================

    private Task newTask() {
        return taskRepo.insert(new Task(null, "qa-p12-task", "qa test goal",
                "{\"agent_type\":\"QA\"}", 10, false, "ACTIVE", null));
    }

    private Run newRunFor(long taskId) {
        return runRepo.insert(new Run(null, taskId, "RUNNING", null,
                "deepseek-chat", 0, 10, 100000, 0, null, null, null, null, null));
    }

    @Test
    @DisplayName("D1 QA Tool finding_report 携带 blank actual → ToolException，Finding 不落库")
    void toolRejectsInvalidEvidence_noRow() throws Exception {
        Task task = newTask();
        Run run = newRunFor(task.id());

        JsonNode args = MAPPER.readTree("""
                {"sessionId":"qt-1","scenarioId":"sc-1","configVersion":"v001",
                 "severity":"HIGH","category":"INVARIANT_VIOLATION",
                 "title":"title","expected":"HP>=0","actual":"   ",
                 "reproSteps":"seed=42 → game_start → observe"}""");

        assertThatThrownBy(() -> findingReportTool.execute(args, ToolContext.of(run.id(), 1L)))
                .isInstanceOf(ToolException.class);
        assertThat(findingCount()).isZero(); // 校验失败路径无任何半成功状态
    }

    @Test
    @DisplayName("D2 QA Tool finding_report 携带完整 expected/actual → Finding+Evidence 落库（P1-1 回归）")
    void toolWithValidEvidence_createsFinding() throws Exception {
        Task task = newTask();
        Run run = newRunFor(task.id());

        JsonNode args = MAPPER.readTree("""
                {"sessionId":"qt-ok","scenarioId":"sc-1","configVersion":"v001",
                 "severity":"HIGH","category":"INVARIANT_VIOLATION",
                 "title":"title","expected":"HP>=0","actual":"hp=-3",
                 "reproSteps":"seed=42 → game_start → observe",
                 "seed":42,"tick":10,"toolCallIds":[1,2]}""");

        var result = findingReportTool.execute(args, ToolContext.of(run.id(), 1L));
        assertThat(result.status()).isEqualTo("COMPLETED");

        List<Finding> findings = findingRepo.findByFilter("v001", null, null, null);
        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.expected()).isEqualTo("HP>=0");
        assertThat(f.actual()).isEqualTo("hp=-3");
        assertThat(f.status()).isEqualTo("OPEN");
        Finding.Evidence ev;
        try {
            ev = MAPPER.readValue(f.evidenceJson(), Finding.Evidence.class);
        } catch (Exception e) {
            throw new IllegalStateException("Evidence 反序列化失败", e);
        }
        assertThat(ev.sessionId()).isEqualTo("qt-ok");
        assertThat(ev.seed()).isEqualTo(42L);
    }
}