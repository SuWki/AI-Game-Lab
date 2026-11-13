package com.aigamelab.tool;

/**
 * 蓝图 §11 冻结契约：工具异常。
 * ToolExecutor 捕获 → tool_call.status=FAILED + error → 作为 observation 回传 LLM
 * （不致命，除非标 critical）。
 */
public class ToolException extends RuntimeException {

    private final boolean critical;

    public ToolException(String message) {
        this(message, false);
    }

    public ToolException(String message, boolean critical) {
        super(message);
        this.critical = critical;
    }

    public boolean isCritical() {
        return critical;
    }
}
