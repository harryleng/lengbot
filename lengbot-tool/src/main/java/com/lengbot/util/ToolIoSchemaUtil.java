package com.lengbot.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.entity.Tool;
import com.lengbot.tool.builtin.AskUserTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 从 Tool 的 inputSchema / outputSchema 提取工作流映射用 IO 列表
 */
public final class ToolIoSchemaUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ToolIoSchemaUtil() {
    }

    /**
     * @param tool 工具实体
     * @return inputs / outputs / toolName
     */
    public static Map<String, Object> buildSchema(Tool tool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (tool == null) {
            schema.put("inputs", List.of());
            schema.put("outputs", defaultOutputs());
            return schema;
        }
        schema.put("toolId", tool.getId() != null ? String.valueOf(tool.getId()) : null);
        schema.put("toolName", tool.getName());
        schema.put("displayName", tool.getDisplayName());
        schema.put("inputs", parseSchemaProperties(tool.getInputSchema(), true));
        List<Map<String, Object>> outputs = parseSchemaProperties(tool.getOutputSchema(), false);
        if (outputs.isEmpty()) {
            outputs = defaultOutputs();
        }
        if (AskUserTool.TOOL_NAME.equals(tool.getName())) {
            appendAskUserOutputHints(outputs);
        }
        schema.put("outputs", outputs);
        return schema;
    }

    private static List<Map<String, Object>> defaultOutputs() {
        List<Map<String, Object>> outputs = new ArrayList<>();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("key", "output");
        raw.put("type", "String");
        raw.put("description", "工具原始返回（JSON 字符串）");
        outputs.add(raw);
        return outputs;
    }

    /** ask_user：补充 HITL resume 后才有的 answer 字段说明 */
    private static void appendAskUserOutputHints(List<Map<String, Object>> outputs) {
        if (outputs == null) {
            return;
        }
        boolean hasAnswer = outputs.stream().anyMatch(row -> "answer".equals(row.get("key")));
        if (!hasAnswer) {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("key", "answer");
            answer.put("type", "String");
            answer.put("description", "用户回答（工作流 resume 后注入）");
            outputs.add(answer);
        }
    }

    private static List<Map<String, Object>> parseSchemaProperties(String schemaJson, boolean includeRequired) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (schemaJson == null || schemaJson.isBlank() || "{}".equals(schemaJson.trim())) {
            return rows;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(schemaJson);
            JsonNode properties = root.get("properties");
            if (properties == null || !properties.isObject()) {
                return rows;
            }
            List<String> required = new ArrayList<>();
            if (includeRequired && root.has("required") && root.get("required").isArray()) {
                for (JsonNode item : root.get("required")) {
                    if (item.isTextual()) {
                        required.add(item.asText());
                    }
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                JsonNode prop = entry.getValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", key);
                row.put("type", capitalizeType(prop.path("type").asText("String")));
                if (prop.has("description")) {
                    row.put("description", prop.get("description").asText());
                }
                if (includeRequired) {
                    row.put("required", required.contains(key));
                }
                rows.add(row);
            }
        } catch (Exception ignored) {
            return rows;
        }
        return rows;
    }

    private static String capitalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "String";
        }
        return switch (type.toLowerCase()) {
            case "integer", "number" -> "Number";
            case "boolean" -> "Boolean";
            case "array" -> "Array";
            case "object" -> "Object";
            default -> "String";
        };
    }
}
