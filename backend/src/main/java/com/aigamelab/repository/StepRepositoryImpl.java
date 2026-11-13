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

import com.aigamelab.domain.Step;

@Repository
public class StepRepositoryImpl implements StepRepository {

    private static final RowMapper<Step> MAPPER = (rs, i) -> new Step(
            rs.getLong("id"),
            rs.getLong("run_id"),
            rs.getInt("seq"),
            rs.getString("phase"),
            rs.getString("llm_content"),
            rs.getLong("prompt_tokens"),
            rs.getLong("completion_tokens"),
            getAsLong(rs, "duration_ms"),
            toTs(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public StepRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Step insert(Step s) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO step(run_id, seq, phase, llm_content, prompt_tokens, completion_tokens, duration_ms)
                    VALUES(?,?,?,?,?,?,?)
                    """, new String[] {"id"});
            ps.setLong(1, s.runId());
            ps.setInt(2, s.seq());
            ps.setString(3, s.phase());
            ps.setString(4, s.llmContent());
            ps.setLong(5, s.promptTokens());
            ps.setLong(6, s.completionTokens());
            if (s.durationMs() != null) {
                ps.setLong(7, s.durationMs());
            } else {
                ps.setNull(7, java.sql.Types.INTEGER);
            }
            return ps;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public Optional<Step> findById(long id) {
        return jdbc.query("SELECT * FROM step WHERE id=?", MAPPER, id).stream().findFirst();
    }

    @Override
    public java.util.List<Step> findByRunId(long runId) {
        return jdbc.query("SELECT * FROM step WHERE run_id=? ORDER BY seq", MAPPER, runId);
    }

    @Override
    public void updateLlmResult(long id, String phase, String llmContent,
            long promptTokens, long completionTokens, Long durationMs) {
        jdbc.update("""
                UPDATE step SET phase=?, llm_content=?, prompt_tokens=?,
                    completion_tokens=?, duration_ms=? WHERE id=?
                """, phase, llmContent, promptTokens, completionTokens, durationMs, id);
    }

    @Override
    public int countIdenticalRecentToolCalls(long runId, String toolName,
            String argumentsJson, int window) {
        // 最近 window 次调用（id 降序取 N 再数相同），基于 DB 派生——重启后仍有效
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT tool_name, arguments_json FROM tool_call
                    WHERE run_id=? ORDER BY id DESC LIMIT ?
                ) WHERE tool_name=? AND arguments_json=?
                """, Integer.class, runId, window, toolName, argumentsJson);
        return n == null ? 0 : n;
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
