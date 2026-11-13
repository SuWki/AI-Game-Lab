package com.aigamelab.domain;

import java.time.LocalDateTime;

/**
 * 用户目标（task 表）。
 * 字段与蓝图 §9.1 一致；createdAt 由 DB 默认值生成，insert 后回读。
 */
public record Task(
        Long id,
        String title,
        String goal,
        String constraintsJson,
        int maxSteps,
        boolean approvalRequired,
        String status,
        LocalDateTime createdAt) {
}
