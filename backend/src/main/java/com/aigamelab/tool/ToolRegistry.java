package com.aigamelab.tool;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aigamelab.common.BusinessException;
import com.aigamelab.common.ErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 蓝图 §11 冻结契约：工具注册表。
 * Spring 收集所有 GameTool Bean，启动时按 name 注册；Agent 经 dispatch 调用，
 * 不直接 new。P1-0 阶段为最小实现：注册 + dispatch + 未知名抛 ToolException；
 * P3-1 验收增强：补 size() 计数、dispatch 未知名改抛 BusinessException（03 验收准则
 * "未知名抛 BusinessException" 优先于 02 技术设计的 ToolException 描述）；
 * JSON Schema 校验、审批拦截等由后续 P3-5 ToolExecutor 承担，不在此处。
 */
@Component
public class ToolRegistry {

    private final Map<String, GameTool> tools = new LinkedHashMap<>();

    public ToolRegistry(Collection<GameTool> discovered) {
        for (GameTool t : discovered) {
            GameTool prev = tools.put(t.name(), t);
            if (prev != null) {
                throw new IllegalStateException("重复的工具名: " + t.name());
            }
        }
    }

    /** 测试场景手动注册替身 */
    public void register(GameTool tool) {
        GameTool prev = tools.put(tool.name(), tool);
        if (prev != null) {
            throw new IllegalStateException("重复的工具名: " + tool.name());
        }
    }

    public GameTool get(String name) {
        return tools.get(name);
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public Collection<GameTool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /** 已注册工具数量（P3-1 验收：注册 8 个后 size()==8） */
    public int size() {
        return tools.size();
    }

    /** 按名分发；未注册名抛 BusinessException（03 验收准则；HTTP 层统一转 40401 包络） */
    public ToolResult dispatch(String name, JsonNode args, ToolContext ctx) {
        GameTool tool = tools.get(name);
        if (tool == null) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "未注册的工具: " + name);
        }
        return tool.execute(args, ctx);
    }
}
