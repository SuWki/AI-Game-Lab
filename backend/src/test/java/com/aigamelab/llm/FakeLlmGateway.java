package com.aigamelab.llm;

import java.util.ArrayList;
import java.util.List;

import com.aigamelab.llm.dto.LlmRequest;
import com.aigamelab.llm.dto.LlmResponse;
import com.aigamelab.llm.dto.ToolCall;
import com.aigamelab.llm.dto.TokenUsage;

/**
 * P1-0 测试替身：脚本化 LlmGateway。
 *
 * 特性：
 * - 按调用序号出队预设剧本（stop / tool_calls / length / 抛异常四种）；
 *   剧本耗尽默认返回 stop 兜底，也可配置为抛异常。
 * - 记录每次实际收到的 LlmRequest 与产出的 LlmResponse，供测试断言。
 * - 不访问真实 API、不引入任何 SDK；单线程设计（AgentLoop 为顺序循环），
 *   并发使用需外部同步。
 */
public class FakeLlmGateway implements LlmGateway {

    /** 一条剧本：一次 complete() 的行为（四种） */
    public sealed interface Script permits Stop, ToolCalls, Length, Failure {
    }

    public record Stop(String content) implements Script {
    }

    public record ToolCalls(List<ToolCall> calls) implements Script {
    }

    public record Length(String content) implements Script {
    }

    /** 抛异常剧本：模拟 LLM 挂 / 超时 / 不可重试错误 */
    public record Failure(RuntimeException error) implements Script {
    }

    private final List<Script> script = new ArrayList<>();
    private final List<LlmRequest> requests = new ArrayList<>();
    private final List<LlmResponse> responses = new ArrayList<>();
    /** 剧本耗尽后的行为：null=默认 stop 兜底；非 null=每次抛该异常 */
    private RuntimeException exhaustedBehavior;
    private int cursor = 0;

    // ---------- 剧本编排（链式 API） ----------

    public FakeLlmGateway thenStop(String content) {
        script.add(new Stop(content));
        return this;
    }

    public FakeLlmGateway thenToolCalls(ToolCall... calls) {
        script.add(new ToolCalls(List.of(calls)));
        return this;
    }

    public FakeLlmGateway thenLength(String content) {
        script.add(new Length(content));
        return this;
    }

    public FakeLlmGateway thenFailure(RuntimeException error) {
        script.add(new Failure(error));
        return this;
    }

    /** 剧本耗尽后改为抛指定异常（默认为 stop 兜底） */
    public FakeLlmGateway onExhaustedThrow(RuntimeException error) {
        this.exhaustedBehavior = error;
        return this;
    }

    // ---------- LlmGateway 契约 ----------

    @Override
    public LlmResponse complete(LlmRequest request) {
        requests.add(request);
        if (cursor >= script.size()) {
            if (exhaustedBehavior != null) {
                throw exhaustedBehavior;
            }
            LlmResponse fallback = LlmResponse.stop(request.requestId(), "script exhausted");
            responses.add(fallback);
            return fallback;
        }
        Script step = script.get(cursor++);
        LlmResponse resp;
        if (step instanceof Stop s) {
            resp = new LlmResponse(request.requestId(), "stop", s.content(), null,
                    TokenUsage.of(10, 5), 0, "fake");
        } else if (step instanceof ToolCalls t) {
            resp = new LlmResponse(request.requestId(), "tool_calls", null, t.calls(),
                    TokenUsage.of(10, 5), 0, "fake");
        } else if (step instanceof Length l) {
            resp = new LlmResponse(request.requestId(), "length", l.content(), null,
                    TokenUsage.of(10, 5), 0, "fake");
        } else if (step instanceof Failure f) {
            throw f.error();
        } else {
            throw new IllegalStateException("未知剧本类型: " + step);
        }
        responses.add(resp);
        return resp;
    }

    // ---------- 断言用观测接口 ----------

    public List<LlmRequest> recordedRequests() {
        return List.copyOf(requests);
    }

    /** 清空剧本与观测记录，供 @BeforeEach 重置跨用例累积状态。 */
    public void reset() {
        script.clear();
        requests.clear();
        responses.clear();
        exhaustedBehavior = null;
        cursor = 0;
    }

    public List<LlmResponse> producedResponses() {
        return List.copyOf(responses);
    }

    public int callCount() {
        return requests.size();
    }
}
