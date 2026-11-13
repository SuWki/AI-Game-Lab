package com.aigamelab.research;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * TH：SerpAPI 真实搜索适配器（REST，query 参数鉴权）。
 *
 * <p>GET {base-url}/search.json?engine=google&q=…&num=…&api_key=…
 * 响应 organic_results[].{title, link, snippet}；total_results 为真实计数。
 *
 * <p>凭据策略与 DeepSeekClient 一致：仅当 research.search.api-key 存在且非空白串时
 * 注册为 SearchGateway Bean；未配置的环境/测试上下文不产生该 Bean，
 * 避免无凭据时 WebSearchTool 伪造结果。
 */
@Component
@ConditionalOnProperty(prefix = "research.search", name = "api-key")
@org.springframework.context.annotation.Conditional(SerpApiSearchClient.ApiKeyPresent.class)
public class SerpApiSearchClient implements SearchGateway {

    /** api-key 配置存在且非空白串才启用真实客户端（空串占位不启用） */
    static class ApiKeyPresent implements org.springframework.context.annotation.Condition {
        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext ctx,
                org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String key = ctx.getEnvironment().getProperty("research.search.api-key");
            return key != null && !key.isBlank();
        }
    }

    private final RestClient restClient;
    private final String apiKey;

    @org.springframework.beans.factory.annotation.Autowired
    public SerpApiSearchClient(
            @Value("${research.search.api-key}") String apiKey,
            @Value("${research.search.base-url:https://serpapi.com/search.json}") String baseUrl,
            @Value("${research.search.timeout-seconds:15}") long timeoutSeconds) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
                .build();
    }

    @Override
    public List<SearchResult> search(String query, int numResults) {
        int capped = Math.max(1, Math.min(numResults, 100)); // SerpAPI num 上限 100

        JsonNode body;
        try {
            body = restClient.get()
                    .uri(ub -> ub.path("/search.json")
                            .queryParam("engine", "google")
                            .queryParam("q", query)
                            .queryParam("num", capped)
                            .queryParam("api_key", apiKey)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            throw new SearchGatewayException("SerpAPI HTTP " + e.getStatusCode().value()
                    + ": " + safeReason(e));
        } catch (RuntimeException e) {
            throw new SearchGatewayException("SerpAPI 网络错误: " + rootMessage(e));
        }
        if (body == null) {
            throw new SearchGatewayException("SerpAPI 响应为空");
        }

        List<SearchResult> results = new ArrayList<>();
        JsonNode organic = body.get("organic_results");
        if (organic != null && organic.isArray()) {
            for (JsonNode n : organic) {
                results.add(new SearchResult(
                        textOr(n.get("title"), ""),
                        textOr(n.get("link"), ""),
                        textOr(n.get("snippet"), "")));
            }
        }
        if (results.isEmpty()) {
            throw new SearchGatewayException("SerpAPI 无搜索结果（可能触达限流/无 organic_results）");
        }
        return results;
    }

    private static String textOr(JsonNode n, String dflt) {
        return n == null || n.isNull() ? dflt : n.asText();
    }

    private static String safeReason(org.springframework.web.client.HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank() || body.length() > 300) {
            return e.getStatusCode().toString();
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

    /** 搜索后端异常：非校验类错误（HTTP/网络/解析），由 WebSearchTool 翻译为 failure。 */
    public static class SearchGatewayException extends RuntimeException {
        public SearchGatewayException(String msg) {
            super(msg);
        }
    }
}
