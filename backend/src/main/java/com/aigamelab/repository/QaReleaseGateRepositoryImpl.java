package com.aigamelab.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.aigamelab.domain.QaReleaseGate;

@Repository
public class QaReleaseGateRepositoryImpl implements QaReleaseGateRepository {

    private static final RowMapper<QaReleaseGate> MAPPER = (rs, i) -> {
        try {
            return new QaReleaseGate(
                    rs.getLong("id"),
                    rs.getLong("lead_task_id"),
                    rs.getLong("run_id"),
                    rs.getString("config_version"),
                    rs.getString("status"),
                    rs.getString("automatic_result"),
                    rs.getString("rule_version"),
                    rs.getString("lead_decision"),
                    rs.getString("lead_decision_evidence_json"),
                    rs.getString("final_result"),
                    rs.getInt("is_consistent"),
                    rs.getInt("inconclusive_count"),
                    rs.getInt("manual_override"),
                    rs.getString("override_reason"),
                    rs.getString("override_by"),
                    rs.getString("override_at"),
                    rs.getInt("critical_bugs"),
                    rs.getInt("high_bugs"),
                    rs.getInt("medium_bugs"),
                    rs.getInt("soft_lock_count"),
                    (Double) rs.getObject("crash_rate"),
                    (Double) rs.getObject("dominant_build_share"),
                    rs.getString("stats_json"),
                    getAsLong(rs, "report_doc_id"),
                    toTs(rs.getTimestamp("created_at")),
                    toTs(rs.getTimestamp("decided_at")));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    };

    private final JdbcTemplate jdbc;

    public QaReleaseGateRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public QaReleaseGate insert(QaReleaseGate g) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO qa_release_gate
                        (lead_task_id, run_id, config_version, status, rule_version, stats_json)
                    VALUES(?,?,?,?,?,?)
                    """, new String[] {"id"});
            ps.setLong(1, g.leadTaskId());
            ps.setLong(2, g.runId());
            ps.setString(3, g.configVersion());
            ps.setString(4, g.status() != null ? g.status() : "PENDING");
            ps.setString(5, g.ruleVersion());
            ps.setString(6, g.statsJson() != null ? g.statsJson() : "{}");
            return ps;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public Optional<QaReleaseGate> findById(long id) {
        return jdbc.query("SELECT * FROM qa_release_gate WHERE id=?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<QaReleaseGate> findByLeadTaskAndConfig(long leadTaskId, String configVersion) {
        return jdbc.query("SELECT * FROM qa_release_gate WHERE lead_task_id=? AND config_version=?",
                MAPPER, leadTaskId, configVersion).stream().findFirst();
    }

    @Override
    public List<QaReleaseGate> listBy(Long leadTaskId, String configVersion, String status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM qa_release_gate WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (leadTaskId != null) {
            sql.append(" AND lead_task_id=?");
            args.add(leadTaskId);
        }
        if (configVersion != null && !configVersion.isBlank()) {
            sql.append(" AND config_version=?");
            args.add(configVersion);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        sql.append(" ORDER BY id DESC");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public void decide(long id, String status, String automaticResult, String ruleVersion,
                       String leadDecision, String leadDecisionEvidenceJson,
                       int isConsistent, int inconclusiveCount,
                       int criticalBugs, int highBugs, int mediumBugs, int softLockCount,
                       Double crashRate, Double dominantBuildShare, String statsJson) {
        jdbc.update("""
                UPDATE qa_release_gate
                SET status=?, automatic_result=?, rule_version=?, lead_decision=?,
                    lead_decision_evidence_json=?, is_consistent=?, inconclusive_count=?,
                    critical_bugs=?, high_bugs=?, medium_bugs=?, soft_lock_count=?,
                    crash_rate=?, dominant_build_share=?, stats_json=?,
                    decided_at=COALESCE(decided_at, datetime('now','localtime'))
                WHERE id=?
                """, status, automaticResult, ruleVersion, leadDecision, leadDecisionEvidenceJson,
                isConsistent, inconclusiveCount, criticalBugs, highBugs, mediumBugs, softLockCount,
                crashRate, dominantBuildShare, statsJson, id);
    }

    @Override
    public void overrideDecision(long id, String finalResult, String reason, String by) {
        jdbc.update("""
                UPDATE qa_release_gate
                SET status=?, final_result=?, manual_override=1, override_reason=?,
                    override_by=?, override_at=datetime('now','localtime'),
                    decided_at=COALESCE(decided_at, datetime('now','localtime'))
                WHERE id=?
                """, finalResult, finalResult, reason, by, id);
    }

    @Override
    public void updateReportDoc(long id, Long reportDocId) {
        jdbc.update("UPDATE qa_release_gate SET report_doc_id=? WHERE id=?", reportDocId, id);
    }

    private static Long getAsLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static java.time.LocalDateTime toTs(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}