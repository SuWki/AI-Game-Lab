package com.aigamelab.tester;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.TestPlan;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * T3-2 Tool 1/8：test_plan_get —— 读取 task 关联的 TestPlan（含场景/invariants/seed）。
 * 链路：Tool → TestPlanService → TestPlanRepository。
 */
@Component
public class TestPlanGetTool extends AbstractTesterTool {

    static final String NAME = "test_plan_get";

    private final TestPlanService planService;

    public TestPlanGetTool(TestPlanService planService) {
        this.planService = planService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("读取指定任务的测试计划（场景列表/不变量/seed/状态）。"
                        + "status=ACTIVE 的计划才允许执行测试。",
                Map.of("taskId", Map.of("type", "integer", "description", "任务 id")),
                "taskId");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        long taskId = requireLong(args, "taskId");
        List<TestPlan> plans = planService.findActiveByTask(taskId);
        if (plans.isEmpty()) {
            return ToolResult.success(
                    "{\"activePlans\":[]}",
                    "该任务暂无 ACTIVE 测试计划");
        }
        var payload = MAPPER.createArrayNode();
        for (TestPlan p : plans) {
            var node = payload.addObject();
            node.put("id", p.id());
            node.put("taskId", p.taskId());
            node.put("configVersion", p.configVersion());
            node.put("status", p.status());
            // scenarios 以结构化数组内联（scenariosJson 原文对 LLM 冗长且含转义）
            try {
                node.set("scenarios", MAPPER.readTree(p.scenariosJson()));
            } catch (Exception e) {
                node.putArray("scenarios"); // activate 时已强制校验，此处仅防御
            }
        }
        var root = MAPPER.createObjectNode();
        root.set("activePlans", payload);
        int scenarioCount = plans.stream().mapToInt(p -> safeScenarioCount(p)).sum();
        return ToolResult.success(root.toString(),
                "找到 " + plans.size() + " 个 ACTIVE 计划，共 " + scenarioCount + " 个场景");
    }

    private int safeScenarioCount(TestPlan p) {
        try {
            return planService.parseScenarios(p.scenariosJson()).size();
        } catch (Exception e) {
            return 0; // 结构损坏的计划不阻塞列举，activate 时已强制校验
        }
    }
}
