package com.aigamelab.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.aigamelab.common.ApiResp;
import com.aigamelab.common.BusinessException;
import com.aigamelab.common.ErrorCodes;
import com.aigamelab.llm.LlmException;

/**
 * 全局异常 → 统一错误包络（蓝图 §8 六码，@RestControllerAdvice 统一转换）。
 *
 * 映射表：
 *  - BusinessException              → 其自带 code
 *  - MethodArgumentNotValidException 等参数校验 → 40001
 *  - NoResourceFoundException / 资源不存在     → 40401
 *  - DuplicateKeyException          → 40901（UNIQUE 冲突 = 幂等复用/资源冲突）
 *  - DataIntegrityViolationException（非 UNIQUE）→ 40901（同为约束冲突类）
 *  - 未知 Exception 兜底             → 50000，日志留痕、响应零泄漏
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResp<Void>> business(BusinessException e) {
        return respond(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResp<Void>> invalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("请求体不合法");
        return respond(ErrorCodes.INVALID_ARGUMENT, "参数校验失败: " + detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiResp<Void>> badRequest(Exception e) {
        return respond(ErrorCodes.INVALID_ARGUMENT, "请求参数不合法");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResp<Void>> noStaticResource(NoResourceFoundException e) {
        return respond(ErrorCodes.NOT_FOUND, "资源不存在");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResp<Void>> duplicate(DuplicateKeyException e) {
        // SQLite UNIQUE → DuplicateKeyException 的端到端出口：HTTP 409 + 40901
        return respond(ErrorCodes.CONFLICT, "资源已存在或唯一性冲突");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResp<Void>> integrity(DataIntegrityViolationException e) {
        return respond(ErrorCodes.CONFLICT, "数据约束冲突");
    }

    /**
     * P8-2-2：LLM 网关异常 → HTTP 502 + ErrorCodes.UPSTREAM_ERROR（50201）。
     * 截断原始消息至 300 字符，杜绝 SQL / 堆栈 / 内部类名泄漏。
     * 只覆盖 Controller / @RestControllerAdvice 层抛出的 LlmException；
     * AgentLoop 内部的 LlmException 由其自身 RuntimeException catch 收口为 Run=FAILED。
     */
    @ExceptionHandler(LlmException.class)
    public ResponseEntity<ApiResp<Void>> upstream(LlmException e) {
        String reason = e.getMessage();
        if (reason != null && reason.length() > 300) {
            reason = reason.substring(0, 300);
        }
        return respond(ErrorCodes.UPSTREAM_ERROR, "LLM 网关异常: " + (reason == null ? "" : reason));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResp<Void>> unknown(Exception e) {
        // 完整堆栈只进日志；对外仅暴露固定文案，杜绝 SQL / 类名 / 内部细节泄漏
        log.error("unhandled exception", e);
        return respond(ErrorCodes.INTERNAL_ERROR, "服务器内部错误");
    }

    private ResponseEntity<ApiResp<Void>> respond(int code, String message) {
        return ResponseEntity.status(code / 100).body(ApiResp.error(code, message));
    }
}
