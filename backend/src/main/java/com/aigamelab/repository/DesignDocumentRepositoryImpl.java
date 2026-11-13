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

import com.aigamelab.domain.DesignDocument;

@Repository
public class DesignDocumentRepositoryImpl implements DesignDocumentRepository {

    private static final RowMapper<DesignDocument> MAPPER = (rs, i) -> new DesignDocument(
            rs.getLong("id"),
            getAsLong(rs, "run_id"),
            getAsLong(rs, "task_id"),
            rs.getString("title"),
            rs.getString("content_md"),
            toTs(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public DesignDocumentRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DesignDocument insert(DesignDocument d) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO design_document(run_id, task_id, title, content_md)
                    VALUES(?,?,?,?)
                    """, new String[] {"id"});
            if (d.runId() != null) {
                ps.setLong(1, d.runId());
            } else {
                ps.setNull(1, java.sql.Types.INTEGER);
            }
            if (d.taskId() != null) {
                ps.setLong(2, d.taskId());
            } else {
                ps.setNull(2, java.sql.Types.INTEGER);
            }
            ps.setString(3, d.title());
            ps.setString(4, d.contentMd());
            return ps;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public Optional<DesignDocument> findById(long id) {
        return jdbc.query("SELECT * FROM design_document WHERE id=?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<DesignDocument> findByTaskId(long taskId) {
        return jdbc.query("SELECT * FROM design_document WHERE task_id=? ORDER BY id", MAPPER, taskId);
    }

    @Override
    public List<DesignDocument> findByRunId(long runId) {
        return jdbc.query("SELECT * FROM design_document WHERE run_id=? ORDER BY id", MAPPER, runId);
    }

    @Override
    public void deleteById(long id) {
        jdbc.update("DELETE FROM design_document WHERE id=?", id);
    }

    @Override
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM design_document", Long.class);
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
