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

import com.aigamelab.domain.AgentEvent;

@Repository
public class AgentEventRepositoryImpl implements AgentEventRepository {

    private static final RowMapper<AgentEvent> MAPPER = (rs, i) -> new AgentEvent(
            rs.getLong("id"),
            rs.getString("event_id"),
            rs.getString("type"),
            rs.getString("run_id"),
            rs.getString("task_id"),
            rs.getString("payload_json"),
            toTs(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public AgentEventRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AgentEvent insert(AgentEvent e) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO agent_event(event_id, type, run_id, task_id, payload_json)
                    VALUES(?,?,?,?,?)
                    """, new String[] {"id"});
            ps.setString(1, e.eventId());
            ps.setString(2, e.type());
            ps.setString(3, e.runId());
            ps.setString(4, e.taskId());
            ps.setString(5, e.payloadJson() != null ? e.payloadJson() : "{}");
            return ps;
        }, keys);
        // 回读拿 DB 生成的 id 与 created_at
        Long id = keys.getKey() != null ? keys.getKey().longValue() : null;
        if (id == null) {
            // 兜底：按唯一 eventId 找回
            return jdbc.query("SELECT * FROM agent_event WHERE event_id=?", MAPPER, e.eventId())
                    .stream().findFirst().orElseThrow();
        }
        return findById(id).orElseThrow();
    }

    @Override
    public Optional<AgentEvent> findById(long id) {
        return jdbc.query("SELECT * FROM agent_event WHERE id=?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<AgentEvent> findByRunIdAfter(long runId, long afterId, int limit) {
        return jdbc.query("""
                SELECT * FROM agent_event WHERE run_id=? AND id>? ORDER BY id LIMIT ?
                """, MAPPER, String.valueOf(runId), afterId, limit);
    }

    @Override
    public List<AgentEvent> findRecent(int limit) {
        return jdbc.query("SELECT * FROM agent_event ORDER BY id DESC LIMIT ?", MAPPER, limit);
    }

    private static LocalDateTime toTs(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
