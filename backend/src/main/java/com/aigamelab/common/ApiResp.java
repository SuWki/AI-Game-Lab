package com.aigamelab.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应包络（蓝图 §8 line 311）：{code, message, data}。
 * 成功固定 {0,"ok",data}；失败 data 为 null、message 为安全信息。
 * 全部 REST Controller 返回值统一为本类型，禁止手写 Map 包络。
 */
@JsonInclude(JsonInclude.Include.ALWAYS) // 失败时 data:null 也必须出现在 JSON 中
public record ApiResp<T>(int code, String message, T data) {

    public static <T> ApiResp<T> ok(T data) {
        return new ApiResp<>(0, "ok", data);
    }

    public static ApiResp<Void> ok() {
        return new ApiResp<>(0, "ok", null);
    }

    public static ApiResp<Void> error(int code, String message) {
        return new ApiResp<>(code, message, null);
    }
}
