package com.aigamelab.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.aigamelab.domain.PromptVersion;

@Repository
public class PromptVersionRepositoryImpl implements PromptVersionRepository {

    private static final RowMapper<PromptVersion> MAPPER = (rs, i) -> new PromptVersion(
            rs.getLong("id"),
            rs.getString("version"),
            rs.getString("system_prompt"),
            rs.getString("description"),
            rs.getInt("is_active") == 1,
            toTs(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public PromptVersionRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PromptVersion insert(PromptVersion p) {
        jdbc.update("""
                INSERT INTO prompt_version(version, system_prompt, description, is_active)
                VALUES(?,?,?,0)
                """, p.version(), p.systemPrompt(), p.description());
        return jdbc.query("SELECT * FROM prompt_version WHERE version=?", MAPPER, p.version())
                .stream().findFirst().orElseThrow();
    }

    @Override
    public Optional<PromptVersion> findActive() {
        return jdbc.query("SELECT * FROM prompt_version WHERE is_active=1 LIMIT 1", MAPPER)
                .stream().findFirst();
    }

    @Override
    public Optional<PromptVersion> findById(long id) {
        return jdbc.query("SELECT * FROM prompt_version WHERE id=?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<PromptVersion> findAll() {
        return jdbc.query("SELECT * FROM prompt_version ORDER BY id", MAPPER);
    }

    @Override
    @Transactional
    public void setActive(long id) {
        jdbc.update("UPDATE prompt_version SET is_active=0 WHERE is_active=1");
        jdbc.update("UPDATE prompt_version SET is_active=1 WHERE id=?", id);
    }

    private static LocalDateTime toTs(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
