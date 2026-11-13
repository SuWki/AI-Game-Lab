package com.aigamelab.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P1-0 测试替身：可编程 StubTool。
 *
 * 特性：
 * - 实现 GameTool 契约，可直接 register 进 ToolRegistry；
 * - 预定义 输入(args)→输出 映射，键为归一化 JSON；未命中剧本走默认行为；
 * - 记录调用次数、每次参数/上下文/结局与实例内顺序号，供测试断言；
 * - 可模拟成功与失败（FAILED 结果或抛异常）。
 *
 * 复用方式：直接 new（name/schema/requiresApproval 均可编程），配合 @BeforeEach
 * 重建实现用例间隔离；多实例天然互不串台。
 */
public class StubTool implements GameTool {

    /** 一次调用的完整记录：seq 为该实例内递增顺序号 */
    public record Invocation(int seq, JsonNode args, ToolContext ctx, String outcome) {
    }

    private static final ObjectMapper KEY_MAPPER = new ObjectMapper();

    private final String name;
    private final Map<String, Object> schema;
    private final boolean requiresApproval;

    /** 归一化 argsJson → 结果行为 */
    private final Map<String, java.util.function.Function<JsonNode, ToolResult>> scriptedOutputs =
            new LinkedHashMap<>();
    /** 归一化 argsJson → 抛出的异常 */
    private final Map<String, RuntimeException> scriptedFailures = new LinkedHashMap<>();
    /** 未命中剧本时的默认行为 */
    private java.util.function.Function<JsonNode, ToolResult> defaultBehavior =
            args -> ToolResult.success("{\"stub\":true}", "stub ok");

    private final List<Invocation> invocations = new ArrayList<>();

    public StubTool(String name) {
        this(name, Map.of("type", "object"), false);
    }

    public StubTool(String name, Map<String, Object> schema, boolean requiresApproval) {
        this.name = name;
        this.schema = schema;
        this.requiresApproval = requiresApproval;
    }

    // ---------- 编程接口（链式） ----------

    /** 指定参数 JSON → 成功结果 */
    public StubTool onArgs(String argsJson, String payloadJson, String summaryText) {
        scriptedOutputs.put(key(argsJson), args -> ToolResult.success(payloadJson, summaryText));
        return this;
    }

    /** 指定参数 JSON → 失败结果（不抛异常，FAILED 状态） */
    public StubTool onArgsFailure(String argsJson, String error) {
        scriptedOutputs.put(key(argsJson), args -> ToolResult.failure(error));
        return this;
    }

    /** 指定参数 JSON → 抛异常（验证异常传播路径） */
    public StubTool onArgsThrow(String argsJson, RuntimeException e) {
        scriptedFailures.put(key(argsJson), e);
        return this;
    }

    /** 设置默认行为（未命中剧本时） */
    public StubTool defaultSuccess(String payloadJson, String summaryText) {
        this.defaultBehavior = args -> ToolResult.success(payloadJson, summaryText);
        return this;
    }

    // ---------- GameTool 契约 ----------

    @Override
    public String name() {
        return name;
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema;
    }

    @Override
    public boolean requiresApproval() {
        return requiresApproval;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        int seq = invocations.size() + 1;
        String k = key(args.toString());
        invocations.add(new Invocation(seq, args, ctx, "STARTED"));
        RuntimeException toThrow = scriptedFailures.get(k);
        if (toThrow != null) {
            return fail(seq, args, ctx, toThrow);
        }
        var behavior = scriptedOutputs.getOrDefault(k, defaultBehavior);
        ToolResult result = behavior.apply(args);
        replace(seq, new Invocation(seq, args, ctx, result.status()));
        return result;
    }

    // ---------- 断言用观测接口 ----------

    public List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    public int callCount() {
        return invocations.size();
    }

    private ToolResult fail(int seq, JsonNode args, ToolContext ctx, RuntimeException e) {
        replace(seq, new Invocation(seq, args, ctx, "THROWN"));
        throw e;
    }

    private void replace(int seq, Invocation updated) {
        for (int i = 0; i < invocations.size(); i++) {
            if (invocations.get(i).seq() == seq) {
                invocations.set(i, updated);
                return;
            }
        }
    }

    /** 参数 JSON 归一化：消除键序/空白差异，保证映射稳定命中 */
    private static String key(String argsJson) {
        try {
            return KEY_MAPPER.readTree(argsJson).toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 JSON: " + argsJson, e);
        }
    }
}
