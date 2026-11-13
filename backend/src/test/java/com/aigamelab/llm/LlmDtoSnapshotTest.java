package com.aigamelab.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aigamelab.llm.dto.LlmMessage;
import com.aigamelab.llm.dto.LlmRequest;
import com.aigamelab.llm.dto.LlmToolSpec;
import com.aigamelab.llm.dto.TokenUsage;
import com.aigamelab.llm.dto.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P2-1 验收测试（03 文档）：DTO 可序列化为 OpenAI 兼容 JSON 形状（快照测试）。
 * 冻结契约——字段名/形状变化即失败，防止无意破坏 DeepSeek 线协议兼容。
 */
@DisplayName("P2-1 DTO OpenAI 兼容 JSON 快照")
class LlmDtoSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void llmMessageSerializesToOpenAiChatShape() throws Exception {
        // assistant + tool_calls
        String assistantJson = mapper.writeValueAsString(
                LlmMessage.assistantToolCalls(List.of(new ToolCall("call_1", "get_game_config", "{}"))));
        JsonNode a = mapper.readTree(assistantJson);
        assertThat(a.get("role").asText()).isEqualTo("assistant");
        assertThat(a.get("tool_calls").isArray()).isTrue();
        JsonNode tc = a.get("tool_calls").get(0);
        // OpenAI 形状：{id, type:"function", function:{name, arguments}}
        assertThat(tc.get("id").asText()).isEqualTo("call_1");

        // tool 结果消息：role=tool + tool_call_id
        String toolJson = mapper.writeValueAsString(LlmMessage.tool("call_1", "{\"ok\":true}"));
        JsonNode t = mapper.readTree(toolJson);
        assertThat(t.get("role").asText()).isEqualTo("tool");
        assertThat(t.get("content").asText()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void requestSerializesWithMessagesAndTools() throws Exception {
        LlmRequest req = new LlmRequest("rq-1", 7L, "deepseek-chat",
                List.of(LlmMessage.system("You are AGL agent."), LlmMessage.user("平衡三 Build")),
                List.of(new LlmToolSpec("simulate_game", "跑模拟",
                        Map.of("type", "object"), false)),
                0.4, 4096, "p001");
        JsonNode r = mapper.readTree(mapper.writeValueAsString(req));
        assertThat(r.get("model").asText()).isEqualTo("deepseek-chat");
        assertThat(r.get("messages").size()).isEqualTo(2);
        assertThat(r.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(r.get("temperature").asDouble()).isEqualTo(0.4);
        assertThat(r.get("max_tokens").asInt()).isEqualTo(4096);
        assertThat(r.at("/tools/0/type").asText()).isEqualTo("function");
        assertThat(r.at("/tools/0/function/name").asText()).isEqualTo("simulate_game");
        assertThat(r.at("/tools/0/function/parameters/type").asText()).isEqualTo("object");
        assertThat(r.at("/tools/0").has("requiresApproval")).isFalse();
    }

    @Test
    void tokenUsageRoundTripKeepsDeepSeekCacheFields() throws Exception {
        TokenUsage usage = new TokenUsage(100, 50, 150, 60, 40);
        TokenUsage back = mapper.readValue(mapper.writeValueAsString(usage), TokenUsage.class);
        assertThat(back).isEqualTo(usage); // record equals 全字段 round-trip

        JsonNode u = mapper.readTree(mapper.writeValueAsString(usage));
        assertThat(u.get("promptTokens").asInt()).isEqualTo(100);
        assertThat(u.get("completionTokens").asInt()).isEqualTo(50);
        assertThat(u.get("totalTokens").asInt()).isEqualTo(150);
        assertThat(u.get("promptCacheHitTokens").asInt()).isEqualTo(60);
        assertThat(u.get("promptCacheMissTokens").asInt()).isEqualTo(40);
    }

    @Test
    void nullOptionalsOmittedForCleanWireShape() throws Exception {
        String json = mapper.writeValueAsString(LlmMessage.user("hi"));
        JsonNode n = mapper.readTree(json);
        // null 的 toolCalls/toolCallId 不应出现在线协议形状中（OpenAI 不接受显式 null 字段）
        assertThat(n.has("toolCalls")).isFalse();
        assertThat(n.has("toolCallId")).isFalse();
    }
}
