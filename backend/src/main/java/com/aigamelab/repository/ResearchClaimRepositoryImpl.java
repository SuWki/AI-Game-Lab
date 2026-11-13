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

import com.aigamelab.domain.ResearchClaim;

/**
 * research_claim 表 JdbcTemplate 实现（R0-3）。
 */
@Repository
public class ResearchClaimRepositoryImpl implements ResearchClaimRepository {

    private static final RowMapper<ResearchClaim> MAPPER = (rs, i) -> new ResearchClaim(
            rs.getLong("id"),
            rs.getLong("run_id"),
            rs.getLong("task_id"),
            rs.getString("claim"),
            rs.getDouble("confidence"),
            rs.getString("supporting_source_ids"),
            rs.getString("contradicting_source_ids"),
            rs.getString("status"),
            toTs(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public ResearchClaimRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ResearchClaim insert(ResearchClaim c) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO research_claim(run_id, task_id, claim, confidence,
                        supporting_source_ids, contradicting_source_ids, status)
                    VALUES(?,?,?,?,?,?,?)
                    """, new String[] {"id"});
            ps.setLong(1, c.runId());
            ps.setLong(2, c.taskId());
            ps.setString(3, c.claim());
            ps.setDouble(4, c.confidence());
            ps.setString(5, c.supportingSourceIds() != null ? c.supportingSourceIds() : "[]");
            ps.setString(6, c.contradictingSourceIds() != null ? c.contradictingSourceIds() : "[]");
            ps.setString(7, c.status() != null ? c.status() : "DRAFT");
            return ps;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public Optional<ResearchClaim> findById(long id) {
        return jdbc.query("SELECT * FROM research_claim WHERE id=?", MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public void updateConfidence(long id, double confidence) {
        jdbc.update("UPDATE research_claim SET confidence=? WHERE id=?", confidence, id);
    }

    @Override
    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE research_claim SET status=? WHERE id=?", status, id);
    }

    @Override
    public void updateSourceIds(long id, String supportingSourceIds, String contradictingSourceIds) {
        jdbc.update("UPDATE research_claim SET supporting_source_ids=?, contradicting_source_ids=? WHERE id=?",
                supportingSourceIds, contradictingSourceIds, id);
    }

    @Override
    public List<ResearchClaim> findByRunId(long runId) {
        return jdbc.query("SELECT * FROM research_claim WHERE run_id=? ORDER BY id", MAPPER, runId);
    }

    @Override
    public List<ResearchClaim> findByTaskId(long taskId) {
        return jdbc.query("SELECT * FROM research_claim WHERE task_id=? ORDER BY id", MAPPER, taskId);
    }

    @Override
    public List<ResearchClaim> findByRunIdOrderByConfidenceDesc(long runId) {
        return jdbc.query("SELECT * FROM research_claim WHERE run_id=? ORDER BY confidence DESC", MAPPER, runId);
    }

    private static LocalDateTime toTs(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
