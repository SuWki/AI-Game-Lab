package com.aigamelab.repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.aigamelab.domain.ResearchSource;

/**
 * research_source 表 JdbcTemplate 实现（R0-3）。
 */
@Repository
public class ResearchSourceRepositoryImpl implements ResearchSourceRepository {

    /** SQLite 时间 TEXT 统一格式（与 datetime('now','localtime') / 既有表约定一致） */
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final RowMapper<ResearchSource> MAPPER = (rs, i) -> new ResearchSource(
            rs.getLong("id"),
            rs.getLong("run_id"),
            rs.getLong("task_id"),
            rs.getString("url"),
            rs.getString("source_type"),
            rs.getString("title"),
            rs.getString("content_summary"),
            (Double) rs.getObject("reliability"),
            toLdt(rs.getString("fetched_at")),
            toLdt(rs.getString("created_at")));

    private final JdbcTemplate jdbc;

    public ResearchSourceRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ResearchSource insert(ResearchSource s) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO research_source(run_id, task_id, url, source_type, title,
                        content_summary, reliability, fetched_at)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, new String[] {"id"});
            ps.setLong(1, s.runId());
            ps.setLong(2, s.taskId());
            ps.setString(3, s.url());
            ps.setString(4, s.sourceType());
            ps.setString(5, s.title());
            ps.setString(6, s.contentSummary());
            if (s.reliability() != null) {
                ps.setDouble(7, s.reliability());
            } else {
                ps.setNull(7, java.sql.Types.REAL);
            }
            if (s.fetchedAt() != null) {
                // 注意：SQLite JDBC 驱动 setTimestamp 会写 epoch millis，
                // getTimestamp 无法把它解析回（"Error parsing time stamp"）。
                // 既有的 started_at/finished_at 均以 TEXT 存储，此处保持一致。
                ps.setString(8, s.fetchedAt().format(TS_FMT));
            } else {
                ps.setNull(8, java.sql.Types.VARCHAR);
            }
            return ps;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public Optional<ResearchSource> findById(long id) {
        return jdbc.query("SELECT * FROM research_source WHERE id=?", MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public List<ResearchSource> findByRunId(long runId) {
        return jdbc.query("SELECT * FROM research_source WHERE run_id=? ORDER BY id", MAPPER, runId);
    }

    @Override
    public List<ResearchSource> findByTaskId(long taskId) {
        return jdbc.query("SELECT * FROM research_source WHERE task_id=? ORDER BY id", MAPPER, taskId);
    }

    @Override
    public Optional<ResearchSource> findByRunIdAndUrl(long runId, String url) {
        return jdbc.query("SELECT * FROM research_source WHERE run_id=? AND url=? ORDER BY id LIMIT 1",
                MAPPER, runId, url).stream().findFirst();
    }

    /** 容错解析 SQLite 时间 TEXT；null/异常则返回 null（与既有库约定：可空时间列）。 */
    private static LocalDateTime toLdt(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), TS_FMT);
        } catch (Exception e) {
            return null; // 兼容 SQLite datetime('now','localtime') 的同格式输出
        }
    }
}
