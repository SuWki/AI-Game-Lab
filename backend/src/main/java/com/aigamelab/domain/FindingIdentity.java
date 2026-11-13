package com.aigamelab.domain;

/**
 * T7-2：Finding 逻辑标识（fingerprint）—— 不修改 DB Schema 的跨版本归一化键。
 *
 * 职责：跨 configVersion / Run / TestPlan 比较"是否为同一逻辑缺陷"的唯一标识。
 * 标准化规则：scenarioId + category + normalized title（lowercase + trim + whitespace collapse）。
 *
 * <p>设计约束：
 * - 纯值对象，无 ID/无 DB 映射，仅 Service 层内存使用；
 * - 不替代 Finding.id（后者是物理主键）；
 * - Finding 允许同场景多次上报同类缺陷（T-DB1 DDL Review §6），
 *   regression diff 需要基于此逻辑键去重后再做 NEW/FIXED/PERSISTED/CHANGED 分类。
 *
 * @param scenarioId    场景标识（Finding.scenarioId，必填）
 * @param category      缺陷分类（Finding.category，必填）
 * @param normalizedTitle 标准化后的标题（lowercase + trim + 多空格合并）
 */
public record FindingIdentity(
        String scenarioId,
        String category,
        String normalizedTitle) {

    /** 未归一化时的哨兵值（FingerprintService 不会产出此值，仅用于测试/占位）。 */
    public static final FindingIdentity PLACEHOLDER =
            new FindingIdentity("", "", "");
}
