package com.aigamelab.domain;

/**
 * T7-3：单条 Finding 回归对比结果。
 *
 * 对比逻辑：以 {@link FindingIdentity}（scenarioId + category + normalizedTitle）为逻辑键，
 * 比较 oldConfigVersion 与 newConfigVersion 两组 Finding 集合。
 *
 * @param fingerprint 逻辑标识（归一化后的 FindingIdentity）
 * @param status      NEW / FIXED / PERSISTED / CHANGED
 * @param oldSeverity 旧版本 severity（FIXED / PERSISTED / CHANGED 时有值；NEW 时为 null）
 * @param newSeverity 新版本 severity（NEW / PERSISTED / CHANGED 时有值；FIXED 时为 null）
 * @param title       标题原文（来自新版本，FIXED 时取旧版本标题）
 * @param scenarioId  场景标识
 * @param category    缺陷分类
 */
public record FindingRegressionItem(
        FindingIdentity fingerprint,
        String status,
        String oldSeverity,
        String newSeverity,
        String title,
        String scenarioId,
        String category) {

    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_FIXED = "FIXED";
    public static final String STATUS_PERSISTED = "PERSISTED";
    public static final String STATUS_CHANGED = "CHANGED";
}
