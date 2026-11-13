package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.QaReleaseGate;

/**
 * QA Phase 2：发布门禁仓储（R5 §4 / freeze DB1/DB2/DB4）。
 *
 * <p>接口声明（Impl 见 QaReleaseGateRepositoryImpl）。一棵 QA 树对一个
 * config_version 仅一条 Gate 行，由 QaReleaseGateService 保障 upsert 语义。
 */
public interface QaReleaseGateRepository {

    QaReleaseGate insert(QaReleaseGate g);

    Optional<QaReleaseGate> findById(long id);

    Optional<QaReleaseGate> findByLeadTaskAndConfig(long leadTaskId, String configVersion);

    /** 按可选条件查询 Gate 列表；null 参数忽略该维度。 */
    List<QaReleaseGate> listBy(Long leadTaskId, String configVersion, String status);

    /** 更新自动判定 + 计数留痕（PENDING → 最终判定）。 */
    void decide(long id, String status, String automaticResult, String ruleVersion,
                String leadDecision, String leadDecisionEvidenceJson,
                int isConsistent, int inconclusiveCount,
                int criticalBugs, int highBugs, int mediumBugs, int softLockCount,
                Double crashRate, Double dominantBuildShare, String statsJson);

    /** 人工覆盖（audit DB2）。 */
    void overrideDecision(long id, String finalResult, String reason, String by);

    void updateReportDoc(long id, Long reportDocId);
}
