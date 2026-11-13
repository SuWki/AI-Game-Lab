package com.aigamelab.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.domain.DesignDocument;
import com.aigamelab.repository.DesignDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * P3-4 工具 #4：create_design_document（03 文档 P3-4 / 02 文档 §11 #7）。
 *
 * <p>WRITE 权限、无需审批。产出 Markdown 设计文档落 design_document 表
 * （复用 T4-3 已建立的 DesignDocumentRepository + 表，不新增数据层）。
 *
 * <p>仅做参数解析 + 落库 + 转 ToolResult。
 */
@Component
public class CreateDesignDocumentTool implements GameTool {

    private static final int MAX_SUMMARY = 4000;

    private final DesignDocumentRepository docRepo;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public CreateDesignDocumentTool(DesignDocumentRepository docRepo) {
        this.docRepo = docRepo;
    }

    @Override
    public String name() {
        return "create_design_document";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> title = new LinkedHashMap<>();
        title.put("type", "string");
        title.put("description", "文档标题");

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "string");
        content.put("description", "Markdown 设计文档正文");

        Map<String, Object> configVersion = new LinkedHashMap<>();
        configVersion.put("type", "string");
        configVersion.put("description", "关联配置版本（可空）");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", title);
        props.put("content", content);
        props.put("configVersion", configVersion);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("title", "content"));
        return schema;
    }

    @Override
    public boolean requiresApproval() {
        return false; // WRITE 权限，但文档产出无需审批（与 T4-3 报告工具一致）
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        if (args == null || !args.has("title") || args.get("title").isNull()
                || !args.has("content") || args.get("content").isNull()) {
            throw new ToolException("title 与 content 为必填参数");
        }
        String title = args.get("title").asText();
        String content = args.get("content").asText();

        DesignDocument saved = docRepo.insert(new DesignDocument(
                null, ctx.runId(), null, title, content, null));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("documentId", saved.id());
        payload.put("title", saved.title());

        return ToolResult.success(payload.toString(),
                truncate("已落设计文档 #" + saved.id() + "（title=" + title + "）"));
    }

    private static String truncate(String text) {
        return text == null ? "" : (text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY));
    }
}
