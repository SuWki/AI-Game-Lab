package com.aigamelab.production;

import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.Run;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.runtime.RuntimeConversation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DesignerHandoffService {
    public record Draft(long runId, String result) {}
    public record Save(long taskId, JsonNode brief, JsonNode spec) {}

    private final RunRepository runs;
    private final RuntimeConversation conversation;
    private final ProductionArtifactService artifacts;

    public DesignerHandoffService(RunRepository runs, RuntimeConversation conversation, ProductionArtifactService artifacts) {
        this.runs = runs;
        this.conversation = conversation;
        this.artifacts = artifacts;
    }

    public List<Draft> drafts(long taskId) {
        return runs.findByTask(taskId).reversed().stream().filter(this::isDraft).limit(10)
                .map(run -> new Draft(run.id(), run.finalResult())).toList();
    }

    @Transactional
    public JsonNode save(long runId, Save request) {
        Run run = runs.findById(runId).orElseThrow(() -> BusinessException.notFound("Designer Run not found"));
        if (run.taskId() != request.taskId() || !isDraft(run)) {
            throw BusinessException.conflict("Only completed Designer draft Runs of this task can be imported");
        }
        if (!(request.spec() instanceof ObjectNode spec)) {
            throw new IllegalArgumentException("Specification must be an object");
        }
        var brief = artifacts.save(new ProductionArtifactService.Request(run.taskId(), runId, null, 0,
                "ProductionBrief", request.brief(), List.of()));
        var ref = new ProductionArtifactService.Ref(brief.path("artifactId").asText(),
                brief.path("revision").asInt(), brief.path("contentHash").asText());
        return artifacts.save(new ProductionArtifactService.Request(run.taskId(), runId, null, 0,
                "GameSpec", spec.deepCopy().put("briefRef", ref.id()), List.of(ref)));
    }

    private boolean isDraft(Run run) {
        if (!"COMPLETED".equals(run.status()) || run.finalResult() == null || run.finalResult().isBlank()
                || run.finalResult().length() > 262144) return false;
        JsonNode context = conversation.context(run.id());
        return context != null && context.path("productionDraft").asBoolean()
                && "DESIGNER".equals(context.path("agentType").asText());
    }
}
