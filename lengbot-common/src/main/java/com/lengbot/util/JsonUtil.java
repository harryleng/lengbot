package com.lengbot.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * JSON 安全解析工具。
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonToMap(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ignored) {
            return new HashMap<>();
        }
    }
}
