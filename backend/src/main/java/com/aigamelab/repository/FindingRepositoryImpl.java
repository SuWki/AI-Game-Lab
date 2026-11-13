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

import com.aigamelab.domain.Finding;
import com.aigamelab.domain.FindingFilter;

/**
 * finding 表 JdbcTemplate 实现（T-DB1 已批准 DDL）。
 * 可空外键 run_id/test_plan_id/task_id 需显式 setNull；
 * findByFilter 命中 idx_finding_ver_sev（config_version+severity）。
 */
@Repository
public class FindingRepositoryImpl implements FindingRepository {

    private static final RowMapper<Finding> MAPPER = (rs, i) -> new Finding(
            rs.getLong("id"),
            getAsLong(rs, "run_id"),
            getAsLong(rs, "test_plan_id"),
            getAsLong(rs, "task_id"),
            rs.getString("scenario_id"),
            rs.getString("config_version"),
            rs.getString("severity"),
            rs.getString("category"),
            rs.getString("source"),
            rs.getString("title"),
            rs.getString("expected"),
            rs.getString("actual"),
            rs.getString("repro_steps"),
            rs.getString("evidence_json"),
            (Double) rs.getObject("reproduction_rate"),
            rs.getString("status"),
            toTs(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public FindingRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Finding insert(Finding f) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO finding(run_id, test_plan_id, task_id, scenario_id, config_version,
                        severity, category, source, title, expected, actual, repro_steps,
                        evidence_json, reproduction_rate, status)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, new String[] {"id"});
            setNullableLong(ps, 1, f.runId());
            setNullableLong(ps, 2, f.testPlanId());
            setNullableLong(ps, 3, f.taskId());
            ps.setString(4, f.scenarioId());
            ps.setString(5, f.configVersion());
            ps.setString(6, f.severity());
            ps.setString(7, f.category());
            ps.setString(8, f.source() != null ? f.source() : "LLM");
            ps.setString(9, f.title());
            ps.setString(10, f.expected());
            ps.setString(11, f.actual());
            ps.setString(12, f.reproSteps());
            ps.setString(13, f.evidenceJson() != null ? f.evidenceJson() : "{}");
            if (f.reproductionRate() != null) {
                ps.setDouble(14, f.reproductionRate());
            } else {
                ps.setNull(14, java.sql.Types.REAL);
            }
            ps.setString(15, f.status() != null ? f.status() : "OPEN");
            return ps;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE finding SET status=? WHERE id=?", status, id);
    }

    @Override
    public Optional<Finding> findById(long id) {
        return jdbc.query("SELECT * FROM finding WHERE id=?", MAPPER, id).stream().findFirst();
    }

    /**
     * 过滤查询（回归对比核心路径）。参数为 null 表示忽略该条件；
     * configVersion+severity 组合命中 idx_finding_ver_sev。
     */
    @Override
    public List<Finding> findByFilter(String configVersion, String severity, Long runId,
            Long testPlanId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM finding WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (configVersion != null) {
            sql.append(" AND config_version=?");
            args.add(configVersion);
        }
        if (severity != null) {
            sql.append(" AND severity=?");
            args.add(severity);
        }
        if (runId != null) {
            sql.append(" AND run_id=?");
            args.add(runId);
        }
        if (testPlanId != null) {
            sql.append(" AND test_plan_id=?");
            args.add(testPlanId);
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /**
     * QA P1-3 多维过滤（R3-B1）。维度：configVersion/severity/category/source/status
     * 单值精确匹配、runIds 多 Run IN 匹配、testPlanId/taskId 归属匹配，全维度 AND。
     * run_id IN 仅当 runIds 非空才追加；空过滤（全部维度缺席）退化为全表扫描，
     * 语义等价 {@link #findByFilter} 全 null 情形（SELECT * ... WHERE 1=1 ... ORDER BY id）。
     */
    @Override
    public List<Finding> findByQaFilter(FindingFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT * FROM finding WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (filter.configVersion() != null) {
            sql.append(" AND config_version=?");
            args.add(filter.configVersion());
        }
        if (filter.severity() != null) {
            sql.append(" AND severity=?");
            args.add(filter.severity());
        }
        if (filter.category() != null) {
            sql.append(" AND category=?");
            args.add(filter.category());
        }
        if (filter.source() != null) {
            sql.append(" AND source=?");
            args.add(filter.source());
        }
        if (filter.status() != null) {
            sql.append(" AND status=?");
            args.add(filter.status());
        }
        if (!filter.runIds().isEmpty()) {
            sql.append(" AND run_id IN (")
                    .append(String.join(",", java.util.Collections.nCopies(
                            filter.runIds().size(), "?")))
                    .append(")");
            args.addAll(filter.runIds());
        }
        if (filter.testPlanId() != null) {
            sql.append(" AND test_plan_id=?");
            args.add(filter.testPlanId());
        }
        if (filter.taskId() != null) {
            sql.append(" AND task_id=?");
            args.add(filter.taskId());
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** SQLite INTEGER 列可能返回 Integer，统一安全转 Long（null 保持 null）。 */
    private static Long getAsLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static void setNullableLong(java.sql.PreparedStatement ps, int idx, Long v)
            throws java.sql.SQLException {
        if (v != null) {
            ps.setLong(idx, v);
        } else {
            ps.setNull(idx, java.sql.Types.INTEGER);
        }
    }

    private static LocalDateTime toTs(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
