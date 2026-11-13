package com.aigamelab.domain;

import java.util.List;

/**
 * T7-3：版本间 Finding 回归对比汇总结果。
 *
 * @param oldConfigVersion 旧版本标识（回归基线）
 * @param newConfigVersion 新版本标识（被测版本）
 * @param items            逐条回归对比（NEW/FIXED/PERSISTED/CHANGED）
 * @param newCount         NEW 数量
 * @param fixedCount       FIXED 数量
 * @param persistedCount   PERSISTED 数量
 * @param changedCount     CHANGED 数量
 */
public record FindingRegression(
        String oldConfigVersion,
        String newConfigVersion,
        List<FindingRegressionItem> items,
        int newCount,
        int fixedCount,
        int persistedCount,
        int changedCount) {

    public static FindingRegression of(
            String oldConfigVersion, String newConfigVersion,
            List<FindingRegressionItem> items) {
        int newCnt = 0, fixedCnt = 0, persistedCnt = 0, changedCnt = 0;
        for (FindingRegressionItem it : items) {
            switch (it.status()) {
                case FindingRegressionItem.STATUS_NEW -> newCnt++;
                case FindingRegressionItem.STATUS_FIXED -> fixedCnt++;
                case FindingRegressionItem.STATUS_PERSISTED -> persistedCnt++;
                case FindingRegressionItem.STATUS_CHANGED -> changedCnt++;
                default -> throw new IllegalArgumentException("未知 status: " + it.status());
            }
        }
        return new FindingRegression(oldConfigVersion, newConfigVersion, items,
                newCnt, fixedCnt, persistedCnt, changedCnt);
    }
}
