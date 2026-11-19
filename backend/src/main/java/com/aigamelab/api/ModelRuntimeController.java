package com.aigamelab.api;

import com.aigamelab.common.ApiResp;
import com.aigamelab.llm.CcSwitchClient;
import com.aigamelab.llm.DeepSeekClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports the local runtime configuration without making a supplier request. */
@RestController
public class ModelRuntimeController {
    public record Readiness(boolean gatewayConfigured, String model, boolean retryEnabled,
                            int tokenBudget, int maxResponseTokens, long timeoutMs, long httpTimeoutSeconds,
                            String gatewayMode, Integer proxyPort, Boolean proxyHealthy) {}

    private final ObjectProvider<DeepSeekClient> gateway;
    private final ObjectProvider<CcSwitchClient> proxy;
    private final String model;
    private final int tokenBudget;
    private final int maxResponseTokens;
    private final long timeoutMs;

    public ModelRuntimeController(ObjectProvider<DeepSeekClient> gateway, ObjectProvider<CcSwitchClient> proxy,
            @Value("${llm.gateway.model:deepseek-chat}") String model,
            @Value("${agent.runtime.token-budget:100000}") int tokenBudget,
            @Value("${agent.runtime.max-tokens:4096}") int maxResponseTokens,
            @Value("${agent.runtime.timeout-ms:900000}") long timeoutMs) {
        this.gateway = gateway;
        this.proxy = proxy;
        this.model = model;
        this.tokenBudget = tokenBudget;
        this.maxResponseTokens = maxResponseTokens;
        this.timeoutMs = timeoutMs;
    }

    @GetMapping("/api/model-runtime")
    public ApiResp<Readiness> readiness() {
        var local = proxy.getIfAvailable();
        if (local != null) return ApiResp.ok(new Readiness(true, model, false, tokenBudget, maxResponseTokens,
                timeoutMs, local.timeoutSeconds(), "cc-switch-claude", local.port(), local.proxyHealthy()));
        var client = gateway.getIfAvailable();
        return ApiResp.ok(new Readiness(client != null, model, client != null && client.retryEnabled(),
                tokenBudget, maxResponseTokens, timeoutMs, client == null ? 0 : client.readTimeout().toSeconds(),
                "direct", null, null));
    }
}
