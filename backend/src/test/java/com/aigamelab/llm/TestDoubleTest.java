package com.aigamelab.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aigamelab.llm.dto.LlmMessage;
import com.aigamelab.llm.dto.LlmRequest;
import com.aigamelab.llm.dto.LlmResponse;
import com.aigamelab.llm.dto.LlmToolSpec;
import com.aigamelab.common.BusinessException;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.tool.StubTool;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolException;
import com.aigamelab.tool.ToolRegistry;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P1-0 验收测试：测试替身专项验证。
 * 被测对象本身是替身，但验证方式与真实组件一致：
 * 覆盖：剧本顺序返回 / 请求记录 / StubTool 调用记录 / 成功与失败 /
 * 多轮剧本不串台 / 用例间状态隔离。纯 JUnit，不启 Spring 容器。
 */
@DisplayName("P1-0 测试替身集")
class TestDoubleTest {

    private FakeLlmGateway fakeLlm;
    private StubTool stubA;
    private StubTool stubB;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        // 每个用例全新实例：从根上保证用例间隔离
        fakeLlm = new FakeLlmGateway();
        stubA = new StubTool("tool_a");
        stubB = new StubTool("tool_b");
        registry = new ToolRegistry(List.of());
        registry.register(stubA);
    }

    private static LlmRequest req(String requestId) {
        return new LlmRequest(requestId, 1L, "deepseek-chat",
                List.of(LlmMessage.user("hi")),
                List.of(new LlmToolSpec("tool_a", "d", Map.of("type", "object"), false)),
                0.4, 1000, "p001");
    }

    // ==================== FakeLlmGateway ====================

    @Test
    void returnsScriptedResponsesInOrder() {
        fakeLlm.thenStop("第一步完成")
                .thenToolCalls(new ToolCall("c1", "tool_a", "{\"x\":1}"))
                .thenLength("被截断");

        LlmResponse r1 = fakeLlm.complete(req("rq-1"));
        LlmResponse r2 = fakeLlm.complete(req("rq-2"));
        LlmResponse r3 = fakeLlm.complete(req("rq-3"));

        assertThat(r1.finishReason()).isEqualTo("stop");
        assertThat(r1.content()).isEqualTo("第一步完成");
        assertThat(r2.finishReason()).isEqualTo("tool_calls");
        assertThat(r2.toolCalls()).hasSize(1);
        assertThat(r2.toolCalls().get(0).name()).isEqualTo("tool_a");
        assertThat(r2.toolCalls().get(0).argumentsJson()).isEqualTo("{\"x\":1}");
        assertThat(r3.finishReason()).isEqualTo("length");
        assertThat(r3.content()).isEqualTo("被截断");
        assertThat(fakeLlm.callCount()).isEqualTo(3);
    }

    @Test
    void recordsEveryRequestWithFieldsIntact() {
        fakeLlm.thenStop("done");
        LlmRequest sent = req("rq-rec");
        fakeLlm.complete(sent);

        assertThat(fakeLlm.recordedRequests()).hasSize(1);
        LlmRequest recorded = fakeLlm.recordedRequests().get(0);
        assertThat(recorded.requestId()).isEqualTo("rq-rec");
        assertThat(recorded.runId()).isEqualTo(1L);
        assertThat(recorded.model()).isEqualTo("deepseek-chat");
        assertThat(recorded.messages()).hasSize(1);
        assertThat(recorded.messages().get(0).role()).isEqualTo("user");
        assertThat(recorded.messages().get(0).content()).isEqualTo("hi");
        assertThat(recorded.tools()).hasSize(1);
        assertThat(recorded.tools().get(0).name()).isEqualTo("tool_a");
        assertThat(recorded.temperature()).isEqualTo(0.4);
        assertThat(recorded.maxTokens()).isEqualTo(1000);
        assertThat(recorded.promptVersion()).isEqualTo("p001");
        assertThat(fakeLlm.producedResponses().get(0).requestId()).isEqualTo("rq-rec");
    }

    @Test
    void failureScriptThrowsAndStillRecordsRequest() {
        RuntimeException boom = new IllegalStateException("LLM 挂了");
        fakeLlm.thenFailure(boom).thenStop("恢复后成功");

        assertThatThrownBy(() -> fakeLlm.complete(req("rq-f")))
                .isSameAs(boom);
        // 失败剧本不产生响应记录，但请求已记录、游标已推进
        LlmResponse after = fakeLlm.complete(req("rq-next"));
        assertThat(after.finishReason()).isEqualTo("stop");
        assertThat(fakeLlm.callCount()).isEqualTo(2);
        assertThat(fakeLlm.producedResponses()).hasSize(1);
    }

    @Test
    void exhaustedScriptFallsBackOrThrows() {
        fakeLlm.thenStop("唯一一步");
        assertThat(fakeLlm.complete(req("a")).content()).isEqualTo("唯一一步");

        // 默认兜底 stop
        LlmResponse fb = fakeLlm.complete(req("b"));
        assertThat(fb.finishReason()).isEqualTo("stop");
        assertThat(fb.content()).isEqualTo("script exhausted");

        // 可配置为抛异常（供 P1-3 预算耗尽等场景复用）
        fakeLlm.onExhaustedThrow(new IllegalStateException("预算外调用"));
        assertThatThrownBy(() -> fakeLlm.complete(req("c")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("预算外调用");
    }

    // ==================== StubTool ====================

    @Test
    void toolSuccessReturnsScriptedResult() {
        stubA.onArgs("{\"x\":1}", "{\"result\":42}", "答案是 42");

        ToolResult r = registry.dispatch("tool_a",
                parse("{\"x\":1}"), ToolContext.of(7L, 8L));

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.payloadJson()).isEqualTo("{\"result\":42}");
        assertThat(r.summaryText()).isEqualTo("答案是 42");
        assertThat(stubA.callCount()).isEqualTo(1);
    }

    @Test
    void toolFailureResultIsCapturable() {
        stubA.onArgsFailure("{\"x\":2}", "模拟失败");

        ToolResult r = registry.dispatch("tool_a", parse("{\"x\":2}"), ToolContext.of(1L, 1L));

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(r.summaryText()).isEqualTo("模拟失败");
        // 调用记录结局为 FAILED，可供后续 AgentLoop 断言"失败不中断"
        assertThat(stubA.invocations().get(0).outcome()).isEqualTo("FAILED");
    }

    @Test
    void thrownExceptionPropagatesAndIsCapturable() {
        stubA.onArgsThrow("{\"x\":3}", new ToolException("critical 失败", true));

        assertThatThrownBy(() -> registry.dispatch("tool_a", parse("{\"x\":3}"), null))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("critical 失败")
                .extracting(e -> ((ToolException) e).isCritical())
                .isEqualTo(true);

        // THROWN 结局已记录，供后续断言
        assertThat(stubA.invocations().get(0).outcome()).isEqualTo("THROWN");
        assertThat(stubA.callCount()).isEqualTo(1);
    }

    @Test
    void unregisteredNameThrowsToolException() {
        // P3-1 验收（03 文档）：未注册名 dispatch 抛 BusinessException（40401 未找到）
        assertThatThrownBy(() -> registry.dispatch("no_such_tool", parse("{}"), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(40401))
                .hasMessageContaining("no_such_tool");
    }

    @Test
    void invocationsRecordSeqArgsAndContext() {
        stubA.defaultSuccess("{\"v\":1}", "d1");
        stubA.execute(parse("{\"n\":1}"), ToolContext.of(11L, 21L));
        stubA.execute(parse("{\"n\":2}"), ToolContext.of(11L, 22L));
        stubA.execute(parse("{\"n\":1}"), ToolContext.of(11L, 23L));

        var calls = stubA.invocations();
        assertThat(calls).hasSize(3);
        // 顺序号递增；同参不同 ctx 均独立记录
        assertThat(calls).extracting(StubTool.Invocation::seq).containsExactly(1, 2, 3);
        assertThat(calls.get(0).ctx().stepId()).isEqualTo(21L);
        assertThat(calls.get(1).args().toString()).isEqualTo(parse("{\"n\":2}").toString());
        assertThat(calls.get(2).ctx().stepId()).isEqualTo(23L);
        // 同参数两次均走默认行为
        assertThat(calls).extracting(StubTool.Invocation::outcome)
                .containsExactly("COMPLETED", "COMPLETED", "COMPLETED");
    }

    @Test
    void multiRoundScriptsDoNotCrossContaminate() {
        // 多轮剧本 + 多实例并行使用，互不串台
        fakeLlm.thenStop("round1-stop")
                .thenToolCalls(
                        new ToolCall("c-a", "tool_a", "{\"k\":\"a\"}"),
                        new ToolCall("c-b", "tool_b", "{\"k\":\"b\"}"))
                .thenStop("round3-stop");

        stubA.onArgs("{\"k\":\"a\"}", "{\"out\":\"A\"}", "A done");
        stubB.onArgs("{\"k\":\"b\"}", "{\"out\":\"B\"}", "B done");
        registry.register(stubB);

        LlmResponse r1 = fakeLlm.complete(req("m1"));
        assertThat(r1.finishReason()).isEqualTo("stop");
        assertThat(r1.content()).isEqualTo("round1-stop");

        LlmResponse r2 = fakeLlm.complete(req("m2"));
        assertThat(r2.finishReason()).isEqualTo("tool_calls");
        for (ToolCall c : r2.toolCalls()) {
            ToolResult tr = registry.dispatch(c.name(), parse(c.argumentsJson()),
                    ToolContext.of(9L, 9L));
            if (c.name().equals("tool_a")) {
                assertThat(tr.payloadJson()).isEqualTo("{\"out\":\"A\"}");
            } else {
                assertThat(tr.payloadJson()).isEqualTo("{\"out\":\"B\"}");
            }
        }

        LlmResponse r3 = fakeLlm.complete(req("m3"));
        assertThat(r3.content()).isEqualTo("round3-stop");

        // 各自记录独立且正确
        assertThat(fakeLlm.callCount()).isEqualTo(3);
        assertThat(fakeLlm.recordedRequests()).extracting(LlmRequest::requestId)
                .containsExactly("m1", "m2", "m3");
        assertThat(stubA.callCount()).isEqualTo(1);
        assertThat(stubB.callCount()).isEqualTo(1);
    }

    @Test
    void testIsolationAcrossCases() {
        // 本用例故意污染状态；下一个用例的 setUp 会全部重建，
        // 由 multiRoundScriptsDoNotCrossContaminate 或任意后续用例的结果间接证明无残留。
        fakeLlm.thenStop("pollution");
        stubA.onArgsThrow("{}", new RuntimeException("pollute"));
        assertThat(fakeLlm.callCount()).isEqualTo(0);
        assertThat(stubA.callCount()).isEqualTo(0);
    }

    @Test
    void pollutionDidNotLeakIntoThisCase() {
        // 若 setUp 隔离失效，fakeLlm 将带着上一用例的剧本/记录运行——此处断言干净初始态
        assertThat(fakeLlm.callCount()).isZero();
        assertThat(stubA.callCount()).isZero();
        assertThat(registry.contains("tool_b")).isFalse(); // setUp 只注册了 stubA
        fakeLlm.thenStop("fresh");
        assertThat(fakeLlm.complete(req("iso")).content()).isEqualTo("fresh");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 JSON: " + json, e);
        }
    }
}
