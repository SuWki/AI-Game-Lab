package com.aigamelab.tester;

import org.springframework.stereotype.Service;

import com.aigamelab.domain.Finding;
import com.aigamelab.domain.FindingIdentity;

/**
 * T7-2：Finding 逻辑标识计算（fingerprint service）。
 *
 * 职责：将 Finding 归一化为 {@link FindingIdentity}——跨 configVersion / Run /
 * TestPlan 比较"是否为同一逻辑缺陷"的唯一逻辑键。
 *
 * <p>标准化规则（设计文档无既有定义，本 Sprint 新增）：
 * <ol>
 *   <li>scenarioId — 原样保留（场景标识，不允许变体）；</li>
 *   <li>category — 原样保留（已由 FindingService 校验白名单）；</li>
 *   <li>normalizedTitle = title.trim().toLowerCase().replaceAll("\\\\s+", " ")；</li>
 * </ol>
 *
 * <p>设计约束：
 * - 纯函数，无副作用，无线程安全问题；
 * - 不修改 Finding 表 Schema（FindingIdentity 仅在 Service 层内存存在）；
 * - Finding 允许同场景多次上报同类缺陷（T-DB1 DDL Review §6），
 *   regression diff 在 Service 层使用此逻辑键做去重/匹配。
 */
@Service
public class FindingFingerprintService {

    /**
     * 计算 Finding 的逻辑标识（fingerprint）。
     *
     * @param finding 上报的 Finding（必含 scenarioId / category / title，已由 FindingService 校验）
     * @return 归一化的逻辑标识（FindingIdentity）
     * @throws IllegalArgumentException finding 为 null 或必填字段为空（防御性，正常流程不会触发）
     */
    public FindingIdentity fingerprint(Finding finding) {
        if (finding == null) {
            throw new IllegalArgumentException("finding 不允许为 null");
        }
        String scenarioId = requireNonBlank(finding.scenarioId(), "scenarioId");
        String category = requireNonBlank(finding.category(), "category");
        String title = requireNonBlank(finding.title(), "title");

        String normalizedTitle = normalizeTitle(title);
        return new FindingIdentity(scenarioId, category, normalizedTitle);
    }

    /**
     * 标准化标题：trim → lowercase → 多空格合并为单空格。
     * 同一逻辑问题的不同表述变体（大小写/前后空格/多空格）将被归一化为同一键。
     *
     * <p>示例：
     * <pre>
     *   "  HP 越界  "         → "hp 越界"
     *   "HP   越界"           → "hp 越界"   （多空格合并）
     *   "hp 越界"             → "hp 越界"   （幂等）
     * </pre>
     */
    public static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.trim()
                .toLowerCase()
                .replaceAll("\\s+", " ");
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不允许为空（Fingerprint 计算前置条件）");
        }
        return value;
    }
}
