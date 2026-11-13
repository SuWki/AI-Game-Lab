package com.aigamelab.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * TH 验收：WebSearchTool 行为。
 *
 * <p>① 无凭据（Optional.empty）→ BLOCKED 报错（不伪造结果）
 * <p>② 有 SearchGateway → 真实结果，totalResults = 实际返回数量（非请求数）
 * <p>③ Gateway 异常 → failure，Run 继续
 */
@DisplayName("TH WebSearchTool 无凭据 BLOCKED + 真实搜索验证")
class WebSearchToolTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final ToolContext CTX = new ToolContext(1L, 1L);

    /** 无凭据：BLOCKED 明确报错，不含伪造结果 */
    @Test
    @DisplayName("TH 无 SearchGateway 时返回 BLOCKED — PROVIDER CREDENTIAL REQUIRED")
    void blockedWhenNoGateway() {
        WebSearchTool tool = new WebSearchTool(Optional.empty());

        ToolResult result = tool.execute(
                json("{\"query\":\"AI游戏设计\"}"), CTX);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.summaryText()).contains("BLOCKED — PROVIDER CREDENTIAL REQUIRED");
        assertThat(result.summaryText()).contains("research.search.api-key");
        assertThat(result.payloadJson()).isNull(); // 不伪造任何结果
    }

    /** 有 gateway 时返回真实结果，totalResults = API 实际返回条数 */
    @Test
    @DisplayName("TH 有 SearchGateway 时 totalResults 等于真实返回条数（非请求数）")
    void realSearchReturnsActualCount() {
        SearchGateway fake = (q, n) -> List.of(
                new SearchGateway.SearchResult("AI 游戏设计", "https://example.com/1", "摘要1"),
                new SearchGateway.SearchResult("游戏 AI 应用", "https://example.com/2", "摘要2"));

        WebSearchTool tool = new WebSearchTool(Optional.of(fake));

        // 请求 10 条但 API 实际只返回 2 条
        ToolResult result = tool.execute(
                json("{\"query\":\"AI游戏设计\",\"numResults\":10}"), CTX);

        assertThat(result.status()).isEqualTo("COMPLETED");
        JsonNode payload = json(result.payloadJson());
        assertThat(payload.get("totalResults").asInt()).isEqualTo(2); // 真实计数，非 10
        assertThat(payload.get("results").size()).isEqualTo(2);
        assertThat(payload.get("results").get(0).get("title").asText()).isEqualTo("AI 游戏设计");
    }

    /** Gateway 异常 → failure，不抛出 ToolException，Run 继续 */
    @Test
    @DisplayName("TH SearchGateway 异常 → failure，Run 继续（non-critical）")
    void gatewayExceptionTranslatedToFailure() {
        SearchGateway failing = (q, n) -> {
            throw new SerpApiSearchClient.SearchGatewayException("SerpAPI HTTP 429: rate limit");
        };
        WebSearchTool tool = new WebSearchTool(Optional.of(failing));

        ToolResult result = tool.execute(json("{\"query\":\"测试\"}"), CTX);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.summaryText()).contains("真实搜索失败");
        assertThat(result.summaryText()).contains("429");
    }

    private static JsonNode json(String s) {
        try { return M.readTree(s); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
