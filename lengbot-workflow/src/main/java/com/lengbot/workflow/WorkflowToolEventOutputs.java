package com.lengbot.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流 tool 节点 SSE 事件 outputs 构建：保留完整 JSON 与 toolName，供 Chat toolRegistry 渲染
 */
public final class WorkflowToolEventOutputs {

    private WorkflowToolEventOutputs() {
    }

    /**
     * 从节点执行 outputs 提取 Chat 可渲染的 tool 事件字段（不截断 JSON）
     */
    public static Map<String, Object> build(Map<String, Object> source, ObjectMapper objectMapper) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        copyMetaField(out, source, "toolName");
        copyMetaField(out, source, "toolId");
        copyMetaField(out, source, "toolDisplayName");
        copyMetaField(out, source, "answer");
        copyMetaField(out, source, "question");
        copyMetaField(out, source, "is_open_ended");
        Object options = source.get("options");
        if (options != null) {
            out.put("options", options);
        }

        Object text = source.get("toolResultText");
        if (text instanceof String s && !s.isBlank()) {
            out.put("toolResultText", s);
        }
        Object toolResult = source.get("toolResult");
        if (toolResult != null) {
            if (toolResult instanceof String str && !str.isBlank()) {
                out.putIfAbsent("toolResultText", str);
            } else {
                out.put("toolResult", toolResult);
                if (!out.containsKey("toolResultText")) {
                    try {
                        out.put("toolResultText", objectMapper.writeValueAsString(toolResult));
                    } catch (Exception e) {
                        out.put("toolResultText", String.valueOf(toolResult));
                    }
                }
            }
        }
        if (!out.containsKey("toolResultText") && !out.containsKey("toolResult")) {
            Object output = source.get("output");
            if (output instanceof String s && !s.isBlank()) {
                out.put("toolResultText", s);
            } else if (output != null) {
                try {
                    out.put("toolResultText", objectMapper.writeValueAsString(output));
                } catch (Exception e) {
                    out.put("toolResultText", String.valueOf(output));
                }
            }
        }
        return out;
    }

    private static void copyMetaField(Map<String, Object> target, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }
}
