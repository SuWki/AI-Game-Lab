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

import com.aigamelab.domain.ToolCall;

@Repository
public class ToolCallRepositoryImpl implements ToolCallRepository {

    private static final RowMapper<ToolCall> MAPPER = (rs, i) -> new ToolCall(
            rs.getLong("id"),
            rs.getLong("step_id"),
            rs.getLong("run_id"),
            rs.getString("tool_name"),
            rs.getString("arguments_json"),
            rs.getString("result_json"),
            rs.getString("status"),
            rs.getString("approval_status"),
            toTs(rs.getTimestamp("started_at")),
            toTs(rs.getTimestamp("ended_at")),
            rs.getString("error"));

    private final JdbcTemplate jdbc;

    public ToolCallRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ToolCall insert(ToolCall c) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO tool_call(step_id, run_id, tool_name, arguments_json, status, approval_status, started_at)
                    VALUES(?,?,?,?,?,?,datetime('now','localtime'))
                    """, new String[] {"id"});
            ps.setLong(1, c.stepId());
            ps.setLong(2, c.runId());
            ps.setString(3, c.toolName());
            ps.setString(4, c.argumentsJson());
            ps.setString(5, c.status() != null ? c.status() : "STARTED");
            ps.setString(6, c.approvalStatus() != null ? c.approvalStatus() : "NOT_REQUIRED");
            return ps;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public Optional<ToolCall> findById(long id) {
        return jdbc.query("SELECT * FROM tool_call WHERE id=?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<ToolCall> findByStep(long stepId) {
        return jdbc.query("SELECT * FROM tool_call WHERE step_id=? ORDER BY id", MAPPER, stepId);
    }

    @Override
    public List<ToolCall> findByRun(long runId) {
        return jdbc.query("SELECT * FROM tool_call WHERE run_id=? ORDER BY id", MAPPER, runId);
    }

    @Override
    public void updateStatus(long id, String status, String resultJson, String error) {
        jdbc.update("""
                UPDATE tool_call SET status=?, result_json=COALESCE(?,result_json),
                    error=?, ended_at=datetime('now','localtime')
                WHERE id=?
                """, status, resultJson, error, id);
    }

    private static LocalDateTime toTs(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
