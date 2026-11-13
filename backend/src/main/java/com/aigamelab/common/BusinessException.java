package com.aigamelab.common;

/**
 * 业务异常：Controller / Service 层抛出，由 GlobalExceptionHandler 统一转为错误包络。
 * code 必须取自 {@link ErrorCodes} 六码之一。
 *
 * @param code    六码之一
 * @param message 面向调用方的安全信息——禁止携带 SQL、堆栈、内部类名等实现细节
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 便捷工厂：资源不存在 */
    public static BusinessException notFound(String message) {
        return new BusinessException(ErrorCodes.NOT_FOUND, message);
    }

    /** 便捷工厂：状态机/冲突 */
    public static BusinessException conflict(String message) {
        return new BusinessException(ErrorCodes.CONFLICT, message);
    }

    public int getCode() {
        return code;
    }
}
