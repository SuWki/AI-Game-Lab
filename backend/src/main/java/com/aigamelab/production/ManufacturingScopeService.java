package com.aigamelab.production;

import com.aigamelab.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Service
public class ManufacturingScopeService {
    public record Request(long taskId, String specId, int revision, String contentHash) {}
    public record Confirmation(String contentHash, String confirmedAt) {}

    private final JdbcTemplate db;
    private final ProductionArtifactService artifacts;

    public ManufacturingScopeService(JdbcTemplate db, ProductionArtifactService artifacts) {
        this.db = db;
        this.artifacts = artifacts;
    }

    public Confirmation confirm(Request request) {
        var spec = artifacts.get(request.specId(), request.revision());
        if (!"GameSpec".equals(spec.path("kind").asText()) || spec.path("taskId").asLong() != request.taskId()
                || !spec.path("contentHash").asText().equals(request.contentHash())) {
            throw BusinessException.conflict("Manufacturing confirmation must match the exact task and specification");
        }
        var content = spec.path("content");
        if (!content.path("openQuestions").isEmpty()) {
            throw BusinessException.conflict("Resolve open questions in a new specification revision before confirming");
        }
        var settings = content.path("systems").path("survivalWeb");
        if (!"web".equals(content.path("targetPlatform").asText())
                || content.path("title").asText().length() > 120
                || !Set.of("heat-burst", "shield-dash").contains(settings.path("mechanic").asText())) {
            throw BusinessException.conflict("Only supported survival-web specifications can be confirmed");
        }
        range(settings, "duration", 1, 300);
        range(settings, "enemySpeed", 20, 180);
        range(settings, "spawnInterval", 0.3, 3);
        range(settings, "seed", 0, 4294967295L);
        double seed = settings.path("seed").asDouble();
        if (Math.floor(seed) != seed) throw new IllegalArgumentException("Seed must be an integer");
        db.update("""
                INSERT INTO production_scope_confirmation(artifact_id, revision, content_hash, confirmed_at)
                VALUES (?,?,?,?) ON CONFLICT(artifact_id, revision) DO NOTHING
                """, request.specId(), request.revision(), request.contentHash(), Instant.now().toString());
        return find(request.specId(), request.revision(), request.contentHash()).orElseThrow();
    }

    public Optional<Confirmation> find(String id, int revision, String hash) {
        return db.query("""
                SELECT content_hash, confirmed_at FROM production_scope_confirmation
                WHERE artifact_id=? AND revision=? AND content_hash=?
                """, (row, index) -> new Confirmation(row.getString(1), row.getString(2)), id, revision, hash)
                .stream().findFirst();
    }

    private void range(JsonNode settings, String field, double min, double max) {
        var node = settings.path(field);
        double value = node.asDouble(Double.NaN);
        if (!node.isNumber() || !Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException("Unsupported survival-web parameter: " + field);
        }
    }
}
