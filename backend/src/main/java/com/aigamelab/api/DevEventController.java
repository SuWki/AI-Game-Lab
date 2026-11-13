package com.aigamelab.api;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aigamelab.event.AgentEventType;
import com.aigamelab.event.PersistingAgentEventPublisher;

/**
 * P1-6b：SSE 独立验证端点（仅 dev profile）。
 * 不启动 Agent 即可手动验证：连接建立、事件到达、断线重连。
 */
@RestController
@Profile("dev")
public class DevEventController {

    private final PersistingAgentEventPublisher publisher;

    public DevEventController(PersistingAgentEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** POST /sse/dev/emit?type=STEP_STARTED&runId=1&payload={} → 走完整落库+推送链路 */
    @PostMapping("/sse/dev/emit")
    public Map<String, Object> emit(@RequestParam String type,
            @RequestParam(required = false) Long runId,
            @RequestParam(defaultValue = "{}") String payload) {
        if (!AgentEventType.isValid(type)) {
            throw com.aigamelab.common.BusinessException.notFound("未知事件类型: " + type);
        }
        if (runId != null) {
            publisher.publish(type, runId, null, payload);
        } else {
            // 无 run 维度：借 runId=null 走 global-only 分支
            publisher.publish(type, null, null, payload);
        }
        return Map.of("emitted", type);
    }
}
