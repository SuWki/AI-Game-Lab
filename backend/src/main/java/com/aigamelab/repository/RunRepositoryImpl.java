package com.aigamelab.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;

@Repository
public class RunRepositoryImpl implements RunRepository {

    private static final RowMapper<Run> MAPPER = (rs, i) -> new Run(
            rs.getLong("id"),
            rs.getLong("task_id"),
            rs.getString("status"),
            getAsLong(rs, "prompt_version_id"),
            rs.getString("model"),
            rs.getInt("current_step"),
            rs.getInt("max_steps"),
            rs.getInt("token_budget"),
            rs.getLong("tokens_used"),
            rs.getString("error_message"),
            rs.getString("final_result"),
            toTs(rs.getTimestamp("started_at")),
            toTs(rs.getTimestamp("finished_at")),
            toTs(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public RunRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Run insert(Run r) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO run(task_id, status, prompt_version_id, model, max_steps, token_budget)
                    VALUES(?,?,?,?,?,?)
                    """, new String[] {"id"});
            ps.setLong(1, r.taskId());
            ps.setString(2, r.status() != null ? r.status() : RunStatus.PENDING.name());
            if (r.promptVersionId() != null) {
                ps.setLong(3, r.promptVersionId());
            } else {
                ps.setNull(3, java.sql.Types.INTEGER);
            }
            ps.setString(4, r.model() != null ? r.model() : "deepseek-chat");
            ps.setInt(5, r.maxSteps());
            ps.setInt(6, r.tokenBudget());
            return ps;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public Optional<Run> findById(long id) {
        return jdbc.query("SELECT * FROM run WHERE id=?", MAPPER, id).stream().findFirst();
    }

    @Override
    public java.util.List<Run> findByTask(long taskId) {
        return jdbc.query("SELECT * FROM run WHERE task_id=? ORDER BY id", MAPPER, taskId);
    }

    @Override
    public java.util.List<Run> findByStatus(String status) {
        return jdbc.query("SELECT * FROM run WHERE status=? ORDER BY id", MAPPER, status);
    }

    @Override
    public void updateStatusAndStep(long id, RunStatus status, int currentStep) {
        // RUNNING 首次迁移时落 started_at；终态由 finish 负责
        if (status == RunStatus.RUNNING) {
            jdbc.update("""
                    UPDATE run SET status=?, current_step=?,
                        started_at=COALESCE(started_at, datetime('now','localtime'))
                    WHERE id=?
                    """, status.name(), currentStep, id);
        } else {
            jdbc.update("UPDATE run SET status=?, current_step=? WHERE id=?", status.name(), currentStep, id);
        }
    }

    @Override
    public boolean advanceStepIfRunning(long id, int currentStep) {
        // 条件 UPDATE：仅当仍为 RUNNING 时才推进，消除「读-判-写」窗口内
        // 控制方写入的 CANCELLED 被覆写的竞态（P1-4 协作式取消）
        return jdbc.update(
                "UPDATE run SET current_step=? WHERE id=? AND status='RUNNING'",
                currentStep, id) == 1;
    }

    @Override
    public void finish(long id, RunStatus status, String finalResult, String errorMessage) {
        finishIfActive(id,status,finalResult,errorMessage);
    }

    @Override public boolean transitionIf(long id,String expectedStatus,RunStatus next) {
        return jdbc.update("UPDATE run SET status=?, started_at=CASE WHEN ?='RUNNING' THEN COALESCE(started_at,datetime('now','localtime')) ELSE started_at END WHERE id=? AND status=?",
                next.name(),next.name(),id,expectedStatus)==1;
    }

    @Override public boolean finishIfActive(long id,RunStatus status,String finalResult,String errorMessage) {
        return jdbc.update("""
                UPDATE run SET status=?, final_result=?, error_message=?,
                    finished_at=datetime('now','localtime')
                WHERE id=? AND status IN ('PENDING','RUNNING','WAITING_APPROVAL')
                """, status.name(), finalResult, errorMessage, id)==1;
    }

    @Override
    public void addTokensUsed(long id, long delta) {
        jdbc.update("UPDATE run SET tokens_used=tokens_used+? WHERE id=?", delta, id);
    }

    @Override
    public AggregateStats aggregateStats() {
        // 注意：本查询必须保持 FROM run 单表聚合，禁止 JOIN step/tool_call 等
        // 一对多子表——JOIN 会放大行数导致 COUNT/AVG/SUM 全部重复计算。
        // 需要子表统计（如 Tool 调用 TOP）时另开独立查询再在服务层合并。
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS totalRuns,
                       SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END)      AS completedRuns,
                       SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END)         AS failedRuns,
                       SUM(CASE WHEN status='RUNNING' THEN 1 ELSE 0 END)        AS runningRuns,
                       SUM(CASE WHEN status='WAITING_APPROVAL' THEN 1 ELSE 0 END) AS waitingApprovalRuns,
                       SUM(CASE WHEN status='CANCELLED' THEN 1 ELSE 0 END)      AS cancelledRuns,
                       SUM(CASE WHEN status='TIMEOUT' THEN 1 ELSE 0 END)        AS timeoutRuns,
                       AVG(CAST((julianday(finished_at)-julianday(started_at))*86400000 AS REAL)) AS avgDurationMs,
                       COALESCE(SUM(tokens_used),0)                             AS totalTokens
                FROM run
                """, (rs, i) -> new AggregateStats(
                rs.getLong("totalRuns"),
                rs.getLong("completedRuns"),
                rs.getLong("failedRuns"),
                rs.getLong("runningRuns"),
                rs.getLong("waitingApprovalRuns"),
                rs.getLong("cancelledRuns"),
                rs.getLong("timeoutRuns"),
                (Double) rs.getObject("avgDurationMs"),
                rs.getLong("totalTokens")));
    }

    /** SQLite INTEGER 列可能返回 Integer，统一安全转 Long（null 保持 null）。 */
    private static Long getAsLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static LocalDateTime toTs(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
