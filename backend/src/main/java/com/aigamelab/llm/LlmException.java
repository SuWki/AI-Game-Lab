package com.aigamelab.llm;

/**
 * 蓝图 §10/P2-3：LLM 网关异常。
 *
 * @param kind       RETRYABLE（可重试）/ NON_RETRYABLE（直接抛）
 * @param httpStatus 上游 HTTP 状态码；非 HTTP 错误（网络/解析）为 null
 */
public class LlmException extends RuntimeException {

    public enum Kind {
        RETRYABLE, NON_RETRYABLE
    }

    private final Kind kind;
    private final Integer httpStatus;
    /** 解析类失败（坏 JSON/空响应）：NON_RETRYABLE 中唯一允许重试 1 次的子类 */
    private final boolean parseFailure;

    public LlmException(Kind kind, Integer httpStatus, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.parseFailure = false;
    }

    public LlmException(Kind kind, Integer httpStatus, String message, boolean parseFailure) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.parseFailure = parseFailure;
    }

    /** 重试包装（网络超时等无 HTTP 状态场景） */
    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = null;
        this.parseFailure = false;
    }

    public Kind kind() {
        return kind;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public boolean isParseFailure() {
        return parseFailure;
    }
}
