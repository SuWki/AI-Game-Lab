package com.aigamelab.tool;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 蓝图 §11 冻结契约：GameTool 接口。
 * 注册表启动扫描注册；Agent 经 dispatch 调用，不直接 new。
 */
public interface GameTool {

    String name();

    /** OpenAI function schema */
    Map<String, Object> jsonSchema();

    boolean requiresApproval();

    ToolResult execute(JsonNode args, ToolContext ctx);
}
