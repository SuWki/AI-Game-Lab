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

import com.aigamelab.domain.TestPlan;

/**
 * test_plan 表 JdbcTemplate 实现（T-DB1 已批准 DDL）。
 * 风格对照 ExperimentRepositoryImpl：KeyHolder 回填自增 id、TEXT 状态列、
 * datetime 列经 Timestamp 转 LocalDateTime。
 */
@Repository
public class TestPlanRepositoryImpl implements TestPlanRepository {

    private static final RowMapper<TestPlan> MAPPER = (rs, i) -> new TestPlan(
            rs.getLong("id"),
            rs.getLong("task_id"),
            rs.getString("config_version"),
            rs.getString("status"),
            rs.getString("scenarios_json"),
            toTs(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public TestPlanRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TestPlan insert(TestPlan plan) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO test_plan(task_id, config_version, status, scenarios_json)
                    VALUES(?,?,?,?)
                    """, new String[] {"id"});
            ps.setLong(1, plan.taskId());
            ps.setString(2, plan.configVersion());
            ps.setString(3, plan.status() != null ? plan.status() : TestPlan.STATUS_DRAFT);
            ps.setString(4, plan.scenariosJson());
            return ps;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE test_plan SET status=? WHERE id=?", status, id);
    }

    /** 用户编辑 scenarios（仅 DRAFT 态允许，由 Service 层校验；此处只管持久化） */
    @Override
    public void updateScenarios(long id, String scenariosJson) {
        jdbc.update("UPDATE test_plan SET scenarios_json=? WHERE id=?", scenariosJson, id);
    }

    @Override
    public Optional<TestPlan> findById(long id) {
        return jdbc.query("SELECT * FROM test_plan WHERE id=?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<TestPlan> findByTaskId(long taskId) {
        return jdbc.query("SELECT * FROM test_plan WHERE task_id=? ORDER BY id", MAPPER, taskId);
    }

    private static LocalDateTime toTs(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
