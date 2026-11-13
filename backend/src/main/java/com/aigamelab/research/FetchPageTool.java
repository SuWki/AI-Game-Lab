package com.aigamelab.research;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolResult;

/**
 * R0-5：fetch_page 工具。
 *
 * 接收 URL，通过 WebFetchService 获取页面内容，提取纯文本，返回截断后的内容。
 */
@Component
public class FetchPageTool extends AbstractResearchTool {

    private final WebFetchService webFetchService;

    public FetchPageTool(WebFetchService webFetchService) {
        this.webFetchService = webFetchService;
    }

    @Override
    public String name() {
        return "fetch_page";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema("获取指定 URL 的页面内容并提取纯文本。",
                Map.of(
                        "url", Map.of("type", "string", "description", "要获取的网页 URL")),
                "url");
    }

    @Override
    protected ToolResult doExecute(JsonNode args, ToolContext ctx) {
        String url = requireText(args, "url");

        String text = webFetchService.fetchText(url);
        if (text == null) {
            return ToolResult.failure("无法获取页面内容: " + url);
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("url", url);
        payload.put("contentLength", text.length());
        payload.put("content", text);

        String summary = "获取页面成功: " + url + " (" + text.length() + " 字符)";
        return ToolResult.success(payload.toString(), summary);
    }
}
