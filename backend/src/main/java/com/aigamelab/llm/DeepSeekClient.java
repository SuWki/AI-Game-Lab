package com.aigamelab.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.aigamelab.llm.dto.LlmRequest;
import com.aigamelab.llm.dto.LlmResponse;
import com.aigamelab.llm.dto.TokenUsage;
import com.aigamelab.llm.dto.ToolCall;

/**
 * 蓝图 §10 / P2-2：RestClient 直连 OpenAI 兼容 POST {baseUrl}/chat/completions。
 * Bearer auth；messages/tools/tool_calls/tool_call_id 原样映射；
 * function.arguments 字符串透传；读超时配置化。
 *
 * P2-3 起重试由 GatewayRetry 包装；本类只负责单次调用与映射，
 * 错误按可重试性分类抛 LlmException（429/5xx/网络→RETRYABLE，400/401/403/解析→NON_RETRYABLE）。
 *
 * 仅当 api-key 非空时注册为 LlmGateway Bean：
 * 未配置 DEEPSEEK_API_KEY 的环境/测试上下文不产生该 Bean，
 * 避免与 FakeLlmGateway 形成 NoUniqueBeanDefinitionException。
 */
@Component
@ConditionalOnProperty(prefix = "llm.gateway", name = "api-key")
@org.springframework.context.annotation.Conditional(DeepSeekClient.ApiKeyPresent.class)
public class DeepSeekClient implements LlmGateway {

    /** api-key 配置存在且非空白串才启用真实客户端（空串占位不启用） */
    static class ApiKeyPresent implements org.springframework.context.annotation.Condition {
        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext ctx,
                org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String key = ctx.getEnvironment().getProperty("llm.gateway.api-key");
            return key != null && !key.isBlank();
        }
    }

    private final RestClient restClient;
    /** P2-3：可重试错误退避包装（构造器可注入测试级小退避） */
    private final GatewayRetry retry;

    @org.springframework.beans.factory.annotation.Autowired
    public DeepSeekClient(
            @Value("${llm.gateway.api-key}") String apiKey,
            @Value("${llm.gateway.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${llm.gateway.timeout-seconds:120}") long timeoutSeconds) {
        this(apiKey, baseUrl, timeoutSeconds, GatewayRetry.production());
    }

    /** 测试入口：注入 ms 级退避的 GatewayRetry */
    DeepSeekClient(String apiKey, String baseUrl, long timeoutSeconds, GatewayRetry retry) {
        if(timeoutSeconds<1||timeoutSeconds>3600)throw new IllegalArgumentException("LLM timeout must be 1..3600 seconds");
        var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // SimpleClientHttpRequestFactory：JDK URL 连接，规避 JDK HttpClient
                // 与 WireMock keep-alive/chunked 的兼容性问题（生产 baseUrl 为 https api.deepseek.com 不受影响）
                .requestFactory(factory)
                .build();
        this.timeoutSeconds = timeoutSeconds;
        this.retry = retry;
    }

    private final long timeoutSeconds;

    @Override
    public LlmResponse complete(LlmRequest request) {
        return retry.execute(() -> doComplete(request));
    }

    private LlmResponse doComplete(LlmRequest request) {
        long t0 = System.currentTimeMillis();
        DeepSeekChatResponse body;
        try {
            body = restClient.post()
                    .uri("/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(DeepSeekChatResponse.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            throw new LlmException(classify(status), status,
                    "LLM 上游错误 HTTP " + status + ": " + safeReason(e));
        } catch (RuntimeException e) {
            // 网络/超时类：一律 RETRYABLE（P2-3 指数退避）
            throw new LlmException(LlmException.Kind.RETRYABLE,
                    "LLM 网络错误: " + rootMessage(e), e);
        }
        if (body == null || body.choices() == null || body.choices().isEmpty()) {
            // 坏 JSON/空响应：NON_RETRYABLE 解析类（P2-3 限重试 1 次）
            throw new LlmException(LlmException.Kind.NON_RETRYABLE, null,
                    "LLM 响应为空或格式非法", true);
        }
        return toLlmResponse(request.requestId(), body,
                System.currentTimeMillis() - t0);
    }

    /** 配置化读超时暴露（P2-3 GatewayRetry 参考） */
    public Duration readTimeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    // ---- 映射：内部 DTO → 对外 LlmResponse ----

    private LlmResponse toLlmResponse(String requestId, DeepSeekChatResponse body, long latencyMs) {
        var choice = body.choices().get(0);
        String finish = mapFinishReason(choice.finish_reason());

        List<ToolCall> toolCalls = null;
        if (choice.message() != null && choice.message().tool_calls() != null
                && !choice.message().tool_calls().isEmpty()) {
            toolCalls = new ArrayList<>();
            for (DeepSeekChatResponse.WireToolCall w : choice.message().tool_calls()) {
                toolCalls.add(new ToolCall(w.id(),
                        w.function() != null ? w.function().name() : null,
                        w.function() != null ? w.function().arguments() : null));
            }
            if ("stop".equals(finish)) {
                finish = "tool_calls"; // 有 tool_calls 的 stop 统一归一为 tool_calls 分支
            }
        }
        return new LlmResponse(requestId, finish,
                choice.message() != null ? choice.message().content() : null,
                toolCalls, toUsage(body.usage()), latencyMs, body.model());
    }

    /** OpenAI finish_reason → AGL 三分支（stop/tool_calls/length），未知值保守归 length 兜底 */
    private String mapFinishReason(String raw) {
        if (raw == null) {
            return "length";
        }
        return switch (raw) {
            case "stop" -> "stop";
            case "tool_calls" -> "tool_calls";
            default -> "length"; // length 或其他 → 截断兜底总结分支（蓝图 §9.4 非 FAILED）
        };
    }

    private TokenUsage toUsage(DeepSeekChatResponse.Usage u) {
        if (u == null) {
            return null;
        }
        return new TokenUsage(nvl(u.prompt_tokens()), nvl(u.completion_tokens()),
                nvl(u.total_tokens()), u.prompt_cache_hit_tokens(),
                u.prompt_cache_miss_tokens());
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    // ---- 错误分类（蓝图 §10 重试策略的分类依据） ----

    static LlmException.Kind classify(int httpStatus) {
        if (httpStatus == 429 || httpStatus >= 500) {
            return LlmException.Kind.RETRYABLE; // 限流/服务端临时故障
        }
        return LlmException.Kind.NON_RETRYABLE; // 400/401/403 等请求侧问题
    }

    private static String safeReason(org.springframework.web.client.HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank() || body.length() > 300) {
            return e.getStatusCode().toString(); // 不透传上游大段响应体
        }
        return body;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName() : msg;
    }
}
