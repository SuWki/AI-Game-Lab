package com.aigamelab.tester;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.DesignDocument;
import com.aigamelab.domain.Finding;
import com.aigamelab.domain.FindingRegression;
import com.aigamelab.domain.FindingRegressionItem;
import com.aigamelab.domain.TestPlan;
import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.repository.DesignDocumentRepository;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * T3-3 Tool 8/8：test_report_create —— 生成 Markdown 测试报告落 design_document
 * 表（title 前缀 [TEST REPORT]，技术设计 §2 复用审查表），发事件属 T4-3。
 *
 * T7-5 增量：新增可选 baselineVersion 参数——当提供时，额外附上版本间 Finding
 * 回归对比摘要（NEW/FIXED/PERSISTED/CHANGED），将回归差异纳入测试报告，
 * 供 Designer 审阅配置变更的缺陷影响。
 *
 * 链路：Tool → FindingService（汇总该 Run/版本 Finding）→ FindingRegressionService（回归 diff）→ DesignDocumentRepository。
 */
@Component
public class TestReportCreateTool extends AbstractTesterTool {

    static final String NAME = "test_report_create";
    static final String TITLE_PREFIX = "[TEST REPORT]";

    private final DesignDocumentRepository docRepo;
    private final FindingService findingService;
    private final TestPlanService planService;
    private final FindingRegressionService regressionService;
    private final AgentEventPublisher publisher;

    public TestReportCreateTool(DesignDocumentRepository docRepo,
            FindingService findingService, TestPlanService planService,
            FindingRegressionService regressionService,
            AgentEventPublisher publisher) {
        this.docRepo = docRepo;
        this.findingService = findingService;
        this.planService = planService;
        this.regressionService = regressionService;
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> jsonSchema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("taskId", Map.of("type", "integer", "description", "任务 id"));
        props.put("configVersion", Map.of("type", "string", "description", "被测版本"));
        props.put("summary", Map.of("type", "string",
                "description", "总体结论与风险评级"));
        props.put("scenarioResults", Map.of("type", "array",
                "items", Map.of("type", "object"),
                "description", "每场景结果 [{scenarioId,passed,note}]"));
        props.put("recommendations", Map.of("type", "string",
                "description", "建议"));
        // T7-5：可选 baselineVersion，提供时附回归对比摘要
        props.put("baselineVersion", Map.of("type", "string",
                "description", "回归对比基线版本（可选；提供时报告附版本间 Finding 差异）"));
        return schema("所有场景完成后生成测试报告（每场景结果/Finding 清单/风险评级/建议）。"
                + "若有 baselineVersion，额外附回归对比摘要。调用后应结束本轮测试。", props,
                "taskId", "configVersion", "summary");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        long taskId = requireLong(args, "taskId");
        String configVersion = textArg(args, "configVersion", null);
        if (configVersion == null || configVersion.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: configVersion");
        }
        String summary = textArg(args, "summary", "");
        String baselineVersion = textArg(args, "baselineVersion", null);

        // 汇总该版本 Finding 清单（回归对比的数据基础）
        List<Finding> findings = findingService.findByFilter(configVersion, null,
                ctx.runId(), null);
        long blockers = findings.stream().filter(f -> "BLOCKER".equals(f.severity())).count();
        long highs = findings.stream().filter(f -> "HIGH".equals(f.severity())).count();

        StringBuilder md = new StringBuilder();
        md.append("# 测试报告 — ").append(configVersion).append("\n\n");
        md.append("- 任务: #").append(taskId).append('\n');
        if (ctx.runId() != null) {
            md.append("- 关联 Run: #").append(ctx.runId()).append('\n');
        }
        md.append("- Finding 统计: BLOCKER=").append(blockers)
                .append(", HIGH=").append(highs)
                .append(", 总计=").append(findings.size()).append("\n\n");

        md.append("## 总体结论\n\n").append(summary).append("\n\n");

        md.append("## 场景结果\n\n");
        JsonNode results = args.get("scenarioResults");
        if (results != null && results.isArray() && !results.isEmpty()) {
            for (JsonNode r : results) {
                md.append("- `").append(r.path("scenarioId").asText("?"))
                        .append("` → ")
                        .append(r.path("passed").asBoolean(false) ? "PASS" : "FAIL")
                        .append(r.hasNonNull("note") ? "：" + r.get("note").asText() : "")
                        .append('\n');
            }
        } else {
            md.append("（无场景明细）\n");
        }

        md.append("\n## Finding 清单\n\n");
        if (findings.isEmpty()) {
            md.append("无——健康基线（0 Finding）。\n");
        } else {
            for (Finding f : findings) {
                md.append("- **#").append(f.id()).append("** [").append(f.severity())
                        .append('/').append(f.category()).append("/src=")
                        .append(f.source()).append("] ").append(f.title())
                        .append(" (status=").append(f.status()).append(")\n");
            }
        }

        // T7-5：若有 baselineVersion，附回归对比摘要
        FindingRegression regression = null;
        if (baselineVersion != null && !baselineVersion.isBlank()) {
            regression = regressionService.diff(baselineVersion, configVersion);
            md.append("\n## 回归对比（").append(baselineVersion).append(" → ")
                    .append(configVersion).append("）\n\n");
            md.append("- NEW（新增）: **").append(regression.newCount()).append("**\n");
            md.append("- FIXED（修复）: **").append(regression.fixedCount()).append("**\n");
            md.append("- PERSISTED（持续）: **").append(regression.persistedCount()).append("**\n");
            md.append("- CHANGED（严重程度变化）: **").append(regression.changedCount()).append("**\n\n");
            if (!regression.items().isEmpty()) {
                for (FindingRegressionItem it : regression.items()) {
                    md.append("- `").append(it.fingerprint().scenarioId()).append("` ")
                            .append(it.fingerprint().category()).append(" ")
                            .append(it.title())
                            .append(" → ").append(it.status());
                    if (it.oldSeverity() != null) {
                        md.append(" (").append(it.oldSeverity());
                        if (it.newSeverity() != null) {
                            md.append("→").append(it.newSeverity());
                        }
                        md.append(")");
                    } else if (it.newSeverity() != null) {
                        md.append(" (").append(it.newSeverity()).append(")");
                    }
                    md.append('\n');
                }
            } else {
                md.append("（无差异）\n");
            }
        }

        String recommendations = textArg(args, "recommendations", null);
        if (recommendations != null && !recommendations.isBlank()) {
            md.append("\n## 建议\n\n").append(recommendations).append("\n");
        }

        DesignDocument saved = docRepo.insert(new DesignDocument(null,
                ctx.runId(), taskId, TITLE_PREFIX + " " + configVersion + " "
                        + riskTag(blockers),
                md.toString(), null));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("documentId", saved.id());
        payload.put("title", saved.title());
        payload.put("findingCount", findings.size());
        payload.put("baselineVersion", baselineVersion);
        if (regression != null) {
            payload.put("regressionNew", regression.newCount());
            payload.put("regressionFixed", regression.fixedCount());
            payload.put("regressionPersisted", regression.persistedCount());
            payload.put("regressionChanged", regression.changedCount());
        }
        // T4-3：报告归档后发 TEST_REPORT_CREATED 事件（中台实时可见）
        if (ctx.runId() != null) {
            ObjectNode ev = MAPPER.createObjectNode();
            ev.put("documentId", saved.id());
            ev.put("title", saved.title());
            ev.put("configVersion", configVersion);
            ev.put("findingCount", findings.size());
            ev.put("blockers", blockers);
            if (baselineVersion != null) {
                ev.put("baselineVersion", baselineVersion);
                if (regression != null) {
                    ev.put("regressionNew", regression.newCount());
                    ev.put("regressionFixed", regression.fixedCount());
                }
            }
            publisher.publish("TEST_REPORT_CREATED", ctx.runId(), ev.toString());
        }
        return ToolResult.success(payload.toString(),
                "测试报告已归档 documentId=" + saved.id()
                        + " findings=" + findings.size()
                        + (regression != null ? " regression=new:" + regression.newCount()
                                + " fixed:" + regression.fixedCount() : ""));
    }

    private static String riskTag(long blockers) {
        return blockers > 0 ? "RISK-BLOCKER" : "";
    }

    // 保留 planService 供 T4 报告关联 Plan 详情扩展（构造注入避免循环依赖）
    @SuppressWarnings("unused")
    private int scenarioCountOf(TestPlan p) {
        return planService.parseScenarios(p.scenariosJson()).size();
    }
}
