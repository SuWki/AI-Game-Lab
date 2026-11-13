package com.aigamelab.llm;

import com.aigamelab.llm.dto.LlmRequest;
import com.aigamelab.llm.dto.LlmResponse;

/**
 * 蓝图 §10 冻结契约。业务代码只依赖此接口 + DTO，禁止 DeepSeek/OpenAI import。
 */
public interface LlmGateway {
    /** 阻塞式；重试耗尽抛 LlmException */
    LlmResponse complete(LlmRequest request);
}
