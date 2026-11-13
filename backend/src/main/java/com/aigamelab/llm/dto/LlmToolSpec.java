package com.aigamelab.llm.dto;

import java.util.Map;

/**
 * 蓝图 §10 冻结契约：注册给 LLM 的工具规格。
 *
 * @param jsonSchema OpenAI function schema
 */
public record LlmToolSpec(String name, String description, Map<String, Object> jsonSchema,
        boolean requiresApproval) {
    @com.fasterxml.jackson.annotation.JsonValue
    public Map<String,Object> wireValue() {
        Map<String,Object> parameters=jsonSchema==null?Map.of("type","object","properties",Map.of()):jsonSchema;
        if(parameters.get("function") instanceof Map<?,?> fn && fn.get("parameters") instanceof Map<?,?> nested) {
            @SuppressWarnings("unchecked") Map<String,Object> normalized=(Map<String,Object>)nested;
            parameters=normalized;
        }
        return Map.of("type","function","function",Map.of("name",name,"description",description==null?name:description,"parameters",parameters));
    }
}
