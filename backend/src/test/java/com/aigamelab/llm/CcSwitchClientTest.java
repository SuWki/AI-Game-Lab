package com.aigamelab.llm;

import java.util.List;
import java.util.Map;

import com.aigamelab.llm.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class CcSwitchClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WireMockServer wire = new WireMockServer(options().dynamicPort());
    private CcSwitchClient client;
    private final LlmRequest first = new LlmRequest("trace1", 7L, "proxy-model",
            List.of(LlmMessage.system("rules"), LlmMessage.user("goal")),
            List.of(new LlmToolSpec("inspect", "inspect state", Map.of("type", "object"), true)),
            0.4, 160, "p1");

    @BeforeEach void start() {
        wire.start();
        client = new CcSwitchClient(mapper, wire.port(), 2, "proxy-model");
    }
    @AfterEach void stop() { wire.stop(); }

    @Test void claudeProfileWinsEvenWhenDirectKeyIsPresent() {
        var context = new ApplicationContextRunner().withBean(ObjectMapper.class, () -> mapper)
                .withUserConfiguration(CcSwitchClient.class, DeepSeekClient.class)
                .withPropertyValues("spring.profiles.active=cc-switch", "llm.gateway.model=proxy-model");
        context.run(ctx -> assertThat(ctx.getBean(LlmGateway.class)).isInstanceOf(CcSwitchClient.class));
        context.withPropertyValues("llm.gateway.api-key=unused-test-value")
                .run(ctx -> assertThat(ctx.getBean(LlmGateway.class)).isInstanceOf(CcSwitchClient.class));
    }

    @Test void healthRecoversWithoutAModelRequestOrFollowingRedirects() {
        wire.stubFor(get("/health").willReturn(temporaryRedirect("/should-not-follow")));
        assertThat(client.proxyHealthy()).isFalse();
        wire.stubFor(get("/health").willReturn(okJson("{\"status\":\"healthy\"}")));
        assertThat(client.proxyHealthy()).isTrue();
        wire.verify(2, getRequestedFor(urlEqualTo("/health")));
        assertThat(wire.getAllServeEvents()).hasSize(2);
    }

    @Test void translatesToolRoundTripAndCountsAllInputTokens() throws Exception {
        wire.stubFor(post("/v1/messages")
                .withHeader("x-api-key", equalTo("PROXY_MANAGED"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withRequestBody(matchingJsonPath("$.tools[0].input_schema.type", equalTo("object")))
                .willReturn(okJson("""
                {"type":"message","model":"upstream","stop_reason":"tool_use","content":[
                 {"type":"text","text":"checking"},
                 {"type":"tool_use","id":"a","name":"inspect","input":{"seed":42}},
                 {"type":"tool_use","id":"b","name":"inspect","input":{}}],
                 "usage":{"input_tokens":7,"cache_read_input_tokens":8,"cache_creation_input_tokens":3,"output_tokens":5}}
                """)));
        var reply = client.complete(first);
        assertThat(reply.finishReason()).isEqualTo("tool_calls");
        assertThat(reply.rawModelId()).isEqualTo("upstream");
        assertThat(reply.usage()).isEqualTo(TokenUsage.of(18, 5));
        assertThat(reply.toolCalls()).extracting(ToolCall::id).containsExactly("a", "b");
        var history = List.of(first.messages().get(0), first.messages().get(1),
                new LlmMessage("assistant", reply.content(), reply.toolCalls(), null),
                LlmMessage.tool("a", "42"), LlmMessage.tool("b", "done"));
        wire.stubFor(post("/v1/messages")
                .withRequestBody(matchingJsonPath("$.messages[2].content[1].tool_use_id", equalTo("b")))
                .willReturn(okJson("""
                    {"type":"message","model":"upstream","stop_reason":"end_turn","content":[{"type":"text","text":"done"}]}
                    """)));
        var done = client.complete(new LlmRequest("trace2", 7L, first.model(), history, first.tools(), 0.4, 160, "p1"));
        assertThat(done.finishReason()).isEqualTo("stop");
        assertThat(done.content()).isEqualTo("done");
        assertThat(done.usage()).isNull();
        var sent = wire.getAllServeEvents().get(0).getRequest();
        assertThat(sent.getHeader("Authorization")).isNull();
        assertThat(sent.getBodyAsString()).doesNotContain("trace2", "requiresApproval", "temperature");
        var body = mapper.readTree(sent.getBodyAsString());
        assertThat(body.at("/system/0/text").asText()).isEqualTo("rules");
        assertThat(body.at("/model").asText()).isEqualTo(first.model());
        assertThat(body.at("/max_tokens").asInt()).isEqualTo(160);
        assertThat(body.at("/messages/1/content/1/input/seed").asInt()).isEqualTo(42);
        assertThat(body.at("/messages/2/content/0/tool_use_id").asText()).isEqualTo("a");
    }

    @Test void truncatedToolIsNeverDispatchedAndRefusalIsNotSuccess() {
        var protocol = new ClaudeMessages(mapper);
        var body = mapper.createObjectNode().put("type", "message").put("stop_reason", "max_tokens");
        body.putArray("content").addObject().put("type", "tool_use").put("id", "partial");
        assertThat(protocol.response("id", body, 0).finishReason()).isEqualTo("length");
        assertThat(protocol.response("id", body, 0).toolCalls()).isNull();
        assertThat(protocol.response("id", body.put("stop_reason", "refusal"), 0).finishReason()).isEqualTo("refusal");
        assertThatThrownBy(() -> protocol.response("id", body.put("stop_reason", "tool_use"), 0))
                .isInstanceOf(LlmException.class);
    }

    @Test void upstreamFailureHasOneAttemptAndOmitsProviderBody() {
        wire.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(503).withBody("private relay details")));
        assertThatThrownBy(() -> client.complete(first)).isInstanceOf(LlmException.class)
                .hasMessageContaining("503").hasMessageNotContaining("private");
        wire.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    }
}
