package com.aigamelab.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T-DB1 领域模型验收测试（表未批准阶段可执行的全部验证）：
 * record 字段与提案 DDL 对应、JSON round-trip、枚举白名单、Evidence 定位锚契约。
 */
@DisplayName("T-DB1 TestPlan/Finding 领域模型（schema 未批准，仅领域层验证）")
class TesterDomainTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("TestPlan：Jackson round-trip 逐字段一致；Scenario 数组可解析")
    void testPlan_roundTrip() throws Exception {
        String scenariosJson = """
                [{"id":"smoke","type":"SMOKE","goal":"存活","seed":42,"repeat":1,
                  "playerProfile":"SCRIPTED","invariants":["HP>=0"],
                  "overrides":{"player.maxHp":1}}]""";
        TestPlan plan = new TestPlan(null, 12L, "v001", TestPlan.STATUS_DRAFT,
                scenariosJson, null);

        String json = mapper.writeValueAsString(plan);
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("taskId").asLong()).isEqualTo(12L);
        assertThat(node.get("configVersion").asText()).isEqualTo("v001");
        assertThat(node.get("status").asText()).isEqualTo("DRAFT");

        // scenariosJson 可解析回 Scenario 数组结构
        TestPlan.Scenario[] scs = mapper.readValue(
                node.get("scenariosJson").asText(), TestPlan.Scenario[].class);
        assertThat(scs).hasSize(1);
        TestPlan.Scenario sc = scs[0];
        assertThat(sc.seed()).isEqualTo(42L);
        assertThat(sc.invariants()).containsExactly("HP>=0");
        assertThat(sc.overrides()).containsEntry("player.maxHp", 1);
    }

    @Test
    @DisplayName("Finding：枚举白名单与技术设计 §6/§7 + DDL Review source 字段一致")
    void finding_enumerations_matchDesign() {
        assertThat(Finding.SEVERITIES).containsExactly(
                "BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO");
        assertThat(Finding.CATEGORIES).containsExactlyInAnyOrder(
                "CRASH", "SOFT_LOCK", "INVARIANT_VIOLATION", "UNEXPECTED_STATE",
                "BALANCE_ANOMALY", "OTHER");
        assertThat(Finding.SOURCES).containsExactly("INVARIANT", "LLM", "MANUAL");
        assertThat(Finding.STATUSES).containsExactly(
                "OPEN", "CONFIRMED", "DISMISSED", "FIXED");
    }

    @Test
    @DisplayName("Evidence：定位锚为 sessionId+runId+toolCallIds，不依赖全局单调 tick")
    void evidence_anchorsOnSessionNotTick() throws Exception {
        Finding.Evidence ev = new Finding.Evidence(
                "gs-abc-123",          // sessionId 必填主锚点
                7L,                    // runId
                List.of(101L, 102L),   // toolCallIds 指向 observation/events 原文
                Map.of("hp", -3, "phase", "BATTLE"),
                512L,                  // tick 仅辅助展示
                42L, Map.of());        // 复现参数

        String json = mapper.writeValueAsString(ev);
        JsonNode node = mapper.readTree(json);

        // 主锚点存在且非空——RESTART 后 tick 重置也不影响唯一定位
        assertThat(node.get("sessionId").asText()).isEqualTo("gs-abc-123");
        assertThat(node.get("runId").asLong()).isEqualTo(7L);
        assertThat(node.get("toolCallIds").get(0).asLong()).isEqualTo(101L);
        assertThat(node.has("tick")).isTrue(); // 存在但仅辅助字段

        Finding.Evidence back = mapper.readValue(json, Finding.Evidence.class);
        assertThat(back).isEqualTo(ev); // round-trip 一致

        // 契约断言：两个不同会话即使 tick 相同也因 sessionId 不同而可区分
        Finding.Evidence otherSession = new Finding.Evidence("gs-other-999",
                ev.runId(), ev.toolCallIds(), ev.observationSnapshot(),
                ev.tick(), ev.seed(), ev.overrides());
        assertThat(otherSession.sessionId()).isNotEqualTo(ev.sessionId());
    }

    @Test
    @DisplayName("Finding record：null 容忍构造（表未批准前内存表示可用）")
    void finding_nullableConstruction() {
        Finding f = new Finding(null, null, null, null, "sc-1", "v001",
                "HIGH", "INVARIANT_VIOLATION", "INVARIANT", "t", "e", "a",
                "steps", "{}", null, Finding.STATUSES.get(0), null);
        assertThat(f.reproductionRate()).isNull();
        assertThat(f.status()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("非法 JSON 的 Scenario 解析抛 Jackson IOException（供 T3 Service 校验路径使用）")
    void scenario_invalidJsonFailsClearly() {
        assertThatIOException()
                .isThrownBy(() -> mapper.readValue("{\"id\":", TestPlan.Scenario.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
