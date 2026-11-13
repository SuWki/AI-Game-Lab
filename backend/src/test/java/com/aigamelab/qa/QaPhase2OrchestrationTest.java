package com.aigamelab.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.aigamelab.domain.QaReleaseGate;
import com.aigamelab.domain.QaRoleAssignment;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Task;
import com.aigamelab.qa.support.QaTestFixture;
import com.aigamelab.repository.QaReleaseGateRepository;
import com.aigamelab.repository.QaRoleAssignmentRepository;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.StepRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.runtime.AgentLoop;
import com.aigamelab.runtime.LoopDetector;
import com.aigamelab.runtime.AgentType;
import com.aigamelab.runtime.AgentTypeResolver;
import com.aigamelab.tester.FindingService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * R5 Phase 2 全链路集成测试（orchestrator + tools + gate + REST + poll_seq）。
 *
 * <p>验证矩阵：
 * <ul>
 *   <li>A. AgentType 白名单注入 5 个 qa_ 工具（QA_LEAD）</li>
 *   <li>B. Orchestrator createSubtask → assignment PENDING + sub-task</li>
 *   <li>C. Orchestrator createSubtask duplicate → 40901</li>
 *   <li>D. startSubrun → run row + assignment RUNNING（agentLoop mocked）</li>
 *   <li>E. checkSubrunStatus write-back：terminal run → assignment updated</li>
 *   <li>F. completeOrchestration → gate PASS</li>
 *   <li>G. completeOrchestration 有 BLOCKER finding → gate FAIL</li>
 *   <li>H. Poll_seq：LoopDetector 3 identical args 命中 → 异常；3 不同 pollSeq 不命中</li>
 *   <li>I. REST：GET /api/qa/gates, GET /api/qa/gates/{id}, POST override, GET assignments</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("R5 Phase 2 全链路集成测试")
class QaPhase2OrchestrationTest {

    @Autowired AgentTypeResolver resolver;
    @Autowired QaOrchestratorService orchestrator;
    @Autowired QaReleaseGateService gateService;
    @Autowired TaskRepository taskRepo;
    @Autowired RunRepository runRepo;
    @Autowired QaRoleAssignmentRepository assignmentRepo;
    @Autowired QaReleaseGateRepository gateRepo;
    @Autowired FindingService findingService;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoopDetector loopDetector;
    @Autowired StepRepository stepRepo;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    /** QA Phase 3（HD-1 A2）：实验数据注入（integration 级 O-series 测试）。 */
    @Autowired com.aigamelab.repository.ExperimentRepository experimentRepo;

    /**
     * no-op AgentLoop：QA 集成测试不触发真实 LLM 循环。
     * 用 @Primary 子类替身（代码库约定：避免 @MockBean 的 ByteBuddy/Java24 兼容问题）。
     */
    @TestConfiguration
    static class NoopAgentLoopConfig {

        @Bean
        @Primary
        AgentLoop noopAgentLoop(RunRepository runRepo, StepRepository stepRepo,
                org.springframework.beans.factory.ObjectProvider<com.aigamelab.llm.LlmGateway> llmGateway,
                com.aigamelab.runtime.ToolExecutor toolExecutor,
                com.aigamelab.event.AgentEventPublisher publisher,
                com.aigamelab.runtime.RunStateMachine stateMachine,
                TaskRepository taskRepo, com.aigamelab.runtime.BudgetGuard budgetGuard,
                LoopDetector loopDetector, JdbcTemplate jdbc,
                org.springframework.beans.factory.ObjectProvider<com.aigamelab.tool.ToolRegistry> registryProvider,
                AgentTypeResolver resolver) {
            return new AgentLoop(runRepo, stepRepo, llmGateway, toolExecutor, publisher,
                    stateMachine, taskRepo, budgetGuard, loopDetector, jdbc,
                    registryProvider, resolver) {
                @Override
                public void run(long runId) {
                    // no-op：集成测试不执行真实 Agent 循环
                }
            };
        }
    }

    static final Path DB = QaTestFixture.tempDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        QaTestFixture.datasourceProps(registry, DB);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM qa_release_gate");
        jdbc.update("DELETE FROM qa_role_assignment");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM experiment"); // FK: experiment.run_id → run.id（必须在 run 前）
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    // ——— 测试工厂 ———

    private Task leadTask() {
        return taskRepo.insert(new Task(null, "QA-Lead", "test goal",
                "{\"agent_type\":\"QA_LEAD\"}", 50, false, "ACTIVE", null));
    }

    private Run leadRun(long taskId) {
        return runRepo.insert(new Run(null, taskId, "RUNNING", null,
                "test-model", 0, 50, 100000, 0, null, null, null, null, null));
    }

    // ==================== A. 白名单注入 ====================

    @Test
    @DisplayName("A: QA_LEAD 白名单包含全部 5 个 qa_ 编排工具")
    void qaLeadWhitelistIncludesAllQaTools() {
        Set<String> qaLeadTools = resolver.toolWhitelist(AgentType.QA_LEAD);
        assertThat(qaLeadTools).containsExactlyInAnyOrder(
                "qa_create_subtask", "qa_start_subrun", "qa_check_subrun_status",
                "qa_collect_findings", "finding_report", "qa_complete_orchestration");
    }

    // ==================== B. createSubtask ====================

    @Test
    @DisplayName("B: createSubtask → PENDING assignment + sub-task with correct constraintsJson")
    void createSubtaskCreatesAssignment() {
        Task lead = leadTask();
        Run run = leadRun(lead.id());
        QaRoleAssignment a = orchestrator.createSubtask(lead, run, "PLAYER_NOVICE", "v001");

        assertThat(a.status()).isEqualTo("PENDING");
        assertThat(a.role()).isEqualTo("PLAYER_NOVICE");
        assertThat(a.leadTaskId()).isEqualTo(lead.id());
        assertThat(a.subTaskId()).isNotNull();
        assertThat(a.orderNo()).isEqualTo(1);

        Task subTask = taskRepo.findById(a.subTaskId()).orElseThrow();
        assertThat(subTask.constraintsJson()).contains("QA_TESTER");
        assertThat(subTask.constraintsJson()).contains("qa_player_novice_p001");
        assertThat(subTask.constraintsJson()).contains("NOVICE");
        assertThat(subTask.constraintsJson()).contains("v001");
        assertThat(subTask.constraintsJson()).contains("\"lead_task_id\":" + lead.id());
    }

    // ==================== C. Duplicate createSubtask ====================

    @Test
    @DisplayName("C: 重复角色 createSubtask → 40901 CONFLICT")
    void duplicateRoleThrowsConflict() {
        Task lead = leadTask();
        Run run = leadRun(lead.id());
        orchestrator.createSubtask(lead, run, "PLAYER_AGGRESSIVE", "v001");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> orchestrator.createSubtask(lead, run, "PLAYER_AGGRESSIVE", "v001"))
                .isInstanceOf(com.aigamelab.common.BusinessException.class)
                .extracting(e -> ((com.aigamelab.common.BusinessException) e).getCode())
                .isEqualTo(40901);
    }

    // ==================== D. startSubrun ====================

    @Test
    @DisplayName("D: startSubrun → run row PENDING + assignment RUNNING + deadline anchored")
    void startSubrunCreatesRunAndBindsAssignment() {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        QaRoleAssignment a = orchestrator.createSubtask(lead, leadRun, "COMBAT_ANALYST", "v001");
        Run subRun = orchestrator.startSubrun(lead, a.subTaskId());

        assertThat(subRun.id()).isNotNull();
        assertThat(subRun.taskId()).isEqualTo(a.subTaskId());
        // runRepo.insert 设 PENDING
        assertThat(subRun.status()).isEqualTo("PENDING");

        // 回查 assignment —— bindSubRun 设 RUNNING
        QaRoleAssignment updated = assignmentRepo.findById(a.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo("RUNNING");
        assertThat(updated.subRunId()).isEqualTo(subRun.id());
    }

    // ==================== E. checkSubrunStatus write-back ====================

    @Test
    @DisplayName("E: checkSubrunStatus 观察到终态 → assignment 回写 COMPLETED")
    void checkSubrunStatusWritesBackTerminalState() {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        QaRoleAssignment a = orchestrator.createSubtask(lead, leadRun, "NUMERICAL_ANALYST", "v001");
        Run subRun = orchestrator.startSubrun(lead, a.subTaskId());

        // 模拟子 Run 自身完成（手工设终态，等价 agentLoop.run 结束）
        runRepo.finish(subRun.id(), com.aigamelab.domain.RunStatus.COMPLETED, "num-ok", null);

        Run result = orchestrator.checkSubrunStatus(lead, subRun.id());
        assertThat(result.status()).isEqualTo("COMPLETED");

        QaRoleAssignment updated = assignmentRepo.findById(a.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo("COMPLETED");
        assertThat(updated.resultSummary()).isEqualTo("num-ok");
    }

    @Test
    @DisplayName("E2: checkSubrunStatus FAILED 子 Run → assignment 回写 FAILED")
    void checkSubrunStatusFailedSubRunWritesBack() {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        QaRoleAssignment a = orchestrator.createSubtask(lead, leadRun, "EXPLOIT_HUNTER", "v001");
        Run subRun = orchestrator.startSubrun(lead, a.subTaskId());

        runRepo.finish(subRun.id(), com.aigamelab.domain.RunStatus.FAILED, null, "critical error");

        Run result = orchestrator.checkSubrunStatus(lead, subRun.id());
        assertThat(result.status()).isEqualTo("FAILED");

        QaRoleAssignment updated = assignmentRepo.findById(a.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo("FAILED");
        assertThat(updated.errorMessage()).isEqualTo("critical error");
    }

    // ==================== F. completeOrchestration → PASS ====================

    @Test
    @DisplayName("F: completeOrchestration 全角色 COMPLETED + 无 finding + 均衡实验 → gate PASS")
    void completeOrchestrationPass() {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        // QA Phase 3（HD-5）：build share 证据缺失 → INCONCLUSIVE；故 PASS 前置需均衡实验
        seedDoneExperiment("v001", buildDistJson(100, 100, 100));

        // 为全部 7 角色建 subtask + 模拟完成
        QaRoleAssignment exploit = null, ux = null;
        for (String role : new String[]{
                "PLAYER_NOVICE", "PLAYER_AGGRESSIVE", "PLAYER_BUILD_SPECIALIST",
                "COMBAT_ANALYST", "NUMERICAL_ANALYST", "EXPLOIT_HUNTER", "UX_REVIEWER"}) {
            QaRoleAssignment a = orchestrator.createSubtask(lead, leadRun, role, "v001");
            Run subRun = orchestrator.startSubrun(lead, a.subTaskId());
            runRepo.updateStatusAndStep(subRun.id(), com.aigamelab.domain.RunStatus.COMPLETED, subRun.currentStep());
            runRepo.finish(subRun.id(), com.aigamelab.domain.RunStatus.COMPLETED, "ok", null);
            if ("EXPLOIT_HUNTER".equals(role)) exploit = a;
            if ("UX_REVIEWER".equals(role)) ux = a;
        }
        // 回写 assignment 状态
        for (QaRoleAssignment a : assignmentRepo.findByLeadTask(lead.id())) {
            if ("RUNNING".equals(a.status())) {
                assignmentRepo.updateStatus(a.id(), "COMPLETED", "ok", null);
            }
        }

        QaReleaseGate gate = orchestrator.completeOrchestration(
                lead, leadRun, findingService, "v001", "Lead says: PASS", "{}");

        assertThat(gate.id()).isNotNull();
        assertThat(gate.automaticResult()).isEqualTo("PASS");
        assertThat(gate.status()).isEqualTo("PASS");
        assertThat(gate.leadDecision()).isEqualTo("Lead says: PASS");
    }

    // ==================== G. completeOrchestration → FAIL (BLOCKER) ====================

    @Test
    @DisplayName("G: completeOrchestration 有 BLOCKER finding → gate FAIL")
    void completeOrchestrationFailWithCriticalBug() {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());

        for (String role : new String[]{
                "PLAYER_NOVICE", "PLAYER_AGGRESSIVE", "PLAYER_BUILD_SPECIALIST",
                "COMBAT_ANALYST", "NUMERICAL_ANALYST", "EXPLOIT_HUNTER", "UX_REVIEWER"}) {
            QaRoleAssignment a = orchestrator.createSubtask(lead, leadRun, role, "v001");
            Run subRun = orchestrator.startSubrun(lead, a.subTaskId());
            runRepo.updateStatusAndStep(subRun.id(), com.aigamelab.domain.RunStatus.COMPLETED, subRun.currentStep());
            runRepo.finish(subRun.id(), com.aigamelab.domain.RunStatus.COMPLETED, "ok", null);
        }
        for (QaRoleAssignment a : assignmentRepo.findByLeadTask(lead.id())) {
            if ("RUNNING".equals(a.status())) {
                assignmentRepo.updateStatus(a.id(), "COMPLETED", "ok", null);
            }
        }

        // 手工落 BLOCKER finding 到子 run（供 gate 统计；repro_steps 为 NOT NULL 列）
        QaRoleAssignment firstSub = assignmentRepo.findByLeadTask(lead.id()).get(0);
        jdbc.update("INSERT INTO finding(run_id, task_id, scenario_id, config_version,"
                + " severity, category, source, title, repro_steps, status)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                firstSub.subRunId(), firstSub.subTaskId(), "sc-1", "v001",
                "BLOCKER", "CRASH", "INVARIANT", "crash issue", "- seed: sc-1", "OPEN");

        QaReleaseGate gate = orchestrator.completeOrchestration(
                lead, leadRun, findingService, "v001", "Lead says: FAIL", "{}");

        assertThat(gate.automaticResult()).isEqualTo("FAIL");
        assertThat(gate.criticalBugs()).isEqualTo(1);
    }

    // ==================== H. Poll_seq LoopDetector ====================

    @Test
    @DisplayName("H: 3 次相同 (toolName,args) → LoopDetector 命中；3 次不同 pollSeq → 不命中")
    void pollSeqDifferentArgsAvoidsLoopDetector() {
        Task lead = leadTask();
        Run run = leadRun(lead.id());

        // tool_call.step_id NOT NULL：先插入一条 step 记录（phase 为 NOT NULL 列）
        jdbc.update("INSERT INTO step(run_id, seq, phase, prompt_tokens, completion_tokens, created_at)"
                + " VALUES(?, 1, 'LLM_CALL', 0, 0, datetime('now','localtime'))", run.id());
        long stepId = jdbc.queryForObject("SELECT id FROM step WHERE run_id=? AND seq=1",
                Long.class, run.id());

        // 手工落 3 条完全相同的 tool_call（无 poll_seq）→ 应命中
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO tool_call(step_id, run_id, tool_name, arguments_json,"
                    + " status, approval_status)"
                    + " VALUES(?, ?, 'qa_check_subrun_status', '{\"subRunId\":1,\"pollSeq\":1}',"
                    + " 'COMPLETED', 'NOT_REQUIRED')", stepId, run.id());
        }
        boolean detected = loopDetector.detect(run.id(),
                "qa_check_subrun_status", "{\"subRunId\":1,\"pollSeq\":1}");
        assertThat(detected).as("相同 args 连续 3 次应命中").isTrue();

        // 清理，再插入 3 次不同 poll_seq → argsJson 不同 → 不命中
        jdbc.update("DELETE FROM tool_call WHERE run_id=?", run.id());
        for (int seq = 1; seq <= 3; seq++) {
            String argsJson = "{\"subRunId\":1,\"pollSeq\":" + seq + "}";
            jdbc.update("INSERT INTO tool_call(step_id, run_id, tool_name, arguments_json,"
                    + " status, approval_status)"
                    + " VALUES(?, ?, 'qa_check_subrun_status', ?,"
                    + " 'COMPLETED', 'NOT_REQUIRED')", stepId, run.id(), argsJson);
        }
        boolean notDetected = loopDetector.detect(run.id(),
                "qa_check_subrun_status",
                "{\"subRunId\":1,\"pollSeq\":3}");
        assertThat(notDetected).as("不同 pollSeq 不应命中").isFalse();
    }

    // ==================== I. REST Gate / Assignment 端点 ====================

    @Test
    @DisplayName("I1: GET /api/qa/gates → 200 + gate 列表")
    void listGatesReturns200() throws Exception {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        gateRepo.insert(new QaReleaseGate(null, lead.id(), leadRun.id(),
                "v001", "PENDING", null, "R1", null, "{}", null,
                1, 0, 0, null, null, null,
                0, 0, 0, 0, null, null, "{}", null, null, null));

        mockMvc.perform(get("/api/qa/gates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("I2: GET /api/qa/gates?leadTaskId=X → 仅返回该 lead 的 gate")
    void listGatesFilterByLeadTask() throws Exception {
        Task lead1 = leadTask();
        Task lead2 = leadTask();
        Run r1 = leadRun(lead1.id());
        Run r2 = leadRun(lead2.id());
        gateRepo.insert(new QaReleaseGate(null, lead1.id(), r1.id(),
                "v001", "PENDING", null, "R1", null, "{}", null,
                1, 0, 0, null, null, null,
                0, 0, 0, 0, null, null, "{}", null, null, null));
        gateRepo.insert(new QaReleaseGate(null, lead2.id(), r2.id(),
                "v001", "PENDING", null, "R1", null, "{}", null,
                1, 0, 0, null, null, null,
                0, 0, 0, 0, null, null, "{}", null, null, null));

        mockMvc.perform(get("/api/qa/gates").param("leadTaskId", String.valueOf(lead1.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("I3: GET /api/qa/gates/不存在 → 40401")
    void getGateNotFound() throws Exception {
        mockMvc.perform(get("/api/qa/gates/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @DisplayName("I4: POST /api/qa/gates/{id}/override → 200 + manual_override=1")
    void overrideGateReturns200() throws Exception {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        QaReleaseGate g = gateRepo.insert(new QaReleaseGate(null, lead.id(), leadRun.id(),
                "v001", "INCONCLUSIVE", null, "R1", null, "{}", null,
                1, 0, 0, null, null, null,
                0, 0, 0, 0, null, null, "{}", null, null, null));

        String body = mapper.writeValueAsString(
                java.util.Map.of("finalResult", "FAIL",
                        "reason", "人工否决",
                        "by", "admin"));

        mockMvc.perform(post("/api/qa/gates/" + g.id() + "/override")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAIL"))
                .andExpect(jsonPath("$.data.manualOverride").value(1))
                .andExpect(jsonPath("$.data.overrideBy").value("admin"));
    }

    @Test
    @DisplayName("I5: POST override 非法 finalResult → 40001")
    void overrideGateInvalidResult() throws Exception {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        QaReleaseGate g = gateRepo.insert(new QaReleaseGate(null, lead.id(), leadRun.id(),
                "v001", "PENDING", null, "R1", null, "{}", null,
                1, 0, 0, null, null, null,
                0, 0, 0, 0, null, null, "{}", null, null, null));

        String body = mapper.writeValueAsString(
                java.util.Map.of("finalResult", "INVALID", "by", "admin"));

        mockMvc.perform(post("/api/qa/gates/" + g.id() + "/override")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("I6: GET /api/qa/orchestrations/{leadTaskId}/assignments → 200 + assignment 列表")
    void listAssignmentsReturns200() throws Exception {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        orchestrator.createSubtask(lead, leadRun, "PLAYER_NOVICE", "v001");
        orchestrator.createSubtask(lead, leadRun, "COMBAT_ANALYST", "v001");

        mockMvc.perform(get("/api/qa/orchestrations/" + lead.id() + "/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ==================== J. 门禁状态域闭合（Phase 2 H-7/§11） ====================

    @Test
    @DisplayName("J1: QaReleaseGate.STATUSES 包含 INCONCLUSIVE 且 AUTOMATIC_RESULTS 全子集（域闭合）")
    void gateStatusDomainClosure() {
        assertThat(QaReleaseGate.STATUSES)
                .contains("PENDING", "PASS", "CONDITIONAL_PASS", "FAIL", "INCONCLUSIVE");
        assertThat(QaReleaseGate.AUTOMATIC_RESULTS).contains("INCONCLUSIVE");
        // 域一致性：所有 AUTOMATIC_RESULTS 也应是合法 STATUSES（PENDING 除外，它是初始态）
        assertThat(QaReleaseGate.AUTOMATIC_RESULTS)
                .allSatisfy(s -> assertThat(QaReleaseGate.STATUSES).contains(s));
    }

    @Test
    @DisplayName("J2: completeOrchestration 遇 child TIMEOUT → gate.status=INCONCLUSIVE 落库（红线：绝不 PASS）")
    void gateResultInconclusiveOnTimeoutPersisted() {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());

        // 7 角色：EXPLOIT_HUNTER 超时，其余 COMPLETED
        for (String role : new String[]{
                "PLAYER_NOVICE", "PLAYER_AGGRESSIVE", "PLAYER_BUILD_SPECIALIST",
                "COMBAT_ANALYST", "NUMERICAL_ANALYST", "EXPLOIT_HUNTER", "UX_REVIEWER"}) {
            QaRoleAssignment a = orchestrator.createSubtask(lead, leadRun, role, "v001");
            Run subRun = orchestrator.startSubrun(lead, a.subTaskId());
            if ("EXPLOIT_HUNTER".equals(role)) {
                runRepo.updateStatusAndStep(subRun.id(), com.aigamelab.domain.RunStatus.TIMEOUT, subRun.currentStep());
                runRepo.finish(subRun.id(), com.aigamelab.domain.RunStatus.TIMEOUT, null, "timeout");
            } else {
                runRepo.updateStatusAndStep(subRun.id(), com.aigamelab.domain.RunStatus.COMPLETED, subRun.currentStep());
                runRepo.finish(subRun.id(), com.aigamelab.domain.RunStatus.COMPLETED, "ok", null);
            }
        }
        for (QaRoleAssignment a : assignmentRepo.findByLeadTask(lead.id())) {
            if ("RUNNING".equals(a.status())) {
                assignmentRepo.updateStatus(a.id(), a.role().equals("EXPLOIT_HUNTER") ? "TIMEOUT" : "COMPLETED", "ok", null);
            }
        }

        QaReleaseGate gate = orchestrator.completeOrchestration(
                lead, leadRun, findingService, "v001", "Lead says: PASS", "{}");

        // 红线断言：TIMEOUT 绝不 PASS；domain 已含 INCONCLUSIVE
        assertThat(gate.automaticResult()).isEqualTo("INCONCLUSIVE");
        assertThat(gate.status()).isEqualTo("INCONCLUSIVE");
        assertThat(QaReleaseGate.STATUSES).contains(gate.status());
    }

    // ==================== K. 跨 lead 隔离（Phase 2 §14 硬化） ====================

    @Test
    @DisplayName("K1: checkSubrunStatus 跨 lead 子 Run → 40401 拒绝回写（隔离）")
    void checkSubrunStatusCrossLeadIsolation() {
        Task leadA = leadTask();
        Run leadRunA = leadRun(leadA.id());
        QaRoleAssignment aA = orchestrator.createSubtask(leadA, leadRunA, "PLAYER_NOVICE", "v001");
        Run subRunA = orchestrator.startSubrun(leadA, aA.subTaskId());

        Task leadB = leadTask();
        Run leadRunB = leadRun(leadB.id());
        orchestrator.createSubtask(leadB, leadRunB, "PLAYER_NOVICE", "v001");

        // leadB 冒充轮询 leadA 的子 Run → 必须被拒绝（不能读，也不能回写 leadA 的 assignment）
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> orchestrator.checkSubrunStatus(leadB, subRunA.id()))
                .isInstanceOf(com.aigamelab.common.BusinessException.class)
                .hasMessageContaining("不属于 leadTask");
    }

    @Test
    @DisplayName("K2: checkSubrunStatus 合法 lead 轮询本 lead 子 Run 仍可用（回归守卫）")
    void checkSubrunStatusOwnLeadStillWorks() {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        QaRoleAssignment a = orchestrator.createSubtask(lead, leadRun, "NUMERICAL_ANALYST", "v001");
        Run subRun = orchestrator.startSubrun(lead, a.subTaskId());

        Run result = orchestrator.checkSubrunStatus(lead, subRun.id());
        assertThat(result.id()).isEqualTo(subRun.id());
    }

    // ==================== L. deadline 治理（Phase 2 H-5/§12） ====================

    @Test
    @DisplayName("L1: deadline 超时后 startSubrun 拒绝启动新 role（40901）")
    void startSubrunRejectedWhenDeadlineExceeded() {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        QaRoleAssignment a = orchestrator.createSubtask(lead, leadRun, "PLAYER_NOVICE", "v001");

        // 固化已过期 deadline
        orchestrator.setDeadlineForTest(lead.id(), System.currentTimeMillis() - 1000);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> orchestrator.startSubrun(lead, a.subTaskId()))
                .isInstanceOf(com.aigamelab.common.BusinessException.class)
                .hasMessageContaining("deadline");
    }

    @Test
    @DisplayName("L2: deadline 超时后 completeOrchestration 冻结 RUNNING→TIMEOUT 且 Gate 不 PASS")
    void completeOrchestrationFreezesRunningOnDeadline() {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());

        // 仅创建 1 个角色并 RUNNING（真实编排中 deadline 超时仍有 RUNNING 子 run）
        QaRoleAssignment a = orchestrator.createSubtask(lead, leadRun, "PLAYER_NOVICE", "v001");
        Run subRun = orchestrator.startSubrun(lead, a.subTaskId());

        // 固化已过期 deadline → completeOrchestration 应先冻结
        orchestrator.setDeadlineForTest(lead.id(), System.currentTimeMillis() - 1000);

        QaReleaseGate gate = orchestrator.completeOrchestration(
                lead, leadRun, findingService, "v001", "Lead says: PASS", "{}");

        // RUNNING assignment 因 deadline 被冻结为 TIMEOUT
        QaRoleAssignment updated = assignmentRepo.findById(a.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo("TIMEOUT");

        // gate 不得 PASS（freeze §10：child timeout ≠ PASS）
        assertThat(gate.automaticResult()).isEqualTo("INCONCLUSIVE");
    }

    // ==================== M. child 预算（Phase 2 §18 治理验证） ====================

    @Test
    @DisplayName("M1: startSubrun 为 child run 固化 step/token 预算，未绕过 BudgetGuard")
    void childRunBudgetAllocated() {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        QaRoleAssignment a = orchestrator.createSubtask(lead, leadRun, "COMBAT_ANALYST", "v001");
        Run subRun = orchestrator.startSubrun(lead, a.subTaskId());

        // child run 带独立 budget 字段（不共享 lead 的 budget，不隐式无限）
        assertThat(subRun.maxSteps()).isEqualTo(50);
        assertThat(subRun.tokenBudget()).isEqualTo(100000);
    }

    // ==================== N. cancel 限制文档化（Phase 2 §13） ====================

    @Test
    @DisplayName("N1: 取消 lead run 不级联 child（孤儿风险已文档化，Phase 2 不做级联取消）")
    void cancelDoesNotCascadeToChildren_documentedLimitation() {
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        QaRoleAssignment a = orchestrator.createSubtask(lead, leadRun, "PLAYER_NOVICE", "v001");
        Run subRun = orchestrator.startSubrun(lead, a.subTaskId());

        // 模拟用户取消 lead run（RunControlService 语义：仅 lead 自身 CANCELLED）
        runRepo.updateStatusAndStep(leadRun.id(), com.aigamelab.domain.RunStatus.CANCELLED, leadRun.currentStep());

        // child assignment 保持 RUNNING（不会是 CANCELLED —— 无级联，孤儿风险已知）
        QaRoleAssignment updated = assignmentRepo.findById(a.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo("RUNNING");

        // 底线验证：Gate 对 RUNNING child 判定 INCONCLUSIVE，绝不允许伪装 PASS
        QaReleaseGate gate = orchestrator.completeOrchestration(
                lead, leadRun, findingService, "v001", "Lead says: PASS", "{}");
        assertThat(gate.automaticResult()).isEqualTo("INCONCLUSIVE");
    }

    // ==================== O. QA Phase 3 build share 全链路（HD-1..6） ====================

    /**
     * 落一条 DONE ALL_BUILDS 实验（result_json 仅含 buildDistribution 子树即可派生）。
     * 使用独特 seed 避免 UNIQUE(config,seed,count,scenario) 冲突。
     */
    private com.aigamelab.domain.Experiment seedDoneExperiment(String configVersion, String resultJson) {
        com.aigamelab.domain.Experiment running = experimentRepo.insert(
                new com.aigamelab.domain.Experiment(null, null, configVersion,
                        System.nanoTime(), 300, "ALL_BUILDS", "RUNNING",
                        null, null, null, null, null, null));
        experimentRepo.complete(running.id(), 0.5, 100.0, 10.0, resultJson, 1000L);
        return experimentRepo.findById(running.id()).orElseThrow();
    }

    /** 构造 buildDistribution JSON（games 逐 build）。 */
    private static String buildDistJson(int... gamesPerBuild) {
        StringBuilder sb = new StringBuilder("{\"buildDistribution\":{");
        for (int i = 0; i < gamesPerBuild.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"build").append(i).append("\":{\"games\":").append(gamesPerBuild[i])
                    .append(",\"wins\":").append(gamesPerBuild[i] / 2)
                    .append(",\"winRate\":0.5,\"ciLow\":0.4,\"ciHigh\":0.6}");
        }
        return sb.append("}}").toString();
    }

    /** 建 7 角色 subtask + 模拟全部 COMPLETED（O-series 复用）。 */
    private void completeAllSevenRoles(Task lead, Run leadRun, String configVersion) throws Exception {
        for (String role : new String[]{
                "PLAYER_NOVICE", "PLAYER_AGGRESSIVE", "PLAYER_BUILD_SPECIALIST",
                "COMBAT_ANALYST", "NUMERICAL_ANALYST", "EXPLOIT_HUNTER", "UX_REVIEWER"}) {
            QaRoleAssignment a = orchestrator.createSubtask(lead, leadRun, role, configVersion);
            Run subRun = orchestrator.startSubrun(lead, a.subTaskId());
            runRepo.updateStatusAndStep(subRun.id(), com.aigamelab.domain.RunStatus.COMPLETED, subRun.currentStep());
            runRepo.finish(subRun.id(), com.aigamelab.domain.RunStatus.COMPLETED, "ok", null);
        }
        for (QaRoleAssignment a : assignmentRepo.findByLeadTask(lead.id())) {
            if ("RUNNING".equals(a.status())) {
                assignmentRepo.updateStatus(a.id(), "COMPLETED", "ok", null);
            }
        }
    }

    @Test
    @DisplayName("O1: 均衡实验（share=100/300≈0.333）→ gate PASS + dominantBuildShare 落库且统计留痕")
    void balancedExperimentPassWithSharePersisted() throws Exception {
        seedDoneExperiment("v001-o1", buildDistJson(100, 100, 100));
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        completeAllSevenRoles(lead, leadRun, "v001-o1");

        QaReleaseGate gate = orchestrator.completeOrchestration(
                lead, leadRun, findingService, "v001-o1", "Lead says: PASS", "{}");

        assertThat(gate.automaticResult()).isEqualTo("PASS");
        assertThat(gate.dominantBuildShare()).isEqualTo(100.0 / 300.0);
        assertThat(gate.statsJson()).contains("\"dominantBuildShare\":0.333333");
    }

    @Test
    @DisplayName("O2: 失衡实验（share=90/110≈0.818 &gt; 0.75）→ gate FAIL（Phase 3 接线后失衡分支可触发）")
    void imbalancedExperimentTriggersFail() throws Exception {
        seedDoneExperiment("v001-o2", buildDistJson(90, 10, 10));
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        completeAllSevenRoles(lead, leadRun, "v001-o2");

        QaReleaseGate gate = orchestrator.completeOrchestration(
                lead, leadRun, findingService, "v001-o2", "Lead says: PASS", "{}");

        assertThat(gate.automaticResult()).isEqualTo("FAIL");
        assertThat(gate.dominantBuildShare()).isEqualTo(90.0 / 110.0);
        assertThat(gate.statsJson()).contains("\"dominantBuildShare\":0.818182");
    }

    @Test
    @DisplayName("O3: 无实验（share 缺失）+ 全绿 → gate INCONCLUSIVE + dominantBuildShare=null（HD-5：missing share never PASS）")
    void noExperimentSharesNullMakesGateInconclusive() throws Exception {
        // 不 seed 实验
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        completeAllSevenRoles(lead, leadRun, "v001-o3");

        QaReleaseGate gate = orchestrator.completeOrchestration(
                lead, leadRun, findingService, "v001-o3", "Lead says: PASS", "{}");

        assertThat(gate.automaticResult()).isEqualTo("INCONCLUSIVE");
        assertThat(gate.dominantBuildShare()).isNull();
        assertThat(gate.statsJson()).contains("\"dominantBuildShare\":null");
    }

    @Test
    @DisplayName("O4: 实验存在但非 DONE（RUNNING）→ 视为缺失 → gate INCONCLUSIVE（不取未完成实验，never PASS）")
    void runningExperimentTreatedAsMissing() throws Exception {
        experimentRepo.insert(new com.aigamelab.domain.Experiment(null, null, "v001-o4",
                System.nanoTime(), 300, "ALL_BUILDS", "RUNNING", null, null, null, null, null, null));
        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        completeAllSevenRoles(lead, leadRun, "v001-o4");

        QaReleaseGate gate = orchestrator.completeOrchestration(
                lead, leadRun, findingService, "v001-o4", "Lead says: PASS", "{}");

        assertThat(gate.automaticResult()).isEqualTo("INCONCLUSIVE");
        assertThat(gate.dominantBuildShare()).isNull();
    }

    @Test
    @DisplayName("O5: 同 config 多条实验 → 取最新 DONE（id 升序最后一行为准，快照语义）")
    void latestDoneExperimentWins() throws Exception {
        // 先旧失衡，后新均衡 → 应按最新 DONE 取均衡 → PASS
        com.aigamelab.domain.Experiment older = experimentRepo.insert(
                new com.aigamelab.domain.Experiment(null, null, "v001-o5",
                        System.nanoTime(), 300, "ALL_BUILDS", "RUNNING",
                        null, null, null, null, null, null));
        experimentRepo.complete(older.id(), 0.5, 100.0, 10.0, buildDistJson(90, 10, 10), 1000L);
        seedDoneExperiment("v001-o5", buildDistJson(100, 100, 100));

        Task lead = leadTask();
        Run leadRun = leadRun(lead.id());
        completeAllSevenRoles(lead, leadRun, "v001-o5");

        QaReleaseGate gate = orchestrator.completeOrchestration(
                lead, leadRun, findingService, "v001-o5", "Lead says: PASS", "{}");

        assertThat(gate.automaticResult()).isEqualTo("PASS");
        assertThat(gate.dominantBuildShare()).isEqualTo(100.0 / 300.0);
    }

    @Test
    @DisplayName("O6: 跨 config 隔离（HD-6）——v001-o6 失衡不影响 v001-o6b 的 gate")
    void configIsolationAcrossExperiments() throws Exception {
        seedDoneExperiment("v001-o6", buildDistJson(90, 10, 10)); // 失衡，只属于 o6
        seedDoneExperiment("v001-o6b", buildDistJson(100, 100, 100)); // 均衡，只属于 o6b

        Task leadA = leadTask();
        Run leadRunA = leadRun(leadA.id());
        completeAllSevenRoles(leadA, leadRunA, "v001-o6b");
        QaReleaseGate gateA = orchestrator.completeOrchestration(
                leadA, leadRunA, findingService, "v001-o6b", "Lead says: PASS", "{}");
        assertThat(gateA.automaticResult()).isEqualTo("PASS");
        assertThat(gateA.dominantBuildShare()).isEqualTo(100.0 / 300.0);

        Task leadB = leadTask();
        Run leadRunB = leadRun(leadB.id());
        completeAllSevenRoles(leadB, leadRunB, "v001-o6");
        QaReleaseGate gateB = orchestrator.completeOrchestration(
                leadB, leadRunB, findingService, "v001-o6", "Lead says: PASS", "{}");
        assertThat(gateB.automaticResult()).isEqualTo("FAIL");
        assertThat(gateB.dominantBuildShare()).isEqualTo(90.0 / 110.0);
    }
}
