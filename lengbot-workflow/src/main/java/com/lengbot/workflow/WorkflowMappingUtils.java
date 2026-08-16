package com.lengbot.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流节点参数映射：{{变量}} 模板解析与 input/outputMappings 读写
 */
public final class WorkflowMappingUtils {

    private WorkflowMappingUtils() {
    }

    /**
     * 从 nodeData 读取 inputMappings，按模板解析为工具/API 入参（支持 {{nodeId.field}} / {{sys.query}}）
     */
    public static Map<String, Object> buildInputArgs(Map<String, Object> nodeData, NodeExecutionContext context) {
        Map<String, Object> args = new LinkedHashMap<>();
        List<Map<String, Object>> mappings = readInputMappingList(nodeData);
        if (mappings == null) {
            return args;
        }
        for (Map<String, Object> row : mappings) {
            String key = WorkflowNodeDataUtils.parseString(row.get("key"));
            if (key == null) {
                continue;
            }
            args.put(key, resolveTemplateValue(row.get("value"), context));
        }
        return args;
    }

    /**
     * 兼容旧调用：仅扁平 variables，不含 sys 桶与 scoped 优先解析
     */
    public static Map<String, Object> buildInputArgs(Map<String, Object> nodeData, Map<String, Object> variables) {
        Map<String, Object> args = new LinkedHashMap<>();
        List<Map<String, Object>> mappings = readInputMappingList(nodeData);
        if (mappings == null) {
            return args;
        }
        for (Map<String, Object> row : mappings) {
            String key = WorkflowNodeDataUtils.parseString(row.get("key"));
            if (key == null) {
                continue;
            }
            args.put(key, resolveTemplateValue(row.get("value"), variables));
        }
        return args;
    }

    /**
     * 将 sourceVars 按 outputMappings 写入工作流变量名
     */
    public static Map<String, Object> applyOutputMappings(Map<String, Object> nodeData,
                                                          Map<String, Object> sourceVars,
                                                          String fallbackKey,
                                                          Object fallbackValue) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        List<Map<String, Object>> mappings = readMappingList(nodeData.get("outputMappings"));
        if (mappings == null || mappings.isEmpty()) {
            mappings = readMappingList(nodeData.get("output_mappings"));
        }
        if (mappings != null && !mappings.isEmpty()) {
            for (Map<String, Object> row : mappings) {
                String key = WorkflowNodeDataUtils.parseString(row.get("key"));
                if (key == null) {
                    continue;
                }
                Object rawValue = row.get("value");
                if (rawValue == null || String.valueOf(rawValue).isBlank()) {
                    rawValue = "{{" + fallbackKey + "}}";
                }
                outputs.put(key, resolveTemplateValue(rawValue, sourceVars));
            }
            return outputs;
        }
        if (fallbackKey != null) {
            Object value = sourceVars.get(fallbackKey);
            if (value == null) {
                value = fallbackValue;
            }
            outputs.put(fallbackKey, value);
        }
        return outputs;
    }

    /**
     * 解析模板（完整上下文：{{nodeId.field}}、{{sys.query}}、混合文本）
     */
    public static Object resolveTemplateValue(Object rawValue, NodeExecutionContext context) {
        if (!(rawValue instanceof String text)) {
            return rawValue;
        }
        if (context == null) {
            return resolveTemplateValue(rawValue, Map.of());
        }
        return WorkflowVariableUtils.resolveValue(text, context);
    }

    /**
     * 解析模板（扁平 variables，支持 a.b 点路径与 Prompt 渲染）
     */
    public static Object resolveTemplateValue(Object rawValue, Map<String, Object> variables) {
        if (!(rawValue instanceof String text)) {
            return rawValue;
        }
        return WorkflowVariableUtils.resolveValue(text, variables);
    }

    private static List<Map<String, Object>> readInputMappingList(Map<String, Object> nodeData) {
        if (nodeData == null) {
            return null;
        }
        List<Map<String, Object>> mappings = readMappingList(nodeData.get("inputMappings"));
        if (mappings == null || mappings.isEmpty()) {
            mappings = readMappingList(nodeData.get("input_mappings"));
        }
        return mappings;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> readMappingList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return result;
    }
}
