package com.lengbot.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * JSONB 数组 ID 解析工具。
 */
public final class JsonIdParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonIdParser() {
    }

    public static List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Object> raw = MAPPER.readValue(json, new TypeReference<>() {
            });
            List<Long> ids = new ArrayList<>();
            for (Object item : raw) {
                if (item == null || item.toString().trim().isBlank()) {
                    continue;
                }
                try {
                    ids.add(Long.parseLong(item.toString().trim()));
                } catch (NumberFormatException ignored) {
                    // 忽略 JSON 数组中的非法 ID，保持既有兼容行为。
                }
            }
            return ids;
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
