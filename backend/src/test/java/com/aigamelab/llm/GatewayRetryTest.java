package com.aigamelab.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
 * P2-3 验收测试（03 文档，冻结策略）：
 * - 连续 500×2 次尝试后抛异常且恰好请求 2 次（5xx×2 预算）
 * - 连续 429×3 次尝试后抛异常且恰好请求 3 次（限流 ×3 预算），Retry-After 优先退避
 * - 网络错误 ×3 预算
 * - 坏 JSON 解析类 NON_RETRYABLE 允许重试 1 次
 * - 400/401/403 直接抛不重试
 */
@DisplayName("P2-3 重试与错误分类")
class GatewayRetryTest {

    private WireMockServer wire;
    private DeepSeekClient client;

    /** 记录 GatewayRetry 实际等待的毫秒数（验证 Retry-After 优先与指数形状） */
    private List<Long> sleptMs;

    @BeforeEach
    void setUp() {
        wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
        sleptMs = new java.util.ArrayList<>();
        // ms 级退避 + 记录型 Sleeper；jitter 0 保证次数/时间可精确断言
        GatewayRetry recording = new GatewayRetry(10, 0.0, sleptMs::add, e -> {
            // 生产中由 HTTP 头读取；此处按 httpStatus==429 返回固定 Retry-After=50ms 模拟
            if (e.httpStatus() != null && e.httpStatus() == 429) {
                return 50L;
            }
            return null;
        });
        client = new DeepSeekClient("k", wire.baseUrl(), 2, recording);
    }

    @AfterEach
    void tearDown() {
        wire.stop();
    }

    private LlmRequest request() {
        return new LlmRequest("rq-r", 1L, "deepseek-chat",
                List.of(LlmMessage.user("hi")), null, 0.4, 100, "p001");
    }

    private int requestCount() {
        return wire.getServeEvents().getRequests().size();
    }

    @Test
    void serverError500RetriesOnceThenThrowsExactlyTwoAttempts() {
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).kind())
                .isEqualTo(LlmException.Kind.RETRYABLE);

        assertThat(requestCount()).isEqualTo(2);      // 5xx×2 预算：首试 + 1 重试
        assertThat(sleptMs).hasSize(1);               // 仅一次退避
        assertThat(sleptMs.get(0)).isEqualTo(10);     // base×2^0：首次重试退避 = base
    }

    @Test
    void rateLimit429HonorsRetryAfterAndThreeAttempts() {
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Retry-After", "1").withBody("rate")));

        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).kind())
                .isEqualTo(LlmException.Kind.RETRYABLE);

        assertThat(requestCount()).isEqualTo(3);      // 429×3 预算
        assertThat(sleptMs).hasSize(2);
        assertThat(sleptMs.get(0)).isEqualTo(50);     // Retry-After 优先于指数退避
        assertThat(sleptMs.get(1)).isEqualTo(50);
    }

    @Test
    void networkFailureRecoversOnSecondAttempt() {
        // WireMock Scenario 状态机：START 返回 503 并迁移到 RECOVERED；RECOVERED 返回 200。
        // （链式 willReturn 不是请求序列——后者会覆盖前者）
        String ok = """
                {"id":"ok","model":"m","choices":[{"index":0,
                  "message":{"role":"assistant","content":"恢复"},
                  "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """;
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("recover")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withBody("unavailable"))
                .willSetStateTo("RECOVERED"));
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("recover")
                .whenScenarioStateIs("RECOVERED")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(ok)));

        LlmResponse resp = client.complete(request());
        assertThat(resp.content()).isEqualTo("恢复");
        assertThat(requestCount()).isEqualTo(2);      // 第一次 5xx 重试后成功
    }

    @Test
    void parseFailureRetriesOnceThenThrows() {
        // 第一次坏 JSON（解析类），第二次合法 → 成功（预算内恢复）。同上用 Scenario 表达序列。
        String ok = """
                {"id":"ok","model":"m","choices":[{"index":0,
                  "message":{"role":"assistant","content":"good"},
                  "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """;
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("parse")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"not-json\":"))
                .willSetStateTo("GOOD"));
        wire.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("parse")
                .whenScenarioStateIs("GOOD")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(ok)));

        LlmResponse resp = client.complete(request());
        assertThat(resp.content()).isEqualTo("good");
        assertThat(requestCount()).isEqualTo(2);
    }

    @Test
    void clientErrors400_401_403ThrowImmediatelyWithoutRetry() {
        for (int status : new int[] {400, 401, 403}) {
            wire.resetAll();
            sleptMs.clear();
            final int s = status;
            wire.stubFor(post(urlEqualTo("/chat/completions"))
                    .willReturn(aResponse().withStatus(s).withBody("{\"error\":\"x\"}")));

            assertThatThrownBy(() -> client.complete(request()))
                    .isInstanceOf(LlmException.class)
                    .extracting(e -> ((LlmException) e).kind())
                    .isEqualTo(LlmException.Kind.NON_RETRYABLE);
            assertThat(requestCount()).as("HTTP %d 不应重试", status).isEqualTo(1);
            assertThat(sleptMs).as("HTTP %d 无退避", status).isEmpty();
        }
    }

    @Test
    void classifyMapsStatusBandsPerFrozenPolicy() {
        assertThat(DeepSeekClient.classify(429)).isEqualTo(LlmException.Kind.RETRYABLE);
        assertThat(DeepSeekClient.classify(500)).isEqualTo(LlmException.Kind.RETRYABLE);
        assertThat(DeepSeekClient.classify(503)).isEqualTo(LlmException.Kind.RETRYABLE);
        assertThat(DeepSeekClient.classify(400)).isEqualTo(LlmException.Kind.NON_RETRYABLE);
        assertThat(DeepSeekClient.classify(401)).isEqualTo(LlmException.Kind.NON_RETRYABLE);
        assertThat(DeepSeekClient.classify(403)).isEqualTo(LlmException.Kind.NON_RETRYABLE);
    }
}
