package com.aigamelab.common;

/**
 * 蓝图 §8（02 技术设计 line 311）定义的极简六码错误体系。
 * 语义按现有文档引用锚定：
 *  - 40901：状态机/资源冲突（P1-1 Run 状态机违规）
 *  - 50201：上游 LLM 网关异常（P8-4 异常演练）
 */
public final class ErrorCodes {

    /** 参数校验失败 / 请求非法 */
    public static final int INVALID_ARGUMENT = 40001;
    /** 资源不存在 */
    public static final int NOT_FOUND = 40401;
    /** 状态机违规 / 资源冲突（含 UNIQUE 幂等复用场景） */
    public static final int CONFLICT = 40901;
    /** 触发限流 */
    public static final int RATE_LIMITED = 42901;
    /** 上游 LLM 网关异常 */
    public static final int UPSTREAM_ERROR = 50201;
    /** 未知内部错误（兜底） */
    public static final int INTERNAL_ERROR = 50000;

    private ErrorCodes() {
    }
}
