package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.aigamelab.domain.Task;

@Repository
public class TaskRepositoryImpl implements TaskRepository {

    private static final RowMapper<Task> MAPPER = (rs, i) -> new Task(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("goal"),
            rs.getString("constraints_json"),
            rs.getInt("max_steps"),
            rs.getInt("approval_required") == 1,
            rs.getString("status"),
            toTs(rs.getString("created_at")));

    private final JdbcTemplate jdbc;

    public TaskRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Task insert(Task t) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO task(title, goal, constraints_json, max_steps, approval_required, status) VALUES(?,?,?,?,?,?)",
                    new String[] {"id"});
            ps.setString(1, t.title());
            ps.setString(2, t.goal());
            ps.setString(3, t.constraintsJson() != null ? t.constraintsJson() : "{}");
            ps.setInt(4, t.maxSteps());
            ps.setInt(5, t.approvalRequired() ? 1 : 0);
            ps.setString(6, t.status() != null ? t.status() : "ACTIVE");
            return ps;
        }, keys);
        Long id = keys.getKey() == null ? null : keys.getKey().longValue();
        // 回读以获得 DB 默认 createdAt，保证返回对象与库中行一致
        return findById(id).orElseThrow();
    }

    @Override
    public Optional<Task> findById(long id) {
        return jdbc.query("SELECT * FROM task WHERE id=?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<Task> findAll() {
        return jdbc.query("SELECT * FROM task ORDER BY id", MAPPER);
    }

    @Override
    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE task SET status=? WHERE id=?", status, id);
    }

    private static java.time.LocalDateTime toTs(String s) {
        return s == null ? null : java.time.LocalDateTime.parse(s.replace(' ', 'T'));
    }
}
