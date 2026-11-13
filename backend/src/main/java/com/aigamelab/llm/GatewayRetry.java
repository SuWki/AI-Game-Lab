package com.aigamelab.llm;

import java.util.function.Supplier;

/**
 * 蓝图 §10 / P2-3：可重试错误指数退避包装器。
 *
 * 策略（冻结）：
 * - RETRYABLE：网络超时 ×3、429 ×3（优先 Retry-After）、5xx ×2；退避 1s→2s→4s + jitter；
 *   优先遵循上游 Retry-After 头。
 * - NON_RETRYABLE：400/401/403、解析坏 JSON（限重试 1 次）直接抛。
 *
 * 测试场景把 baseBackoffMs 调小为 ms 级，避免真实等待。
 */
public class GatewayRetry {

    /** 网络超时/未知 RETRYABLE 最大尝试次数 */
    static final int MAX_NETWORK_ATTEMPTS = 3;
    /** 5xx 最大尝试次数 */
    static final int MAX_5XX_ATTEMPTS = 2;
    /** 坏 JSON（NON_RETRYABLE 解析类）允许的重试次数 */
    static final int PARSE_RETRY_BUDGET = 1;

    private final long baseBackoffMs;
    private final double jitterFactor;
    private final Sleeper sleeper;
    private final RetryAfterReader retryAfterReader;

    /** 可注入时钟行为（测试零等待） */
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    /** 从异常中读取上游 Retry-After 秒数（无则 null）。默认读 LlmException.httpStatus 关联头不可行，故由调用方传入。 */
    interface RetryAfterReader {
        Long retryAfterMs(LlmException e);
    }

    public GatewayRetry(long baseBackoffMs, double jitterFactor) {
        this(baseBackoffMs, jitterFactor,
                ms -> Thread.sleep(ms),
                e -> null);
    }

    /** 生产入口：1s→2s→4s + ±20% jitter */
    public static GatewayRetry production() {
        return new GatewayRetry(1000, 0.2);
    }

    GatewayRetry(long baseBackoffMs, double jitterFactor,
            Sleeper sleeper, RetryAfterReader retryAfterReader) {
        this.baseBackoffMs = baseBackoffMs;
        this.jitterFactor = jitterFactor;
        this.sleeper = sleeper;
        this.retryAfterReader = retryAfterReader;
    }

    /**
     * 执行带重试的操作。attemptContext 由调用方维护以区分错误类别
     * （如 5xx 与网络错误的次数预算不同），本方法按异常携带的 kind/httpStatus 分类计数。
     */
    public <T> T execute(ThrowingSupplier<T> op) {
        int networkAttempts = 0;
        int serverErrorAttempts = 0;
        int parseRetries = 0;
        while (true) {
            try {
                return op.get();
            } catch (LlmException e) {
                if (e.kind() == LlmException.Kind.NON_RETRYABLE) {
                    // 唯一例外：解析类坏 JSON 限重试 1 次
                    if (e.isParseFailure() && parseRetries < PARSE_RETRY_BUDGET) {
                        parseRetries++;
                        backoff(e, networkAttempts);
                        continue;
                    }
                    throw e;
                }
                // RETRYABLE 分类计数
                Integer status = e.httpStatus();
                if (status != null && status >= 500 && status < 600) {
                    serverErrorAttempts++;
                    if (serverErrorAttempts > MAX_5XX_ATTEMPTS - 1) {
                        // 首次请求 + 1 次重试 = 2 次尝试
                        throw e;
                    }
                    backoff(e, serverErrorAttempts);
                    continue;
                }
                networkAttempts++;
                if (networkAttempts > MAX_NETWORK_ATTEMPTS - 1) {
                    throw e; // 首次请求 + 2 次重试 = 3 次尝试
                }
                backoff(e, networkAttempts);
                continue;
            } catch (RuntimeException e) {
                // 未分类运行时异常视作网络级 RETRYABLE（与 DeepSeekClient 网络分支一致语义）
                networkAttempts++;
                if (networkAttempts > MAX_NETWORK_ATTEMPTS - 1) {
                    throw e instanceof LlmException le ? le
                            : new LlmException(LlmException.Kind.RETRYABLE,
                                    "LLM 重试耗尽: " + e.getMessage(), e);
                }
                backoff(null, networkAttempts);
            }
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get();
    }

    /** 指数退避 base×2^(n-1) + jitter；Retry-After 优先（ms） */
    private void backoff(LlmException e, int attempt) {
        long waitMs;
        Long retryAfter = e == null ? null : retryAfterReader.retryAfterMs(e);
        if (retryAfter != null) {
            waitMs = retryAfter;
        } else {
            long exp = baseBackoffMs * (1L << Math.max(0, attempt - 1));
            double jitter = 1.0 + (Math.random() * 2 - 1) * jitterFactor;
            waitMs = (long) (exp * jitter);
        }
        try {
            sleeper.sleep(Math.max(0, waitMs));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试等待被中断", ie);
        }
    }
}
