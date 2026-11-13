package com.aigamelab.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 蓝图 §10 冻结契约：LLM 工具调用（Java 形状 id/name/argumentsJson）。
 * 序列化为 OpenAI function-calling 形状：
 * {id, type:"function", function:{name, arguments}}；
 * 响应方向的 DeepSeek tool_calls 由 DeepSeekClient 内部 DTO 承接后转换（蓝图 §10 解耦原则）。
 * arguments 字符串透传不解析。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCall(
        String id,
        @JsonIgnore String name,
        @JsonIgnore String argumentsJson) {

    /** 线协议嵌套体 {name, arguments}（仅序列化方向使用） */
    record FunctionBody(
            @JsonProperty("name") String name,
            @JsonProperty("arguments") String arguments) {
    }

    @JsonProperty("type")
    public String wireType() {
        return "function";
    }

    @JsonProperty("function")
    public FunctionBody wireFunction() {
        return new FunctionBody(name, argumentsJson);
    }
}
