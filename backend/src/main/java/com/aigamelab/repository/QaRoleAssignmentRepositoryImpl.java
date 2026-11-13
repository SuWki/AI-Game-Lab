package com.aigamelab.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.aigamelab.domain.QaRoleAssignment;

@Repository
public class QaRoleAssignmentRepositoryImpl implements QaRoleAssignmentRepository {

    private static final RowMapper<QaRoleAssignment> MAPPER = (rs, i) -> {
        try {
            return new QaRoleAssignment(
                    rs.getLong("id"),
                    rs.getLong("parent_run_id"),
                    rs.getLong("lead_task_id"),
                    getAsLong(rs, "sub_task_id"),
                    getAsLong(rs, "sub_run_id"),
                    rs.getString("role"),
                    rs.getString("player_profile"),
                    rs.getString("status"),
                    rs.getInt("order_no"),
                    rs.getString("result_summary"),
                    rs.getString("error_message"),
                    getAsInt(rs, "sub_run_steps_used"),
                    getAsInt(rs, "sub_run_tokens_used"),
                    rs.getString("detail_json"),
                    toTs(rs.getString("created_at")),
                    toTs(rs.getString("updated_at")));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    };

    private final JdbcTemplate jdbc;

    public QaRoleAssignmentRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public QaRoleAssignment insert(QaRoleAssignment a) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO qa_role_assignment
                        (parent_run_id, lead_task_id, sub_task_id, sub_run_id, role,
                         player_profile, status, order_no, result_summary, error_message,
                         detail_json)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """, new String[] {"id"});
            ps.setLong(1, a.parentRunId());
            ps.setLong(2, a.leadTaskId());
            setLong(ps, 3, a.subTaskId());
            setLong(ps, 4, a.subRunId());
            ps.setString(5, a.role());
            ps.setString(6, a.playerProfile());
            ps.setString(7, a.status() != null ? a.status() : "PENDING");
            ps.setInt(8, a.orderNo());
            ps.setString(9, a.resultSummary());
            ps.setString(10, a.errorMessage());
            ps.setString(11, a.detailJson() != null ? a.detailJson() : "{}");
            return ps;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public Optional<QaRoleAssignment> findById(long id) {
        return jdbc.query("SELECT * FROM qa_role_assignment WHERE id=?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<QaRoleAssignment> findByLeadTask(long leadTaskId) {
        return jdbc.query("SELECT * FROM qa_role_assignment WHERE lead_task_id=? ORDER BY order_no", MAPPER, leadTaskId);
    }

    @Override
    public List<QaRoleAssignment> findByParentRun(long parentRunId) {
        return jdbc.query("SELECT * FROM qa_role_assignment WHERE parent_run_id=? ORDER BY order_no", MAPPER, parentRunId);
    }

    @Override
    public Optional<QaRoleAssignment> findByLeadTaskAndRole(long leadTaskId, String role) {
        return jdbc.query("SELECT * FROM qa_role_assignment WHERE lead_task_id=? AND role=?",
                MAPPER, leadTaskId, role).stream().findFirst();
    }

    @Override
    public Optional<QaRoleAssignment> findBySubTask(long subTaskId) {
        return jdbc.query("SELECT * FROM qa_role_assignment WHERE sub_task_id=?",
                MAPPER, subTaskId).stream().findFirst();
    }

    @Override
    public void updateStatus(long id, String status, String resultSummary, String errorMessage) {
        jdbc.update("""
                UPDATE qa_role_assignment
                SET status=?, result_summary=?, error_message=?, updated_at=datetime('now','localtime')
                WHERE id=?
                """, status, resultSummary, errorMessage, id);
    }

    @Override
    public void bindSubRun(long id, Long subTaskId, Long subRunId, String status,
                           Integer stepsUsed, Integer tokensUsed) {
        jdbc.update("""
                UPDATE qa_role_assignment
                SET sub_task_id=?, sub_run_id=?, status=?,
                    sub_run_steps_used=?, sub_run_tokens_used=?,
                    updated_at=datetime('now','localtime')
                WHERE id=?
                """, subTaskId, subRunId, status, stepsUsed, tokensUsed, id);
    }

    @Override
    public void updateDetail(long id, String detailJson) {
        jdbc.update("""
                UPDATE qa_role_assignment SET detail_json=?, updated_at=datetime('now','localtime') WHERE id=?
                """, detailJson, id);
    }

    private static Long getAsLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer getAsInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static java.time.LocalDateTime toTs(String s) {
        return s == null ? null : java.time.LocalDateTime.parse(s.replace(' ', 'T'));
    }

    private static void setLong(java.sql.PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v != null) {
            ps.setLong(idx, v);
        } else {
            ps.setNull(idx, java.sql.Types.INTEGER);
        }
    }
}