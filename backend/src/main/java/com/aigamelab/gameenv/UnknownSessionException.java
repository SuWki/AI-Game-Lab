package com.aigamelab.gameenv;

/** T1-1：操作了不存在/已停止的会话时抛出。 */
public class UnknownSessionException extends IllegalArgumentException {

    public UnknownSessionException(String sessionId) {
        super("未知的游戏会话: " + sessionId);
    }
}
