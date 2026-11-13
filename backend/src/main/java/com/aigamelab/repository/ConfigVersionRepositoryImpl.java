package com.aigamelab.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.aigamelab.domain.ConfigVersion;

@Repository
public class ConfigVersionRepositoryImpl implements ConfigVersionRepository {

    private static final RowMapper<ConfigVersion> MAPPER = (rs, i) -> new ConfigVersion(
            rs.getString("version"),
            rs.getString("config_json"),
            rs.getString("parent_version"),
            getAsLong(rs, "change_proposal_id"),
            rs.getString("comment"),
            toTs(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public ConfigVersionRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ConfigVersion insert(ConfigVersion v) {
        jdbc.update("""
                INSERT INTO game_config_version(version, config_json, parent_version, change_proposal_id, comment)
                VALUES(?,?,?,?,?)
                """, v.version(), v.configJson(), v.parentVersion(), v.changeProposalId(), v.comment());
        return findByVersion(v.version()).orElseThrow();
    }

    @Override
    public Optional<ConfigVersion> findByVersion(String version) {
        return jdbc.query("SELECT * FROM game_config_version WHERE version=?", MAPPER, version)
                .stream().findFirst();
    }

    @Override
    public Optional<ConfigVersion> findLatest() {
        return jdbc.query("SELECT * FROM game_config_version ORDER BY version DESC LIMIT 1", MAPPER)
                .stream().findFirst();
    }

    @Override
    public List<ConfigVersion> findAll() {
        return jdbc.query("SELECT * FROM game_config_version ORDER BY version", MAPPER);
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
