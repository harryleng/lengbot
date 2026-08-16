package com.lengbot.workflow;

import com.lengbot.enums.NodeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从工作流定义提取子流程 IO Schema（供 app_component 参数映射）
 */
public final class WorkflowIoSchemaUtil {

    private WorkflowIoSchemaUtil() {
    }

    /**
     * @param definition 已发布工作流定义
     * @return inputs / outputs 列表
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildSchema(WorkflowDefinition definition) {
        Set<String> inputKeys = new LinkedHashSet<>();
        inputKeys.add("query");
        inputKeys.add("input");

        if (definition != null && definition.getNodes() != null) {
            for (WorkflowNode node : definition.getNodes()) {
                if (node.getType() != NodeType.INPUT || node.getData() == null) {
                    continue;
                }
                collectParamKeys(node.getData().get("outputParams"), inputKeys);
                collectParamKeys(node.getData().get("output_params"), inputKeys);
            }
        }

        List<Map<String, Object>> inputs = new ArrayList<>();
        for (String key : inputKeys) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("type", "String");
            inputs.add(row);
        }

        List<Map<String, Object>> outputs = new ArrayList<>();
        Map<String, Object> resultRow = new LinkedHashMap<>();
        resultRow.put("key", "result");
        resultRow.put("type", "String");
        resultRow.put("description", "工作流最终输出");
        outputs.add(resultRow);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("inputs", inputs);
        schema.put("outputs", outputs);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static void collectParamKeys(Object paramsObj, Set<String> keys) {
        if (!(paramsObj instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            Object key = row.get("key");
            if (key != null && !String.valueOf(key).isBlank()) {
                keys.add(String.valueOf(key).trim());
            }
        }
    }
}
