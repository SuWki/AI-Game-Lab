package com.aigamelab.tester;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.aigamelab.domain.Finding;
import com.aigamelab.domain.FindingIdentity;
import com.aigamelab.domain.FindingRegression;
import com.aigamelab.domain.FindingRegressionItem;

/**
 * T7-3：版本间 Finding 回归对比（核心 diff 算法）。
 *
 * 对比逻辑：以 {@link FindingFingerprintService} 产出的 {@link FindingIdentity}
 * （scenarioId + category + normalizedTitle）为逻辑键，比较 oldConfigVersion
 * 与 newConfigVersion 两组 Finding 集合：
 *
 * <ul>
 *   <li><b>NEW</b>：new 中存在，old 中无 → 升级引入的新缺陷；</li>
 *   <li><b>FIXED</b>：old 中存在，new 中无 → 升级修复的旧缺陷；</li>
 *   <li><b>PERSISTED</b>：两组都存在，且 severity 不变 → 缺陷持续存在；</li>
 *   <li><b>CHANGED</b>：两组都存在，但 severity 变化 → 缺陷依旧但严重程度变化。</li>
 * </ul>
 *
 * <p>设计约束：
 * - Service 层纯内存计算，不修改 DB Schema；
 * - 若同逻辑键在 old/new 内出现多次（Finding 允许重复上报，T-DB1 §6），
 *   取该组 severity 最高的一条参与对比（简化策略），其余忽略；
 * - 同时依赖 {@link FindingService#findByFilter} 查询两组 Finding 集合；
 * - 不直接修改冻结的 FindingService / FindingRepository。
 *
 * @see FindingFingerprintService
 * @see FindingService#findByFilter(String, String, Long, Long)
 */
@Service
public class FindingRegressionService {

    private final FindingService findingService;
    private final FindingFingerprintService fingerprintService;

    public FindingRegressionService(FindingService findingService,
            FindingFingerprintService fingerprintService) {
        this.findingService = findingService;
        this.fingerprintService = fingerprintService;
    }

    /**
     * 对比两个 configVersion 之间的 Finding 集合。
     *
     * @param oldConfigVersion 旧版本（基线）
     * @param newConfigVersion 新版本（被测）
     * @return 完整回归对比结果（含计数与逐条明细）
     */
    public FindingRegression diff(String oldConfigVersion, String newConfigVersion) {
        List<Finding> oldFindings = findingService.findByFilter(oldConfigVersion, null, null, null);
        List<Finding> newFindings = findingService.findByFilter(newConfigVersion, null, null, null);

        Map<FindingIdentity, Finding> oldMap = pickHighestSeverityByFingerprint(oldFindings);
        Map<FindingIdentity, Finding> newMap = pickHighestSeverityByFingerprint(newFindings);

        List<FindingRegressionItem> items = new ArrayList<>();

        // NEW：new 有、old 无
        for (Map.Entry<FindingIdentity, Finding> e : newMap.entrySet()) {
            if (!oldMap.containsKey(e.getKey())) {
                Finding nf = e.getValue();
                items.add(new FindingRegressionItem(
                        e.getKey(),
                        FindingRegressionItem.STATUS_NEW,
                        null,
                        nf.severity(),
                        nf.title(),
                        nf.scenarioId(),
                        nf.category()));
            }
        }

        // FIXED / PERSISTED / CHANGED：遍历 old
        for (Map.Entry<FindingIdentity, Finding> e : oldMap.entrySet()) {
            Finding of = e.getValue();
            Finding nf = newMap.get(e.getKey());
            if (nf == null) {
                // 旧有、新无 → 已修复
                items.add(new FindingRegressionItem(
                        e.getKey(),
                        FindingRegressionItem.STATUS_FIXED,
                        of.severity(),
                        null,
                        of.title(),
                        of.scenarioId(),
                        of.category()));
            } else if (of.severity().equals(nf.severity())) {
                // 两组都有、severity 不变 → 持续
                items.add(new FindingRegressionItem(
                        e.getKey(),
                        FindingRegressionItem.STATUS_PERSISTED,
                        of.severity(),
                        nf.severity(),
                        nf.title(),
                        nf.scenarioId(),
                        nf.category()));
            } else {
                // 两组都有、severity 变 → 变化
                items.add(new FindingRegressionItem(
                        e.getKey(),
                        FindingRegressionItem.STATUS_CHANGED,
                        of.severity(),
                        nf.severity(),
                        nf.title(),
                        nf.scenarioId(),
                        nf.category()));
            }
        }

        // 稳定排序：PERSISTED < CHANGED < FIXED < NEW（便于阅读），同组内按 scenarioId+title
        items.sort(Comparator.comparingInt(this::statusRank)
                .thenComparing(FindingRegressionItem::scenarioId)
                .thenComparing(FindingRegressionItem::title));

        return FindingRegression.of(oldConfigVersion, newConfigVersion, items);
    }

    private int statusRank(FindingRegressionItem it) {
        return switch (it.status()) {
            case FindingRegressionItem.STATUS_PERSISTED -> 0;
            case FindingRegressionItem.STATUS_CHANGED -> 1;
            case FindingRegressionItem.STATUS_FIXED -> 2;
            case FindingRegressionItem.STATUS_NEW -> 3;
            default -> 4;
        };
    }

    /**
     * 将 Finding 列表按 fingerprint 聚合，每个逻辑键只保留 severity 最高的一条。
     * severity 排序：BLOCKER > HIGH > MEDIUM > LOW > INFO（见 Finding.SEVERITIES）。
     */
    private Map<FindingIdentity, Finding> pickHighestSeverityByFingerprint(List<Finding> findings) {
        Map<FindingIdentity, Finding> map = new LinkedHashMap<>();
        for (Finding f : findings) {
            FindingIdentity id = fingerprintService.fingerprint(f);
            // severity 排序：BLOCKER(0) > HIGH(1) > MEDIUM(2) > LOW(3) > INFO(4)
            // 取 index 更小（更严重）的一条；candidate 比 existing 更严重则替换。
            map.merge(id, f, (existing, candidate) ->
                    severityRank(candidate.severity()) < severityRank(existing.severity())
                            ? candidate : existing);
        }
        return map;
    }

    private int severityRank(String severity) {
        return Optional.ofNullable(severity)
                .map(s -> {
                    int idx = Finding.SEVERITIES.indexOf(s);
                    return idx < 0 ? -1 : idx;
                })
                .orElse(-1);
    }
}
