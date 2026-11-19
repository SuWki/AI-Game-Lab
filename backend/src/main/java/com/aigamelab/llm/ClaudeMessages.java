package com.aigamelab.llm;

import java.util.ArrayList;
import java.util.List;

import com.aigamelab.llm.dto.LlmRequest;
import com.aigamelab.llm.dto.LlmResponse;
import com.aigamelab.llm.dto.TokenUsage;
import com.aigamelab.llm.dto.ToolCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class ClaudeMessages {
    private final ObjectMapper mapper;

    ClaudeMessages(ObjectMapper mapper) { this.mapper = mapper; }

    ObjectNode request(LlmRequest request) {
        var body = mapper.createObjectNode().put("model", request.model())
                .put("max_tokens", request.maxTokens()).put("stream", false);
        // Use the proxy model's default sampling; some Claude models reject temperature.
        var system = mapper.createArrayNode();
        var messages = body.putArray("messages");
        ArrayNode results = null;
        for (var message : request.messages()) {
            if ("tool".equals(message.role())) {
                if (results == null) results = messages.addObject().put("role", "user").putArray("content");
                results.addObject().put("type", "tool_result").put("tool_use_id", message.toolCallId())
                        .put("content", message.content());
                continue;
            }
            results = null;
            if ("system".equals(message.role())) {
                system.addObject().put("type", "text").put("text", message.content());
                continue;
            }
            var blocks = messages.addObject().put("role", message.role()).putArray("content");
            if (message.content() != null && !message.content().isEmpty()) {
                blocks.addObject().put("type", "text").put("text", message.content());
            }
            if (message.toolCalls() != null) {
                for (var call : message.toolCalls()) {
                    var tool = blocks.addObject().put("type", "tool_use").put("id", call.id()).put("name", call.name());
                    try {
                        var input = mapper.readTree(call.argumentsJson());
                        if (input == null || !input.isObject()) throw invalid("工具参数应为 JSON 对象");
                        tool.set("input", input);
                    } catch (JsonProcessingException e) {
                        throw invalid("无法回放工具参数");
                    }
                }
            }
        }
        if (!system.isEmpty()) body.set("system", system);
        if (request.tools() != null && !request.tools().isEmpty()) {
            var tools = body.putArray("tools");
            for (var spec : request.tools()) {
                tools.addObject().put("name", spec.name()).put("description", spec.description() == null ? spec.name() : spec.description())
                        .set("input_schema", mapper.valueToTree(spec.parameters()));
            }
        }
        return body;
    }

    LlmResponse response(String requestId, JsonNode body, long latency) {
        if (body == null || !"message".equals(body.path("type").asText()) || !body.path("content").isArray()) {
            throw invalid("响应缺少 message 或 content");
        }
        var text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        String reason = body.path("stop_reason").asText();
        for (var block : body.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                if (!block.path("text").isTextual()) throw invalid("文本块缺少 text");
                text.append(block.get("text").textValue());
            }
            if ("tool_use".equals(block.path("type").asText()) && ("end_turn".equals(reason) || "stop_sequence".equals(reason))) {
                throw invalid("结束响应含未结算的工具调用");
            }
            if ("tool_use".equals(reason) && "tool_use".equals(block.path("type").asText())) {
                if (!block.path("id").isTextual() || block.get("id").textValue().isBlank()
                        || !block.path("name").isTextual() || block.get("name").textValue().isBlank()
                        || !block.path("input").isObject()) throw invalid("工具调用缺少 ID、名称或参数对象");
                calls.add(new ToolCall(block.get("id").textValue(), block.get("name").textValue(), block.get("input").toString()));
            }
        }
        if ("tool_use".equals(reason) && calls.isEmpty()) throw invalid("tool_use 响应缺少工具调用");
        String finish = switch (reason) {
            case "tool_use" -> "tool_calls";
            case "end_turn", "stop_sequence" -> "stop";
            case "max_tokens", "model_context_window_exceeded" -> "length";
            case "refusal" -> "refusal";
            default -> "unsupported";
        };
        return new LlmResponse(requestId, finish, text.toString(), calls.isEmpty() ? null : calls,
                usage(body.path("usage")), latency, body.path("model").asText(null));
    }

    private static TokenUsage usage(JsonNode usage) {
        if (!usage.hasNonNull("input_tokens") || !usage.hasNonNull("output_tokens")) return null;
        int input = tokens(usage, "input_tokens");
        int output = tokens(usage, "output_tokens");
        int cached = tokens(usage, "cache_read_input_tokens");
        int created = tokens(usage, "cache_creation_input_tokens");
        long prompt = (long) input + cached + created;
        if (prompt + output > Integer.MAX_VALUE) throw invalid("token 用量超出支持范围");
        return TokenUsage.of((int) prompt, output);
    }

    private static int tokens(JsonNode usage, String name) {
        var n = usage.get(name);
        if (n == null) return 0;
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < 0) throw invalid("token 用量无效");
        return n.intValue();
    }

    private static LlmException invalid(String message) {
        return new LlmException(LlmException.Kind.NON_RETRYABLE, null, "CC Switch Messages 协议错误，" + message);
    }
}
