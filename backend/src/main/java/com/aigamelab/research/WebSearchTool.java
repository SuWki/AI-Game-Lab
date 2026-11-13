package com.aigamelab.research;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;

/**
 * TH：web_search 工具（真实搜索实现）。
 *
 * <p>接收搜索查询，通过 {@link SearchGateway} 后端（如 SerpAPI/Bing）获取真实搜索结果。
 *
 * <p>凭据策略：
 * <ul>
 *   <li>有凭据（env SERPAPI_API_KEY 非空）→ 调用真实搜索 API，totalResults 为真实计数（③）。</li>
 *   <li>无凭据 → 返回 {@code BLOCKED — PROVIDER CREDENTIAL REQUIRED} 明确失败信息，
 *       <b>绝不伪造搜索结果</b>（②）。</li>
 * </ul>
 *
 * <p>R0 阶段 Stub 实现已移除（①）；密钥环境变量化（④）。
 */
@Component
public class WebSearchTool extends AbstractResearchTool {

    private final Optional<SearchGateway> searchGateway;

    public WebSearchTool(Optional<SearchGateway> searchGateway) {
        this.searchGateway = searchGateway;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("搜索网页获取信息。返回相关网页的标题、URL 和摘要列表。",
                Map.of(
                        "query", Map.of("type", "string", "description", "搜索查询关键词"),
                        "numResults", Map.of("type", "integer", "description", "期望返回的结果数量（默认 5）")),
                "query");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        String query = requireText(args, "query");
        int numResults = args.has("numResults") && !args.get("numResults").isNull()
                ? args.get("numResults").asInt(5) : 5;

        // TH ②：无凭据 → 明确报错，不伪造结果
        if (searchGateway.isEmpty()) {
            return ToolResult.failure(
                    "BLOCKED — PROVIDER CREDENTIAL REQUIRED: "
                    + "未配置 search provider 密钥（research.search.api-key / 环境变量 SERPAPI_API_KEY），"
                    + "web_search 真实搜索不可用。请配置密钥后重试。");
        }

        // TH ①③：真实搜索 + totalResults 为 API 实际返回数量
        List<SearchGateway.SearchResult> results;
        try {
            results = searchGateway.get().search(query, numResults);
        } catch (SerpApiSearchClient.SearchGatewayException e) {
            return ToolResult.failure("真实搜索失败: " + e.getMessage());
        }

        ArrayNode resultsArray = MAPPER.createArrayNode();
        for (SearchGateway.SearchResult r : results) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("title", r.title());
            node.put("url", r.url());
            node.put("snippet", r.snippet());
            resultsArray.add(node);
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("query", query);
        payload.set("results", resultsArray);
        payload.put("totalResults", results.size()); // ③ 真实计数

        return ToolResult.success(payload.toString(),
                "搜索「" + query + "」返回 " + results.size() + " 条真实结果");
    }
}
