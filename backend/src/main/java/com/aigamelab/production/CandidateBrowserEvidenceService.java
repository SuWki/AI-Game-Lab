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
    static final String INPUT_PROFILE = "survival-web-space-resource-v1";
    private static final Set<String> RESOURCES = Set.of("heat", "energy");
    private static final int WAIT_MS = 2500;
    private static final Set<String> FIELDS = Set.of("health", "heat", "energy", "score", "time");
    private static final Pattern HUD = Pattern.compile(
            "^生命 ([0-9]{1,6}) · (热量|能量) ([0-9]{1,6}) · 得分 ([0-9]{1,9}) · 时间 ([0-9]{1,3}\\.[0-9]) / [0-9]{1,3}(?:\\.[0-9]+)? 秒$");
    private static final Set<String> SETTLEMENTS = Set.of("胜利！存活目标达成", "失败，点击重新开始");

    public record Observation(String executionId, String manifestHash, String archiveSha256,
                              String browser, String settledStatus, String restartStatus, String hud,
                              Instant observedAt, Interaction interaction) {
        public Observation(String executionId, String manifestHash, String archiveSha256,
                           String browser, String settledStatus, String restartStatus, String hud, Instant observedAt) {
            this(executionId, manifestHash, archiveSha256, browser, settledStatus, restartStatus, hud, observedAt, null);
        }
    }

    public record Interaction(String beforeHud, String afterHud, boolean pressed, int elapsedMs, String screenshotPng) {}

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
        if (!"browser".equals(criterion.path("method").asText()) || !expected.isNumber()
                || !Double.isFinite(expected.asDouble())) return false;
        String field = probe.path("field").asText();
        return switch (probe.path("profile").asText()) {
            case PROFILE -> FIELDS.contains(field) && expected.asDouble() >= 0 && expected.asDouble() <= 999_999_999;
            case INPUT_PROFILE -> RESOURCES.contains(field) && expected.isIntegralNumber()
                    && expected.asDouble() >= 1 && expected.asDouble() <= 100;
            default -> false;
        };
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
                    .put("mechanic", config.path("mechanic").asText()).put("inputWaitMs", WAIT_MS);
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
                || observation.observedAt() == null || plan.path("duration").asDouble() < 1
                || plan.path("duration").asDouble() > 30) {
            throw new IllegalArgumentException("A bounded browser observation is required");
        }
        String resource = resourceField(plan.path("mechanic").asText());
        boolean restart = mapped(plan, PROFILE);
        boolean interaction = mapped(plan, INPUT_PROFILE);
        if (!restart && !interaction) throw BusinessException.conflict("No compatible browser probe is mapped to this specification");
        var actions = plan.putArray("actions");
        if (restart) {
            if (observation.settledStatus() == null || !SETTLEMENTS.contains(observation.settledStatus())
                    || !"进行中".equals(observation.restartStatus())) {
                throw new IllegalArgumentException("A completed restart observation is required");
            }
            var readings = readHud(observation.hud(), resource);
            for (var item : plan.path("checks")) {
                if (PROFILE.equals(item.path("profile").asText())) {
                    var actual = readings.path(item.path("field").asText());
                    evaluate((ObjectNode) item, actual, actual.decimalValue().compareTo(item.path("expected").decimalValue()) == 0);
                }
            }
            actions.add("start").add("wait-for-settlement").add("restart").add("observe-first-hud-frame");
        }
        if (interaction) {
            var trace = observation.interaction();
            var delta = inputDelta(trace, resource, plan.path("duration").asDouble());
            actions.add("start-input-round").add("wait-for-skill-ready");
            if (trace.pressed()) actions.add("press-space").add("wait-for-resource-change");
            actions.add("observe-hud");
            for (var item : plan.path("checks")) {
                if (!INPUT_PROFILE.equals(item.path("profile").asText())) continue;
                var row = (ObjectNode) item;
                boolean passed = delta != null && delta.compareTo(row.path("expected").decimalValue()) >= 0;
                evaluate(row, delta == null ? null : mapper.getNodeFactory().numberNode(delta), passed);
                if (!passed) row.put("reason", trace.pressed() ? "RESOURCE_CHANGE_TIMEOUT" : "SKILL_READY_TIMEOUT");
            }
        } else if (observation.interaction() != null) {
            throw new IllegalArgumentException("No input probe is mapped to this specification");
        }
        ObjectNode input = mapper.valueToTree(observation);
        if (observation.interaction() == null) input.remove("interaction");
        String inputHash = ProductionCandidateService.hash(input.toString().getBytes(StandardCharsets.UTF_8));
        plan.put("executionId", observation.executionId()).put("recordedAt", Instant.now().toString())
                .put("evidenceKind", "LOCAL_BROWSER_OBSERVATION").put("inputHash", inputHash);
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

    private static boolean mapped(JsonNode plan, String profile) {
        for (var row : plan.path("checks")) if (profile.equals(row.path("profile").asText())) return true;
        return false;
    }

    private static void evaluate(ObjectNode row, JsonNode actual, boolean passed) {
        row.set("actual", actual);
        row.put("outcome", passed ? "PASS" : "FAIL");
    }

    private BigDecimal inputDelta(Interaction trace, String resource, double duration) {
        if (trace == null || trace.elapsedMs() < 0 || trace.elapsedMs() > Math.ceil(duration * 1000) + WAIT_MS + 1000
                || trace.screenshotPng() == null || trace.screenshotPng().length() > 100_000
                || !trace.screenshotPng().startsWith("iVBORw0KGgo")
                || !trace.screenshotPng().matches("[A-Za-z0-9+/]+={0,2}")) {
            throw new IllegalArgumentException("A bounded input trace with a PNG HUD screenshot is required");
        }
        var before = readHud(trace.beforeHud(), resource);
        var after = readHud(trace.afterHud(), resource);
        BigDecimal start = before.path(resource).decimalValue();
        BigDecimal end = after.path(resource).decimalValue();
        if (after.path("time").decimalValue().compareTo(before.path("time").decimalValue()) < 0
                || trace.pressed() && ("heat".equals(resource) ? start.intValue() > 35 : start.intValue() < 65)) {
            throw new IllegalArgumentException("Input HUD sequence or initial resource is invalid");
        }
        if (!trace.pressed()) return null;
        return "heat".equals(resource) ? end.subtract(start) : start.subtract(end);
    }

    private ObjectNode readHud(String hud, String resource) {
        var match = HUD.matcher(hud == null || hud.length() > 180 ? "" : hud);
        if (!match.matches()) throw new IllegalArgumentException("Unrecognized browser HUD");
        if (!resource.equals("热量".equals(match.group(2)) ? "heat" : "energy")) {
            throw BusinessException.conflict("Observed resource differs from the fixed build mechanic");
        }
        return mapper.createObjectNode().put("health", new BigDecimal(match.group(1)))
                .put(resource, new BigDecimal(match.group(3))).put("score", new BigDecimal(match.group(4)))
                .put("time", new BigDecimal(match.group(5)));
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
            row.put("field", field).put("profile", probe.path("profile").asText());
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
