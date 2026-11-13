package com.aigamelab.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.aigamelab.domain.Experiment;

@Repository
public class ExperimentRepositoryImpl implements ExperimentRepository {

    private static final RowMapper<Experiment> MAPPER = (rs, i) -> new Experiment(
            rs.getLong("id"),
            getAsLong(rs, "run_id"),
            rs.getString("config_version"),
            rs.getLong("seed"),
            rs.getInt("simulation_count"),
            rs.getString("scenario"),
            rs.getString("status"),
            (Double) rs.getObject("win_rate"),
            (Double) rs.getObject("avg_dps"),
            (Double) rs.getObject("avg_game_time_s"),
            rs.getString("result_json"),
            getAsLong(rs, "duration_ms"),
            toTs(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public ExperimentRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Experiment insert(Experiment e) {
        KeyHolder keys = new GeneratedKeyHolder();
        // UNIQUE 冲突由 JdbcConfig 的翻译器转为 DuplicateKeyException 向上抛
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO experiment(run_id, config_version, seed, simulation_count, scenario, status)
                    VALUES(?,?,?,?,?,?)
                    """, new String[] {"id"});
            if (e.runId() != null) {
                ps.setLong(1, e.runId());
            } else {
                ps.setNull(1, java.sql.Types.INTEGER);
            }
            ps.setString(2, e.configVersion());
            ps.setLong(3, e.seed());
            ps.setInt(4, e.simulationCount());
            ps.setString(5, e.scenario() != null ? e.scenario() : "ALL_BUILDS");
            ps.setString(6, e.status() != null ? e.status() : "RUNNING");
            return ps;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public void complete(long id, double winRate, double avgDps, double avgGameTimeS,
            String resultJson, long durationMs) {
        jdbc.update("""
                UPDATE experiment SET status='DONE', win_rate=?, avg_dps=?, avg_game_time_s=?,
                    result_json=?, duration_ms=? WHERE id=?
                """, winRate, avgDps, avgGameTimeS, resultJson, durationMs, id);
    }

    @Override
    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE experiment SET status=? WHERE id=?", status, id);
    }

    @Override
    public Optional<Experiment> findById(long id) {
        return jdbc.query("SELECT * FROM experiment WHERE id=?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<Experiment> findByFilter(String configVersion, String scenario) {
        StringBuilder sql = new StringBuilder("SELECT * FROM experiment WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (configVersion != null) {
            sql.append(" AND config_version=?");
            args.add(configVersion);
        }
        if (scenario != null) {
            sql.append(" AND scenario=?");
            args.add(scenario);
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public List<Experiment> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query(
                "SELECT * FROM experiment WHERE id IN (" + placeholders + ") ORDER BY id",
                MAPPER, ids.toArray());
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
