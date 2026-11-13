package com.aigamelab.qa;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.QaReleaseGate;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tester.FindingService;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * R5 §6：qa_complete_orchestration —— Lead 收尾编排并触发 Release Gate 计算。
 *
 * <p><b>Gate 决定权不在此工具</b>：lead_decision 仅作为证据性意见写入 gate 行
 * （automatic_result 由 {@link QaReleaseGateService} 阈值公式确定性计算；
 * final_result 留待人工审阅，R5 §10 / freeze §13.1）。</p>
 */
@Component
public class QaCompleteOrchestrationTool extends AbstractQaTool {

    private final QaOrchestratorService orchestrator;
    private final FindingService findingService;

    public QaCompleteOrchestrationTool(QaOrchestratorService orchestrator,
            FindingService findingService, RunRepository runRepo, TaskRepository taskRepo) {
        super(runRepo, taskRepo);
        this.orchestrator = orchestrator;
        this.findingService = findingService;
    }

    @Override
    public String name() {
        return "qa_complete_orchestration";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("收尾 QA 编排并触发 Release Gate 自动判定。"
                        + " leadDecision 是 QA Lead 的判定意见（证据性，不直接决定 Gate）；"
                        + " 可含 PASS / CONDITIONAL_PASS / FAIL / INCONCLUSIVE 标记。",
                Map.of(
                        "configVersion", Map.of("type", "string",
                                "description", "被测 ConfigVersion（如 v001）"),
                        "leadDecision", Map.of("type", "string",
                                "description", "QA Lead 判定意见（自由文本）"),
                        "leadDecisionEvidence", Map.of("type", "string",
                                "description", "判定佐证 JSON（可选）")),
                "configVersion", "leadDecision");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        String configVersion = requireText(args, "configVersion");
        String leadDecision = requireText(args, "leadDecision");
        String evidenceJson = textArg(args, "leadDecisionEvidence", "{}");
        LeadContext lc = leadContext(ctx);
        QaReleaseGate gate = orchestrator.completeOrchestration(
                lc.leadTask(), lc.leadRun(), findingService,
                configVersion, leadDecision, evidenceJson);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("gateId", gate.id());
        out.put("status", gate.status());
        out.put("automaticResult", gate.automaticResult());
        out.put("isConsistent", gate.isConsistent());
        out.put("inconclusiveCount", gate.inconclusiveCount());
        return ok(out);
    }
}
