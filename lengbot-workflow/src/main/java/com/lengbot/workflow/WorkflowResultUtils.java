package com.lengbot.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 工作流最终结果格式化：将 Map/JSON 解包为主字段字符串，避免 Map.toString 进入对话回复
 */
public final class WorkflowResultUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String[] PRIMARY_TEXT_KEYS = {
            "output", "result", "llmOutput", "text", "content", "body", "retrievalResult", "toolResultText"
    };

    private WorkflowResultUtils() {
    }

    /**
     * 将节点输出或变量值格式化为用户可见文本
     *
     * @param value 原始值
     * @return 纯文本；Map 优先解包主字段
     */
    public static String formatAsText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : PRIMARY_TEXT_KEYS) {
                if (map.containsKey(key)) {
                    Object inner = map.get(key);
                    if (inner != null && !(inner instanceof Map<?, ?>)) {
                        return formatAsText(inner);
                    }
                }
            }
            if (map.size() == 1) {
                Object only = map.values().iterator().next();
                if (!(only instanceof Map<?, ?>)) {
                    return formatAsText(only);
                }
            }
            try {
                return OBJECT_MAPPER.writeValueAsString(map);
            } catch (JsonProcessingException e) {
                return String.valueOf(value);
            }
        }
        if (value instanceof List<?> list) {
            try {
                return OBJECT_MAPPER.writeValueAsString(list);
            } catch (JsonProcessingException e) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }
}
