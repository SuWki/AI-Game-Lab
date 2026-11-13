package com.aigamelab.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.config.ConfigVersionService;
import com.aigamelab.domain.ConfigVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * P3-2 读类工具：get_game_config（03 文档 P3-2 / 02 文档 §11 #2）。
 *
 * <p>读取指定版本全量配置 JSON；缺省读取当前生效版本（= latest，由 P6-1
 * {@code ConfigVersionService.getCurrent()} 提供，符合"无 active，current=findLatest()"语义）。
 * READ 权限、无需审批。
 *
 * <p>验收：dispatch("get_game_config",{}) 返回当前版本 JSON 且含 version 字段。
 */
@Component
public class GetGameConfigTool implements GameTool {

    private final ConfigVersionService configService;

    public GetGameConfigTool(ConfigVersionService configService) {
        this.configService = configService;
    }

    @Override
    public String name() {
        return "get_game_config";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("type", "string");
        version.put("description", "版本号，如 v001；缺省读取当前生效版本");
        props.put("version", version);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    public boolean requiresApproval() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String requested = args != null && args.has("version") && !args.get("version").isNull()
                ? args.get("version").asText() : null;

        ConfigVersion cv = requested != null
                ? configService.findByVersion(requested)
                        .orElseThrow(() -> new ToolException("配置版本不存在: " + requested))
                : configService.getCurrent();

        ObjectNode payload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        payload.put("version", cv.version());
        payload.put("config", cv.configJson());

        return ToolResult.success(payload.toString(),
                "已读取配置版本 " + cv.version() + "（" + cv.configJson().length() + " 字符）");
    }
}
