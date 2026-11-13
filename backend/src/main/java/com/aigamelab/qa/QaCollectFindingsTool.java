package com.aigamelab.qa;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.Finding;
import com.aigamelab.domain.Task;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.tester.FindingService;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * R5 §6：qa_collect_findings —— Lead 聚合跨角色 Finding（纯只读）。
 *
 * <p>复用现有 Finding 域（FindingService.findByQaFilter），不创建第二套 Finding
 * system（R5 §12）。只汇总有产出的角色（部分结果语义，freeze §10）；
 * 无产出的失败角色不抛错，由 Lead 在报告中注明。</p>
 */
@Component
public class QaCollectFindingsTool extends AbstractQaTool {

    private final QaOrchestratorService orchestrator;
    private final FindingService findingService;

    public QaCollectFindingsTool(QaOrchestratorService orchestrator,
            FindingService findingService, RunRepository runRepo, TaskRepository taskRepo) {
        super(runRepo, taskRepo);
        this.orchestrator = orchestrator;
        this.findingService = findingService;
    }

    @Override
    public String name() {
        return "qa_collect_findings";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("汇总当前编排下各角色已产出的 Finding（只读聚合，供 Gate 判定与报告）。"
                        + " 无产出角色不抛错；返回按角色分组的 Finding 摘要。",
                Map.of());
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        LeadContext lc = leadContext(ctx);
        List<Finding> findings = orchestrator.collectFindings(findingService, lc.leadTask());
        ObjectNode out = MAPPER.createObjectNode();
        out.put("total", findings.size());
        ArrayNode items = out.putArray("findings");
        for (Finding f : findings) {
            ObjectNode n = items.addObject();
            n.put("id", f.id());
            n.put("severity", f.severity());
            n.put("category", f.category());
            n.put("status", f.status());
            n.put("title", f.title());
            n.put("runId", f.runId() == null ? -1 : f.runId());
            n.put("configVersion", f.configVersion());
        }
        return ok(out);
    }
}
