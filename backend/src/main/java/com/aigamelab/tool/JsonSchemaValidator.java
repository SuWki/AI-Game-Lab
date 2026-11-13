package com.aigamelab.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P3-1 / P3-5：OpenAI function schema 最小子集校验器（蓝图 §11）。
 *
 * 仅支持本项目工具实际用到的 schema 子集：
 * - 顶层 type=object + properties（每属性 type 白名单 string/number/integer/boolean/object/array/array of object）
 * - required 数组（缺失/空值即非法）
 * - 额外属性默认允许（不强制 additionalProperties=false）
 *
 * 设计取舍：不引入 everit/networknt 等完整校验库，避免新增第三方依赖；
 * 本校验器的目标是拦截"AI 漏填 required 字段 / 传错基本类型"这类高频错误，
 * 复杂嵌套结构校验由具体 Tool 自身在 execute 内做业务级校验补充。
 */
public final class JsonSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSchemaValidator() {
    }

    /**
     * 校验 args 是否符合 schema。
     *
     * @return 错误信息列表；空列表表示通过
     */
    public static List<String> validate(Map<String, Object> schema, JsonNode args) {
        List<String> errors = new ArrayList<>();
        if (schema == null || schema.isEmpty()) {
            return errors; // 无 schema 则不校验
        }
        JsonNode root = args == null ? MAPPER.createObjectNode() : args;
        // 兼容 OpenAI function 信封：{"type":"function","function":{"parameters":{...}}}
        // 提取内层 parameters 作为实际校验 schema；非信封则直接使用 schema。
        Map<String, Object> effective = schema;
        Object fn = schema.get("function");
        if (fn instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fnMap = (Map<String, Object>) fn;
            Object params = fnMap.get("parameters");
            if (params instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> paramMap = (Map<String, Object>) params;
                effective = paramMap;
            }
        }
        if (!"object".equals(String.valueOf(effective.get("type")))) {
            errors.add("schema 顶层 type 必须为 object");
            return errors;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) effective.get("properties");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) effective.get("required");
        if (props != null) {
            for (var entry : props.entrySet()) {
                String key = entry.getKey();
                @SuppressWarnings("unchecked")
                Map<String, Object> prop = (Map<String, Object>) entry.getValue();
                JsonNode value = root.get(key);
                // required 校验
                if (required != null && required.contains(key)) {
                    if (value == null || value.isNull()
                            || (value.isTextual() && value.asText().isEmpty())) {
                        errors.add("缺少必填字段: " + key);
                        continue;
                    }
                }
                // 类型校验（仅当值存在时）
                if (value != null && !value.isNull() && prop != null) {
                    String expected = String.valueOf(prop.get("type"));
                    if (!typeMatches(expected, value)) {
                        errors.add("字段 " + key + " 类型应为 " + expected
                                + "，实际为 " + value.getNodeType());
                    }
                }
            }
        }
        // required 字段存在性兜底（即便 properties 未列出）
        if (required != null) {
            for (String key : required) {
                JsonNode value = root.get(key);
                if (value == null || value.isNull()
                        || (value.isTextual() && value.asText().isEmpty())) {
                    boolean already = errors.stream().anyMatch(e -> e.contains(key));
                    if (!already) {
                        errors.add("缺少必填字段: " + key);
                    }
                }
            }
        }
        return errors;
    }

    private static boolean typeMatches(String expected, JsonNode value) {
        return switch (expected) {
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> true; // 未知 type 宽容放过
        };
    }
}
