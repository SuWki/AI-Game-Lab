package com.aigamelab.llm;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.time.Duration;

import com.aigamelab.llm.dto.LlmRequest;
import com.aigamelab.llm.dto.LlmResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** CC Switch owns the upstream credential and selects the provider on its Claude route. */
@Component
@Profile("cc-switch")
public class CcSwitchClient implements LlmGateway {
    private final RestClient messages;
    private final RestClient health;
    private final ClaudeMessages protocol;
    private final long timeoutSeconds;
    private final int port;

    public CcSwitchClient(ObjectMapper mapper,
            @Value("${llm.gateway.proxy-port:15721}") int port,
            @Value("${llm.gateway.timeout-seconds:30}") long timeoutSeconds,
            @Value("${llm.gateway.model}") String model) {
        if (model.isBlank()) throw new IllegalArgumentException("请为 CC Switch 设置 CC_SWITCH_MODEL 模型名");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("CC_SWITCH_PORT 范围为 1–65535");
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) throw new IllegalArgumentException("LLM timeout must be 1..3600 seconds");
        this.port = port;
        this.timeoutSeconds = timeoutSeconds;
        this.protocol = new ClaudeMessages(mapper);
        this.messages = localClient(port, timeoutSeconds);
        this.health = localClient(port, 1);
    }

    private static RestClient localClient(int port, long timeoutSeconds) {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setProxy(Proxy.NO_PROXY);
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder().baseUrl("http://127.0.0.1:" + port).requestFactory(factory).build();
    }

    public boolean proxyHealthy() {
        try {
            var response = health.get().uri("/health").retrieve().toEntity(JsonNode.class);
            return response.getStatusCode().is2xxSuccessful() && response.getBody() != null
                    && "healthy".equals(response.getBody().path("status").asText());
        } catch (RestClientException e) {
            return false;
        }
    }

    public long timeoutSeconds() { return timeoutSeconds; }
    public int port() { return port; }

    @Override
    public LlmResponse complete(LlmRequest request) {
        var body = protocol.request(request);
        long start = System.nanoTime();
        try {
            var result = messages.post().uri("/v1/messages")
                    .header("x-api-key", "PROXY_MANAGED").header("anthropic-version", "2023-06-01")
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        int code = res.getStatusCode().value();
                        throw new LlmException(DeepSeekClient.classify(code), code, "CC Switch 模型请求返回 HTTP " + code);
                    }).body(JsonNode.class);
            return protocol.response(request.requestId(), result, Duration.ofNanos(System.nanoTime() - start).toMillis());
        } catch (ResourceAccessException e) {
            throw new LlmException(LlmException.Kind.RETRYABLE, null, "CC Switch 无法连接或读取超时，请检查本地代理");
        } catch (RestClientException e) {
            throw new LlmException(LlmException.Kind.NON_RETRYABLE, null, "CC Switch 返回了无效的 Messages 响应");
        }
    }
}
