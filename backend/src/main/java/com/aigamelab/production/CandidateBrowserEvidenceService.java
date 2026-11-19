package com.aigamelab.production;

import com.aigamelab.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class CandidateBrowserEvidenceService {
    static final String PROFILE = "survival-web-restart-frame-v1";
    private static final Set<String> FIELDS = Set.of("health", "heat", "energy", "score", "time");
    private static final Pattern HUD = Pattern.compile(
            "^生命 ([0-9]{1,6}) · (热量|能量) ([0-9]{1,6}) · 得分 ([0-9]{1,9}) · 时间 ([0-9]{1,3}\\.[0-9]) / [0-9]{1,3}(?:\\.[0-9]+)? 秒$");
    private static final Set<String> SETTLEMENTS = Set.of("胜利！存活目标达成", "失败，点击重新开始");

    public record Observation(String executionId, String manifestHash, String archiveSha256,
                              String browser, String settledStatus, String restartStatus, String hud,
                              Instant observedAt) {}

    private final ProductionCandidateService candidates;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public CandidateBrowserEvidenceService(ProductionCandidateService candidates, JdbcTemplate db, ObjectMapper mapper) {
        this.candidates = candidates;
        this.db = db;
        this.mapper = mapper;
    }

    static boolean supported(JsonNode criterion) {
        JsonNode probe = criterion.path("browserProbe");
        JsonNode expected = probe.path("expected");
        return "browser".equals(criterion.path("method").asText())
                && PROFILE.equals(probe.path("profile").asText()) && FIELDS.contains(probe.path("field").asText())
                && expected.isNumber() && Double.isFinite(expected.asDouble())
                && expected.asDouble() >= 0 && expected.asDouble() <= 999_999_999;
    }

    public ObjectNode plan(String id) {
        var candidate = candidates.get(id);
        try {
            var files = candidates.verifiedFiles(id);
            var spec = mapper.readTree(files.get("source/specification.json"));
            if (!spec.path("contentHash").equals(candidate.path("manifest").path("specHash"))) {
                throw BusinessException.conflict("Packaged specification differs from candidate");
            }
            var config = mapper.readTree(files.get("build/config.json"));
            var plan = mapper.createObjectNode().put("candidateId", id).put("profile", PROFILE)
                    .put("manifestHash", candidate.path("manifestHash").asText())
                    .put("archiveSha256", candidate.path("archiveSha256").asText())
                    .put("specId", spec.path("artifactId").asText()).put("specRevision", spec.path("revision").asInt())
                    .put("specHash", spec.path("contentHash").asText())
                    .put("duration", config.path("duration").asDouble())
                    .put("mechanic", config.path("mechanic").asText());
            plan.set("checks", checks(spec.path("content"), null, plan.path("mechanic").asText()));
            return plan;
        } catch (IOException ex) {
            throw BusinessException.conflict("Cannot read fixed browser evidence inputs");
        }
    }

    public ObjectNode record(String id, Observation observation) {
        ObjectNode plan = plan(id);
        if (!plan.path("manifestHash").asText().equals(observation.manifestHash())
                || !plan.path("archiveSha256").asText().equals(observation.archiveSha256())) {
            throw BusinessException.conflict("Browser observation belongs to a different fixed package");
        }
        if (observation.executionId() == null || !observation.executionId().matches("[a-f0-9-]{36}")
                || observation.browser() == null || observation.browser().isBlank() || observation.browser().length() > 120
                || observation.settledStatus() == null || !SETTLEMENTS.contains(observation.settledStatus())
                || !"进行中".equals(observation.restartStatus()) || observation.observedAt() == null
                || observation.hud() == null || observation.hud().length() > 180
                || plan.path("duration").asDouble() > 30) {
            throw new IllegalArgumentException("A bounded, completed restart observation is required");
        }
        var match = HUD.matcher(observation.hud());
        if (!match.matches()) throw new IllegalArgumentException("Unrecognized first frame HUD");
        String resource = "热量".equals(match.group(2)) ? "heat" : "energy";
        if (!resource.equals(resourceField(plan.path("mechanic").asText()))) {
            throw BusinessException.conflict("Observed resource differs from the fixed build mechanic");
        }
        var readings = mapper.createObjectNode();
        readings.put("health", new BigDecimal(match.group(1))).put(resource, new BigDecimal(match.group(3)))
                .put("score", new BigDecimal(match.group(4))).put("time", new BigDecimal(match.group(5)));
        boolean tested = false;
        for (JsonNode item : plan.path("checks")) {
            if (!item.has("field")) continue;
            ObjectNode row = (ObjectNode) item;
            JsonNode actual = readings.path(row.path("field").asText());
            row.set("actual", actual);
            row.put("outcome", actual.decimalValue().compareTo(row.path("expected").decimalValue()) == 0 ? "PASS" : "FAIL");
            tested = true;
        }
        if (!tested) throw BusinessException.conflict("No compatible browser probe is mapped to this specification");
        var input = mapper.valueToTree(observation);
        String inputHash = ProductionCandidateService.hash(input.toString().getBytes(StandardCharsets.UTF_8));
        plan.put("executionId", observation.executionId()).put("recordedAt", Instant.now().toString())
                .put("evidenceKind", "LOCAL_BROWSER_OBSERVATION").put("inputHash", inputHash);
        plan.putArray("actions").add("start").add("wait-for-settlement").add("restart").add("observe-first-hud-frame");
        plan.set("observation", input);
        db.update("""
                INSERT INTO candidate_browser_evidence(candidate_id, execution_id, record_json)
                VALUES (?,?,?) ON CONFLICT(candidate_id, execution_id) DO NOTHING
                """, id, observation.executionId(), plan.toString());
        ObjectNode saved = db.queryForObject("""
                SELECT record_json FROM candidate_browser_evidence WHERE candidate_id=? AND execution_id=?
                """, (rs, row) -> parse(rs.getString(1)), id, observation.executionId());
        if (!saved.path("inputHash").asText().equals(inputHash)) {
            throw BusinessException.conflict("Browser execution ID already recorded with another observation");
        }
        return saved;
    }

    public Optional<ObjectNode> latest(String id) {
        return db.query("""
                SELECT record_json FROM candidate_browser_evidence WHERE candidate_id=? ORDER BY rowid DESC LIMIT 1
                """, (rs, row) -> parse(rs.getString(1)), id).stream().findFirst();
    }

    public ObjectNode execution(String id, String executionId) {
        return db.query("""
                SELECT record_json FROM candidate_browser_evidence WHERE candidate_id=? AND execution_id=?
                """, (rs, row) -> parse(rs.getString(1)), id, executionId).stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("Browser evidence execution not found"));
    }

    public boolean hasFailure(JsonNode evidence) {
        for (JsonNode row : evidence.path("checks")) {
            if ("FAIL".equals(row.path("outcome").asText())) return true;
        }
        return false;
    }

    JsonNode checks(JsonNode spec, JsonNode evidence, String mechanic) {
        if (evidence != null) return evidence.path("checks");
        var checks = mapper.createArrayNode();
        for (JsonNode criterion : spec.path("acceptanceCriteria")) {
            var row = checks.addObject().put("criterionId", criterion.path("id").asText()).put("outcome", "NOT_TESTED");
            if (!supported(criterion)) {
                row.put("reason", "No mapped browser probe");
                continue;
            }
            var probe = criterion.path("browserProbe");
            String field = probe.path("field").asText();
            if (Set.of("heat", "energy").contains(field) && !field.equals(resourceField(mechanic))) {
                row.put("reason", "Probe does not match the fixed build mechanic");
                continue;
            }
            row.put("field", field);
            row.set("expected", probe.path("expected"));
        }
        return checks;
    }

    private String resourceField(String mechanic) {
        return switch (mechanic) {
            case "heat-burst" -> "heat";
            case "shield-dash" -> "energy";
            default -> "";
        };
    }

    private ObjectNode parse(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read browser evidence", ex);
        }
    }
}
