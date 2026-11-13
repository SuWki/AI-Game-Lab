package com.aigamelab.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aigamelab.domain.AgentEvent;
import com.aigamelab.domain.ChangeProposal;
import com.aigamelab.domain.ConfigVersion;
import com.aigamelab.domain.DesignDocument;
import com.aigamelab.domain.Experiment;
import com.aigamelab.domain.PromptVersion;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.domain.Step;
import com.aigamelab.domain.Task;
import com.aigamelab.domain.ToolCall;

/**
 * P0-3 验收测试：10 个 Repository 全 CRUD + 实际数据读写一致性验证。
 * 覆盖：Create/Read/Update/Delete、Not Found、UNIQUE 冲突（DuplicateKeyException）、
 * 边界数据、空值字段、事务行为（PromptVersion.setActive）。
 * 所有断言基于回读的数据库真实数据，不依赖上下文启动成功与否。
 */
@DisplayName("P0-3 Repository 层集成测试")
class RepositoryTest extends AbstractRepositoryTest {

    @Autowired TaskRepository taskRepo;
    @Autowired RunRepository runRepo;
    @Autowired StepRepository stepRepo;
    @Autowired ToolCallRepository toolCallRepo;
    @Autowired ExperimentRepository experimentRepo;
    @Autowired ConfigVersionRepository configRepo;
    @Autowired ChangeProposalRepository proposalRepo;
    @Autowired AgentEventRepository eventRepo;
    @Autowired DesignDocumentRepository docRepo;
    @Autowired PromptVersionRepository promptRepo;

    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanAll() {
        // 子表先删，保证每个用例从空库开始，互不污染
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM step");
        jdbc.update("DELETE FROM experiment");
        jdbc.update("DELETE FROM change_proposal");
        jdbc.update("DELETE FROM game_config_version");
        jdbc.update("DELETE FROM design_document");
        jdbc.update("DELETE FROM prompt_version");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM task");
    }

    private Task newTask() {
        return taskRepo.insert(new Task(null, "平衡三 Build", "crit/penetration/as 胜率差距<5%",
                "{\"deadline\":\"2h\"}", 30, true, "ACTIVE", null));
    }

    private Run newRun(long taskId) {
        return runRepo.insert(new Run(null, taskId, "PENDING", null, null, 0, 25, 100000,
                0, null, null, null, null, null));
    }

    // ==================== TaskRepository ====================

    @Test
    void taskCrudRoundtrip() {
        Task t = newTask();
        assertThat(t.id()).isNotNull();
        assertThat(t.createdAt()).isNotNull(); // DB 默认值已回填

        var read = taskRepo.findById(t.id()).orElseThrow();
        assertThat(read.title()).isEqualTo("平衡三 Build");
        assertThat(read.maxSteps()).isEqualTo(30);
        assertThat(read.approvalRequired()).isTrue();

        taskRepo.updateStatus(t.id(), "ARCHIVED");
        assertThat(taskRepo.findById(t.id())).map(Task::status).contains("ARCHIVED");

        assertThat(taskRepo.findAll()).hasSize(1);
    }

    @Test
    void taskNotFoundAndDefaults() {
        assertThat(taskRepo.findById(999L)).isEmpty();
        // 边界：null 可选字段走 DB 默认
        Task minimal = taskRepo.insert(new Task(null, "t", "g", null, 1, false, null, null));
        var read = taskRepo.findById(minimal.id()).orElseThrow();
        assertThat(read.constraintsJson()).isEqualTo("{}"); // DEFAULT '{}'
        assertThat(read.status()).isEqualTo("ACTIVE");      // DEFAULT 'ACTIVE'
    }

    // ==================== RunRepository ====================

    @Test
    void runLifecycleWithAtomicFinish() {
        Run r = newRun(newTask().id());
        assertThat(r.status()).isEqualTo("PENDING");
        assertThat(r.model()).isEqualTo("deepseek-chat"); // DEFAULT

        runRepo.updateStatusAndStep(r.id(), RunStatus.RUNNING, 3);
        var running = runRepo.findById(r.id()).orElseThrow();
        assertThat(running.startedAt()).isNotNull();   // 首次 RUNNING 落 started_at
        assertThat(running.currentStep()).isEqualTo(3);

        runRepo.addTokensUsed(r.id(), 120);
        runRepo.addTokensUsed(r.id(), 80);
        assertThat(runRepo.findById(r.id())).map(Run::tokensUsed).contains(200L);

        runRepo.finish(r.id(), RunStatus.COMPLETED, "达标", null);
        var done = runRepo.findById(r.id()).orElseThrow();
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.finalResult()).isEqualTo("达标");
        assertThat(done.finishedAt()).isNotNull();

        assertThat(runRepo.findByTask(r.taskId())).hasSize(1);
    }

    @Test
    void runAggregateStatsMatchesActualRows() {
        long tid = newTask().id();
        Run a = newRun(tid);
        runRepo.updateStatusAndStep(a.id(), RunStatus.RUNNING, 1);
        runRepo.finish(a.id(), RunStatus.COMPLETED, "ok", null);   // 有时长
        Run b = newRun(tid);
        runRepo.finish(b.id(), RunStatus.FAILED, null, "boom");
        Run c = newRun(tid);
        runRepo.updateStatusAndStep(c.id(), RunStatus.RUNNING, 1);

        var s = runRepo.aggregateStats();
        assertThat(s.totalRuns()).isEqualTo(3);
        assertThat(s.completedRuns()).isEqualTo(1);
        assertThat(s.failedRuns()).isEqualTo(1);
        assertThat(s.runningRuns()).isEqualTo(1);
        assertThat(s.waitingApprovalRuns()).isZero();
        assertThat(s.totalTokens()).isZero();
        assertThat(s.avgDurationMs()).isNotNull(); // 仅 COMPLETED 有 finished-started 区间
    }

    @Test
    void runForeignKeyEnforced() {
        // 空值/非法状态：task_id 无对应行 → FK 拒绝（PRAGMA foreign_keys=ON）
        assertThatThrownBy(() -> newRun(99999L))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ==================== Step / ToolCall ====================

    @Test
    void stepInsertOrderedAndUnique() {
        Run r = newRun(newTask().id());
        stepRepo.insert(new Step(null, r.id(), 1, "LLM_CALL", "分析配置", 100, 50, 800L, null));
        stepRepo.insert(new Step(null, r.id(), 2, "FINAL_ANSWER", "完成", 90, 30, 500L, null));

        List<Step> steps = stepRepo.findByRunId(r.id());
        assertThat(steps).extracting(Step::seq).containsExactly(1, 2); // seq 升序
        assertThat(steps.get(0).llmContent()).isEqualTo("分析配置");

        // UNIQUE(run_id, seq) 冲突 → DuplicateKeyException
        assertThatThrownBy(() -> stepRepo.insert(new Step(null, r.id(), 1, "LLM_CALL", "dup", 0, 0, null, null)))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(stepRepo.findById(999L)).isEmpty();
    }

    @Test
    void toolCallStatusTransition() {
        Run r = newRun(newTask().id());
        Step s = stepRepo.insert(new Step(null, r.id(), 1, "LLM_CALL", null, 10, 20, null, null));

        ToolCall c = toolCallRepo.insert(new ToolCall(null, s.id(), r.id(),
                "get_game_config", "{}", null, "STARTED", "NOT_REQUIRED", null, null, null));
        assertThat(c.startedAt()).isNotNull();

        toolCallRepo.updateStatus(c.id(), "COMPLETED", "{\"version\":\"v001\"}", null);
        var done = toolCallRepo.findById(c.id()).orElseThrow();
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.resultJson()).isEqualTo("{\"version\":\"v001\"}");
        assertThat(done.endedAt()).isNotNull();

        ToolCall f = toolCallRepo.insert(new ToolCall(null, s.id(), r.id(),
                "simulate_game", "{}", null, "STARTED", "NOT_REQUIRED", null, null, null));
        toolCallRepo.updateStatus(f.id(), "FAILED", null, "timeout");
        var failed = toolCallRepo.findById(f.id()).orElseThrow();
        assertThat(failed.error()).isEqualTo("timeout");
        assertThat(failed.resultJson()).isNull(); // FAILED 不写 result

        assertThat(toolCallRepo.findByStep(s.id())).hasSize(2);
        assertThat(toolCallRepo.findByRun(r.id())).hasSize(2);
    }

    // ==================== Experiment：UNIQUE 幂等核心 ====================

    @Test
    void experimentUniqueConflictThrowsDuplicateKey_forIdempotentReuse() {
        // 本用例直接验证 P0-1 异常映射被 ExperimentRepository 正确消费：
        // 同 (configVersion,seed,count,scenario) 二次插入必须抛 DuplicateKeyException，
        // P5-1 ExperimentService 将据此返回 reused:true。
        Experiment first = experimentRepo.insert(new Experiment(null, null,
                "v001", 12345L, 10000, "ALL_BUILDS", "RUNNING", null, null, null, null, null, null));
        assertThat(first.id()).isNotNull();

        assertThatThrownBy(() -> experimentRepo.insert(new Experiment(null, null,
                "v001", 12345L, 10000, "ALL_BUILDS", "RUNNING", null, null, null, null, null, null)))
                .as("同参数二次插入应触发 UNIQUE→DuplicateKeyException")
                .isInstanceOf(DuplicateKeyException.class);

        // 不同 scenario 不冲突——唯一键四元组任一不同即新行
        Experiment other = experimentRepo.insert(new Experiment(null, null,
                "v001", 12345L, 10000, "SWARM", "RUNNING", null, null, null, null, null, null));
        assertThat(other.id()).isNotEqualTo(first.id());
    }

    @Test
    void experimentCompleteBackfillsMetrics() {
        Experiment e = experimentRepo.insert(new Experiment(null, null,
                "v002", 7L, 500, "ALL_BUILDS", "RUNNING", null, null, null, null, null, null));
        experimentRepo.complete(e.id(), 0.55, 42.5, 431.2, "{\"winRate\":0.55}", 9800);

        var done = experimentRepo.findById(e.id()).orElseThrow();
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.winRate()).isEqualTo(0.55);
        assertThat(done.avgDps()).isEqualTo(42.5);
        assertThat(done.avgGameTimeS()).isEqualTo(431.2);
        assertThat(done.resultJson()).contains("winRate");
        assertThat(done.durationMs()).isEqualTo(9800);
    }

    @Test
    void experimentFilterAndFindByIds() {
        experimentRepo.insert(new Experiment(null, null, "v001", 1L, 100, "ALL_BUILDS", "DONE", null, null, null, null, null, null));
        experimentRepo.insert(new Experiment(null, null, "v001", 2L, 100, "SWARM", "DONE", null, null, null, null, null, null));
        Experiment v2 = experimentRepo.insert(new Experiment(null, null, "v002", 1L, 100, "ALL_BUILDS", "RUNNING", null, null, null, null, null, null));

        assertThat(experimentRepo.findByFilter("v001", null)).hasSize(2);
        assertThat(experimentRepo.findByFilter("v001", "SWARM")).hasSize(1);
        assertThat(experimentRepo.findByFilter(null, "ALL_BUILDS")).hasSize(2);
        assertThat(experimentRepo.findByFilter(null, null)).hasSize(3); // 全表

        List<Experiment> picked = experimentRepo.findByIds(List.of(v2.id()));
        assertThat(picked).hasSize(1);
        assertThat(picked.get(0).configVersion()).isEqualTo("v002");
        assertThat(experimentRepo.findByIds(List.of())).isEmpty(); // 边界：空列表
        assertThat(experimentRepo.findByIds(List.of(999L))).isEmpty(); // Not Found
    }

    // ==================== ConfigVersion ====================

    @Test
    void configVersionsAppendOnlyChain() {
        configRepo.insert(new ConfigVersion("v001", "{\"player\":{}}", null, null, "种子配置", null));
        configRepo.insert(new ConfigVersion("v002", "{\"player\":{},\"patched\":true}", "v001", null, "crit 下调", null));
        configRepo.insert(new ConfigVersion("v010", "{}", "v002", null, null, null)); // 字典序> v002

        assertThat(configRepo.findLatest()).map(ConfigVersion::version).contains("v010");
        assertThat(configRepo.findAll()).extracting(ConfigVersion::version)
                .containsExactly("v001", "v002", "v010");

        // 主键 version 冲突 → DuplicateKeyException
        assertThatThrownBy(() -> configRepo.insert(new ConfigVersion("v001", "{}", null, null, null, null)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ==================== ChangeProposal ====================

    @Test
    void proposalDecideApproveAndReject() {
        Run r = newRun(newTask().id());
        ChangeProposal p = proposalRepo.insert(new ChangeProposal(null, r.id(), "v001", null,
                "下调 crit 倍率", "crit 胜率 61% 超 target", "[12]",
                "[{\"path\":\"weapons[0].critMultiplier\",\"before\":2.8,\"after\":2.3}]",
                "{\"full\":\"snapshot\"}", "PENDING", null, null, null));

        assertThat(proposalRepo.findByStatus("PENDING")).hasSize(1);

        // Approve：回填 targetVersion
        proposalRepo.decide(p.id(), "APPROVED", "v002", "同意");
        var approved = proposalRepo.findById(p.id()).orElseThrow();
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.targetVersion()).isEqualTo("v002");
        assertThat(approved.decidedAt()).isNotNull();
        assertThat(approved.decidedComment()).isEqualTo("同意");

        // Reject：targetVersion 保持 null
        ChangeProposal q = proposalRepo.insert(new ChangeProposal(null, null, "v001", null,
                "t", "r", null, "[]", "{}", "PENDING", null, null, null));
        proposalRepo.decide(q.id(), "REJECTED", null, "理由不足");
        var rejected = proposalRepo.findById(q.id()).orElseThrow();
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.targetVersion()).isNull();

        assertThat(proposalRepo.findByStatus("PENDING")).isEmpty();
    }

    // ==================== AgentEvent ====================

    @Test
    void eventReplayAfterIdAndRecent() {
        // Cursor is a persisted event id, not an index within this Run. Other Runs may already have events.
        eventRepo.insert(new AgentEvent(null,"preexisting-other-run","STEP_STARTED","2","1","{}",null));
        long afterId=0;
        for (int i = 1; i <= 5; i++) {
            var saved=eventRepo.insert(new AgentEvent(null, "evt-" + i, "STEP_STARTED",
                    String.valueOf(1), String.valueOf(1), "{\"seq\":" + i + "}", null));
            if(i==2)afterId=saved.id();
        }
        // Last-Event-ID 补发：第二条事件实际游标之后的事件。
        assertThat(afterId).isGreaterThan(2);
        List<AgentEvent> replay = eventRepo.findByRunIdAfter(1, afterId, 1000);
        assertThat(replay).hasSize(3);
        assertThat(replay).extracting(AgentEvent::eventId)
                .containsExactly("evt-3", "evt-4", "evt-5"); // id 升序

        // limit 生效
        assertThat(eventRepo.findByRunIdAfter(1, 0, 2)).hasSize(2);
        // 其他 run 无事件（run_id 为 TEXT 匹配）
        assertThat(eventRepo.findByRunIdAfter(99, 0, 1000)).isEmpty();
        assertThat(eventRepo.findRecent(3)).hasSize(3);

        // eventId UNIQUE → DuplicateKeyException
        assertThatThrownBy(() -> eventRepo.insert(new AgentEvent(null, "evt-1", "X", null, null, null, null)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ==================== DesignDocument ====================

    @Test
    void designDocumentCrudAndDelete() {
        long tid = newTask().id();
        DesignDocument d = docRepo.insert(new DesignDocument(null, null, tid,
                "平衡报告 v1", "# 结论\n三 build 已对齐", null));
        assertThat(docRepo.count()).isEqualTo(1);
        assertThat(docRepo.findByTaskId(tid)).hasSize(1);

        docRepo.deleteById(d.id());
        assertThat(docRepo.count()).isZero();
        assertThat(docRepo.findById(d.id())).isEmpty();
        docRepo.deleteById(d.id()); // 幂等删除不抛异常
    }

    // ==================== PromptVersion：事务行为 ====================

    @Test
    void promptSetActiveIsTransactionalSingleActive() {
        PromptVersion p1 = promptRepo.insert(new PromptVersion(null, "p001", "SYSTEM PROMPT V1", "初版", false, null));
        PromptVersion p2 = promptRepo.insert(new PromptVersion(null, "p002", "SYSTEM PROMPT V2", null, false, null));

        assertThat(promptRepo.findActive()).isEmpty(); // 初始无 active

        promptRepo.setActive(p1.id());
        assertThat(promptRepo.findActive()).map(PromptVersion::version).contains("p001");

        promptRepo.setActive(p2.id());
        // 事务保证：切换后全表仍只有一条 active
        assertThat(promptRepo.findActive()).map(PromptVersion::version).contains("p002");
        long activeCount = jdbc.queryForObject("SELECT COUNT(*) FROM prompt_version WHERE is_active=1", Long.class);
        assertThat(activeCount).isEqualTo(1);

        // version UNIQUE → DuplicateKeyException
        assertThatThrownBy(() -> promptRepo.insert(new PromptVersion(null, "p001", "dup", null, false, null)))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(promptRepo.findById(p1.id())).map(PromptVersion::active).contains(false);
        assertThat(promptRepo.findAll()).hasSize(2);
    }

    @Test
    void promptSetActiveRollsBackWhenSecondUpdateFails() {
        // 事务回滚验证：用 RAISE 触发器强制 setActive 的第二条 UPDATE 失败，
        // 断言第一条 UPDATE（清全表 active）也被回滚——不允许出现"全表无 active"的中间态残留。
        PromptVersion p1 = promptRepo.insert(new PromptVersion(null, "p001", "SP1", null, false, null));
        PromptVersion p2 = promptRepo.insert(new PromptVersion(null, "p002", "SP2", null, false, null));
        promptRepo.setActive(p1.id());
        assertThat(promptRepo.findActive()).map(PromptVersion::version).contains("p001");

        jdbc.execute("""
                CREATE TRIGGER fail_second_update BEFORE UPDATE ON prompt_version
                FOR EACH ROW WHEN NEW.is_active=1 AND OLD.is_active=0
                BEGIN SELECT RAISE(ABORT, 'injected failure'); END
                """);
        try {
            assertThatThrownBy(() -> promptRepo.setActive(p2.id()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER fail_second_update");
        }

        // 回滚后状态与调用前完全一致：p1 仍是唯一 active，无半成品
        var active = promptRepo.findActive().orElseThrow();
        assertThat(active.version()).isEqualTo("p001");
        long activeCount = jdbc.queryForObject("SELECT COUNT(*) FROM prompt_version WHERE is_active=1", Long.class);
        assertThat(activeCount).isEqualTo(1);
    }
}
