package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.AgentEvent;

/**
 * agent_event 表仓储（蓝图 §7/§16）。持久化事件源，兼作 SSE 补发数据。
 */
public interface AgentEventRepository {

    /** 插入事件并回填自增 id（全局序）与 createdAt。eventId UNIQUE。 */
    AgentEvent insert(AgentEvent event);

    Optional<AgentEvent> findById(long id);

    /** Last-Event-ID 断线补发：该 Run 自 dbId 之后的事件，按 id 升序，上限 1000 条。 */
    List<AgentEvent> findByRunIdAfter(long runId, long afterId, int limit);

    /** 全局最近事件（Dashboard 事件流）。 */
    List<AgentEvent> findRecent(int limit);
}
