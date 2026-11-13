package com.aigamelab.sim;

import java.io.InputStream;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P4-0：GameConfig 加载器（Jackson 反序列化）。
 * 支持从 classpath 资源（如 config/v001.json）或输入流加载；
 * 反向序列化（模型 → JSON）同样可用，满足"config JSON ↔ Java 模型双向可用"验收。
 */
public final class GameConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GameConfigLoader() {
    }

    /** 从 classpath 资源加载（如 "config/v001.json"）。 */
    public static GameConfig loadFromClasspath(String resourcePath) {
        try (InputStream in = GameConfigLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("配置资源不存在: " + resourcePath);
            }
            return MAPPER.readValue(in, GameConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("加载 GameConfig 失败: " + resourcePath, e);
        }
    }

    /** 模型 → JSON 字符串（用于持久化/调试）。 */
    public static String toJson(GameConfig config) {
        try {
            return MAPPER.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 GameConfig 失败", e);
        }
    }

    /** JSON 字符串 → 模型。 */
    public static GameConfig fromJson(String json) {
        try {
            return MAPPER.readValue(json, GameConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析 GameConfig JSON 失败", e);
        }
    }
}
