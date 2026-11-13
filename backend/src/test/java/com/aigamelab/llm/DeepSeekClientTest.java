package com.aigamelab.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aigamelab.llm.dto.LlmMessage;
import com.aigamelab.llm.dto.LlmRequest;
import com.aigamelab.llm.dto.LlmResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * P2-2 验收测试（03 文档）：WireMock 返回标准响应时正确映射为 LlmResponse；
 * 返回 400 时抛 NON_RETRYABLE LlmException。请求体形状（Bearer/线协议）一并断言。
 */
@DisplayName("P2-2 DeepSeekClient WireMock 映射")
class DeepSeekClientTest {

    private WireMockServer wire;
    private DeepSeekClient client;

    @BeforeEach
    void setUp() {
        wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
        // ms 级退避：测试不真实等待；jitter 0 保证请求次数精确断言
        client = new DeepSeekClient("test-key-123", wire.baseUrl(), 2,
                new GatewayRetry(1, 0.0));
    }

    @AfterEach
    void tearDown() {
        wire.stop();
    }

    private LlmRequest request() {
        return new LlmRequest("rq-1", 7L, "deepseek-chat",
                List.of(LlmMessage.system("sys"), LlmMessage.user("goal")),
                null, 0.4, 4096, "p001");
    }

    @Test void actualHttpRequestRequiresNormalizedFunctionSchema() {
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.tools[0].function.name",equalTo("get_game_config")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.tools[0].function.parameters.type",equalTo("object")))
                .willReturn(aResponse().withHeader("Content-Type","application/json").withBody("{\"model\":\"test\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"accepted schema\"},\"finish_reason\":\"stop\"}]}")));
        var envelope=java.util.Map.<String,Object>of("type","function","function",java.util.Map.of("name","ignored-internal-name","parameters",java.util.Map.of("type","object","properties",java.util.Map.of())));
        var req=new LlmRequest("wire",1L,"test",List.of(LlmMessage.user("test")),List.of(new com.aigamelab.llm.dto.LlmToolSpec("get_game_config","read config",envelope,true)),0.4,100,null);
        assertThat(client.complete(req).content()).isEqualTo("accepted schema");
        String body=wire.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(body).doesNotContain("requiresApproval","jsonSchema","requestId");
    }

    @Test void configuredReadTimeoutIsActuallyApplied() {
        client=new DeepSeekClient("test",wire.baseUrl(),1,new GatewayRetry(1,0));
        wire.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withFixedDelay(5000).withHeader("Content-Type","application/json").withBody("{}")));
        long started=System.nanoTime();assertThatThrownBy(()->client.complete(request())).isInstanceOf(LlmException.class);
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-started).toMillis()).isLessThan(4800);
    }

    @Test
    void mapsStandardStopResponse() {
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-1",
                                  "model": "deepseek-chat",
                                  "choices": [{
                                    "index": 0,
                                    "message": {"role": "assistant", "content": "平衡结论：达标"},
                                    "finish_reason": "stop"
                                  }],
                                  "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150,
                                             "prompt_cache_hit_tokens": 60, "prompt_cache_miss_tokens": 40}
                                }
                                """)));

        LlmResponse resp = client.complete(request());

        assertThat(resp.finishReason()).isEqualTo("stop");
        assertThat(resp.content()).isEqualTo("平衡结论：达标");
        assertThat(resp.toolCalls()).isNull();
        assertThat(resp.rawModelId()).isEqualTo("deepseek-chat");
        assertThat(resp.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(resp.usage().promptTokens()).isEqualTo(100);
        assertThat(resp.usage().completionTokens()).isEqualTo(50);
        assertThat(resp.usage().totalTokens()).isEqualTo(150);
        assertThat(resp.usage().promptCacheHitTokens()).isEqualTo(60);
        assertThat(resp.usage().promptCacheMissTokens()).isEqualTo(40);

        // 线协议请求形状：Bearer + model + messages
        wire.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-key-123")));
        var recorded = wire.getServeEvents().getRequests();
        assertThat(recorded).hasSize(1);
        var body = recorded.get(0).getRequest().getBodyAsString();
        assertThat(body).contains("\"model\":\"deepseek-chat\"");
        assertThat(body).contains("\"role\":\"system\"");
        assertThat(body).contains("\"max_tokens\":4096");
    }

    @Test
    void mapsToolCallsResponseWithArgumentsPassthrough() {
        // arguments 为字符串透传（含空格，不做任何解析重写）
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "c-2",
                                  "model": "deepseek-chat",
                                  "choices": [{
                                    "index": 0,
                                    "message": {
                                      "role": "assistant",
                                      "content": null,
                                      "tool_calls": [{
                                        "id": "call_9",
                                        "type": "function",
                                        "function": {"name": "simulate_game", "arguments": "{\\"seed\\": 42}"}
                                      }]
                                    },
                                    "finish_reason": "tool_calls"
                                  }],
                                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                                }
                                """)));

        LlmResponse resp = client.complete(request());

        assertThat(resp.finishReason()).isEqualTo("tool_calls");
        assertThat(resp.toolCalls()).hasSize(1);
        var tc = resp.toolCalls().get(0);
        assertThat(tc.id()).isEqualTo("call_9");
        assertThat(tc.name()).isEqualTo("simulate_game");
        assertThat(tc.argumentsJson()).isEqualTo("{\"seed\": 42}"); // 原样透传
    }

    @Test
    void mapsLengthFinishReasonToTruncationBranch() {
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"c-3","model":"deepseek-chat","choices":[{
                                   "index":0,
                                   "message":{"role":"assistant","content":"半截..."},
                                   "finish_reason":"length"}],
                                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                                """)));

        LlmResponse resp = client.complete(request());
        assertThat(resp.finishReason()).isEqualTo("length"); // AgentLoop 按截断兜底 COMPLETED
    }

    @Test
    void badRequest400ThrowsNonRetryable() {
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"error\":{\"message\":\"Invalid request\"}}")));

        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> {
                    LlmException le = (LlmException) e;
                    assertThat(le.kind()).isEqualTo(LlmException.Kind.NON_RETRYABLE);
                    assertThat(le.httpStatus()).isEqualTo(400);
                });
    }

    @Test
    void serverError500AndRateLimit429AreRetryableKind() {
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).kind())
                .isEqualTo(LlmException.Kind.RETRYABLE);

        wire.resetAll();
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Retry-After", "7").withBody("rate")));
        try {
            client.complete(request());
        } catch (LlmException e) {
            assertThat(e.kind()).isEqualTo(LlmException.Kind.RETRYABLE);
            assertThat(e.httpStatus()).isEqualTo(429);
        }
    }

    @Test
    void emptyBodyThrowsNonRetryableParseFailure() {
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"x\",\"model\":\"m\",\"choices\":[]}")));

        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).kind())
                .isEqualTo(LlmException.Kind.NON_RETRYABLE);
    }
}
