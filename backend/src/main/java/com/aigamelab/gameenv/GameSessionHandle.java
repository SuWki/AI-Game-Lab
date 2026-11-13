package com.aigamelab.gameenv;

import java.time.LocalDateTime;

/**
 * T1-1：会话句柄——Adapter 分配、Tool 层映射到 runId 的会话标识。
 *
 * @param sessionId 全局唯一会话 id
 * @param adapterId 产生该会话的 Adapter id
 * @param startedAt 启动时间
 */
public record GameSessionHandle(String sessionId, String adapterId, LocalDateTime startedAt) {
}
