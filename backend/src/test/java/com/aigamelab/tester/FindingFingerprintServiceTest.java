package com.aigamelab.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aigamelab.domain.Finding;
import com.aigamelab.domain.FindingIdentity;

/**
 * T7-2 验收：Finding Identity 与版本归一化（fingerprint）。
 *
 * 验证 fingerprint 的稳定性与归一化规则：
 *  - Case 1：同一逻辑问题在不同 Run 中上报 → 相同 fingerprint；
 *  - Case 2：同一逻辑问题跨不同 configVersion 上报 → 相同 fingerprint；
 *  - Case 3：不同逻辑问题 → 不同 fingerprint；
 *  - Case 4：标题归一化（大小写/空格/多空格合并）→ 相同 fingerprint；
 *  - Case 5：必填字段为空 → IllegalArgumentException；
 *  - Case 6：normalizeTitle 幂等性。
 *
 * 纯 Service 层逻辑，无需数据库，@SpringBootTest 可选（此处用单元测试验证纯函数）。
 */
@DisplayName("T7-2 Finding Identity fingerprint 归一化")
class FindingFingerprintServiceTest {

    private FindingFingerprintService service;

    @BeforeEach
    void setUp() {
        service = new FindingFingerprintService();
    }

    private Finding makeFinding(String scenarioId, String category, String title) {
        return new Finding(
                null, null, null, null,
                scenarioId, "v001", "HIGH", category,
                "LLM", title, "expect", "actual",
                "seed=42", "{}", 0.5, "OPEN", null);
    }

    // ---- Case 1：同一逻辑问题不同 Run → 相同 fingerprint ----

    @Test
    @DisplayName("Case 1 同逻辑问题不同 Run → 相同 fingerprint")
    void sameIssueDifferentRunSameFingerprint() {
        Finding f1 = new Finding(
                1L, 100L, null, null,
                "sc1", "v001", "HIGH", "BALANCE_ANOMALY",
                "LLM", "HP 越界", "expect", "actual",
                "seed=42", "{}", 0.5, "OPEN", null);
        Finding f2 = new Finding(
                2L, 200L, null, null,
                "sc1", "v001", "HIGH", "BALANCE_ANOMALY",
                "LLM", "HP 越界", "expect", "actual",
                "seed=99", "{}", 0.3, "OPEN", null);

        FindingIdentity id1 = service.fingerprint(f1);
        FindingIdentity id2 = service.fingerprint(f2);

        assertThat(id1).isEqualTo(id2);
        assertThat(id1.scenarioId()).isEqualTo("sc1");
        assertThat(id1.category()).isEqualTo("BALANCE_ANOMALY");
        assertThat(id1.normalizedTitle()).isEqualTo("hp 越界");
    }

    // ---- Case 2：同一逻辑问题跨 configVersion → 相同 fingerprint ----

    @Test
    @DisplayName("Case 2 同逻辑问题跨 configVersion → 相同 fingerprint")
    void sameIssueDifferentConfigVersionSameFingerprint() {
        Finding fV1 = makeFinding("sc1", "CRASH", "NullPointer crash on wave 3");
        Finding fV2 = new Finding(
                null, null, null, null,
                "sc1", "v002", "BLOCKER", "CRASH",
                "LLM", "NullPointer crash on wave 3", "expect", "actual",
                "seed=42", "{}", 1.0, "OPEN", null);

        FindingIdentity idV1 = service.fingerprint(fV1);
        FindingIdentity idV2 = service.fingerprint(fV2);

        // configVersion 不参与 fingerprint（跨版本比较靠此）
        assertThat(idV1).isEqualTo(idV2);
        assertThat(idV1.normalizedTitle()).isEqualTo("nullpointer crash on wave 3");
    }

    // ---- Case 3：不同逻辑问题 → 不同 fingerprint ----

    @Test
    @DisplayName("Case 3 不同逻辑问题 → 不同 fingerprint")
    void differentIssuesDifferentFingerprint() {
        Finding fA = makeFinding("sc1", "CRASH", "NullPointer crash");
        Finding fB = makeFinding("sc1", "BALANCE_ANOMALY", "NullPointer crash");
        Finding fC = makeFinding("sc2", "CRASH", "NullPointer crash");
        Finding fD = makeFinding("sc1", "CRASH", "SOFT lock on restart");

        FindingIdentity idA = service.fingerprint(fA);
        FindingIdentity idB = service.fingerprint(fB);
        FindingIdentity idC = service.fingerprint(fC);
        FindingIdentity idD = service.fingerprint(fD);

        // 每个维度变化都产生不同 fingerprint
        assertThat(idA).isNotEqualTo(idB); // 不同 category
        assertThat(idA).isNotEqualTo(idC); // 不同 scenarioId
        assertThat(idA).isNotEqualTo(idD); // 不同 title
    }

    // ---- Case 4：标题归一化（大小写/空格）→ 相同 fingerprint ----

    @Test
    @DisplayName("Case 4 标题归一化：大小写/空格变体 → 相同 fingerprint")
    void titleNormalizationCaseAndWhitespace() {
        Finding f1 = makeFinding("sc1", "HIGH", "HP 越界");
        Finding f2 = makeFinding("sc1", "HIGH", "  HP   越界  ");
        Finding f3 = makeFinding("sc1", "HIGH", "hp 越界");
        Finding f4 = makeFinding("sc1", "HIGH", "Hp\n\t越界");

        FindingIdentity id1 = service.fingerprint(f1);
        FindingIdentity id2 = service.fingerprint(f2);
        FindingIdentity id3 = service.fingerprint(f3);
        FindingIdentity id4 = service.fingerprint(f4);

        assertThat(id1).isEqualTo(id2);
        assertThat(id2).isEqualTo(id3);
        assertThat(id3).isEqualTo(id4);
        assertThat(id1.normalizedTitle()).isEqualTo("hp 越界");
    }

    // ---- Case 5：必填字段为空 → IllegalArgumentException ----

    @Test
    @DisplayName("Case 5 null finding → IllegalArgumentException")
    void nullFindingThrows() {
        assertThatThrownBy(() -> service.fingerprint(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Case 5 空 scenarioId → IllegalArgumentException")
    void blankScenarioIdThrows() {
        Finding f = makeFinding("", "CRASH", "title");
        assertThatThrownBy(() -> service.fingerprint(f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Case 5 null category → IllegalArgumentException")
    void nullCategoryThrows() {
        Finding f = new Finding(
                null, null, null, null,
                "sc1", "v001", "HIGH", null,
                "LLM", "title", "e", "a",
                "r", "{}", 0.5, "OPEN", null);
        assertThatThrownBy(() -> service.fingerprint(f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Case 6：normalizeTitle 幂等性 ----

    @Test
    @DisplayName("Case 6 normalizeTitle 幂等：两次归一化结果相同")
    void normalizeTitleIdempotent() {
        String raw = "  HP   越界  ";
        String once = FindingFingerprintService.normalizeTitle(raw);
        String twice = FindingFingerprintService.normalizeTitle(once);

        assertThat(once).isEqualTo(twice);
        assertThat(once).isEqualTo("hp 越界");
    }

    // ---- Case 7：FindingIdentity PLACEHOLDER 占位值 ----

    @Test
    @DisplayName("Case 7 FindingIdentity.PLACEHOLDER 各字段为空串")
    void placeholderFieldsAreEmpty() {
        assertThat(FindingIdentity.PLACEHOLDER.scenarioId()).isEmpty();
        assertThat(FindingIdentity.PLACEHOLDER.category()).isEmpty();
        assertThat(FindingIdentity.PLACEHOLDER.normalizedTitle()).isEmpty();
    }
}
