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

import com.aigamelab.domain.ChangeProposal;

@Repository
public class ChangeProposalRepositoryImpl implements ChangeProposalRepository {

    private static final RowMapper<ChangeProposal> MAPPER = (rs, i) -> new ChangeProposal(
            rs.getLong("id"),
            getAsLong(rs, "run_id"),
            rs.getString("base_version"),
            rs.getString("target_version"),
            rs.getString("title"),
            rs.getString("reason"),
            rs.getString("evidence_experiment_ids"),
            rs.getString("diff_json"),
            rs.getString("after_config_json"),
            rs.getString("status"),
            toTs(rs.getTimestamp("decided_at")),
            rs.getString("decided_comment"),
            toTs(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public ChangeProposalRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ChangeProposal insert(ChangeProposal p) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO change_proposal(run_id, base_version, title, reason,
                        evidence_experiment_ids, diff_json, after_config_json, status)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, new String[] {"id"});
            if (p.runId() != null) {
                ps.setLong(1, p.runId());
            } else {
                ps.setNull(1, java.sql.Types.INTEGER);
            }
            ps.setString(2, p.baseVersion());
            ps.setString(3, p.title());
            ps.setString(4, p.reason());
            ps.setString(5, p.evidenceExperimentIds() != null ? p.evidenceExperimentIds() : "[]");
            ps.setString(6, p.diffJson());
            ps.setString(7, p.afterConfigJson());
            ps.setString(8, p.status() != null ? p.status() : "PENDING");
            return ps;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public Optional<ChangeProposal> findById(long id) {
        return jdbc.query("SELECT * FROM change_proposal WHERE id=?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<ChangeProposal> findByStatus(String status) {
        return jdbc.query("SELECT * FROM change_proposal WHERE status=? ORDER BY id", MAPPER, status);
    }

    @Override
    public void decide(long id, String status, String targetVersion, String comment) {
        int updated=jdbc.update("""
                UPDATE change_proposal SET status=?, target_version=?,
                    decided_at=datetime('now','localtime'), decided_comment=?
                WHERE id=? AND status='PENDING'
                """, status, targetVersion, comment, id);
        if(updated!=1)throw com.aigamelab.common.BusinessException.conflict("提案已经裁决");
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
