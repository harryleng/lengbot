package com.lengbot.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.lengbot.util.InlineThinkingStreamParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流变量解析工具
 */
public final class WorkflowVariableUtils {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");

    private WorkflowVariableUtils() {
    }

    /**
     * 从 {{var}} 表达式解析变量值；非引用表达式则按模板渲染
     */
    public static Object resolveValue(String expression, Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        String trimmed = expression.trim();
        Matcher matcher = VAR_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            return getNestedValue(variables, matcher.group(1).trim());
        }
        String rendered = WorkflowPromptUtils.render(trimmed, variables);
        return rendered.equals(trimmed) ? trimmed : rendered;
    }

    /**
     * 基于完整上下文解析变量（支持 {{nodeId.field}} 命名空间引用）
     */
    public static Object resolveValue(String expression, NodeExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        if (context == null) {
            return null;
        }
        String trimmed = expression.trim();
        Matcher matcher = VAR_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            return WorkflowReferenceResolver.resolvePath(matcher.group(1).trim(), context);
        }
        String rendered = WorkflowPromptUtils.render(trimmed, context);
        return rendered.equals(trimmed) ? trimmed : rendered;
    }

    /**
     * 解析文本变量，支持 fallback
     */
    public static String resolveText(String expression, Map<String, Object> variables, String fallback) {
        Object value = resolveValue(expression, variables);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value).trim();
    }

    /**
     * 从节点 inputVariable 配置解析输入文本
     */
    public static String resolveInputText(String inputVariable, Map<String, Object> variables, String userInput) {
        return resolveInputText(inputVariable, variables, userInput, null);
    }

    /**
     * 从节点 inputVariable 配置解析输入文本（支持命名空间引用）
     */
    public static String resolveInputText(String inputVariable, Map<String, Object> variables,
                                          String userInput, NodeExecutionContext context) {
        String expr = inputVariable != null && !inputVariable.isBlank() ? inputVariable : "{{input}}";
        String rendered = context != null
                ? WorkflowPromptUtils.render(expr, context)
                : WorkflowPromptUtils.render(expr, variables);
        if (rendered != null && !rendered.isBlank() && !rendered.equals(expr)) {
            return rendered.trim();
        }
        Object resolved = context != null
                ? resolveValue(expr, context)
                : resolveValue(expr, variables);
        if (resolved != null && !String.valueOf(resolved).isBlank()) {
            return String.valueOf(resolved).trim();
        }
        if (variables != null) {
            Object query = variables.get("query");
            if (query != null && !String.valueOf(query).isBlank()) {
                return String.valueOf(query).trim();
            }
            Object input = variables.get("input");
            if (input != null && !String.valueOf(input).isBlank()) {
                return String.valueOf(input).trim();
            }
        }
        return userInput != null ? userInput.trim() : (rendered != null ? rendered.trim() : "");
    }

    /**
     * 提取 {{key}} 中的 key
     */
    public static String extractVarKey(String expression) {
        if (expression == null) {
            return null;
        }
        Matcher matcher = VAR_PATTERN.matcher(expression.trim());
        return matcher.matches() ? matcher.group(1).trim() : null;
    }

    /**
     * 按点号路径读取嵌套变量（仅支持 Map 一层嵌套）
     */
    @SuppressWarnings("unchecked")
    public static Object getNestedValue(Map<String, Object> variables, String path) {
        if (variables == null || path == null || path.isBlank()) {
            return null;
        }
        if (!path.contains(".")) {
            return variables.get(path.trim());
        }
        String[] parts = path.split("\\.");
        Object current = variables.get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(parts[i]);
        }
        return current;
    }

    /**
     * 从 LLM 原始响应中提取 JSON 对象字符串
     */
    public static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = InlineThinkingStreamParser.stripTags(raw).trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            } else {
                int start = text.indexOf('{');
                int end = text.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    return text.substring(start, end + 1);
                }
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 解析 LLM 返回的 JSON 对象为 Map（兼容 thinking 标签、markdown 代码块、无引号字段名等）
     *
     * @param raw          模型原始输出
     * @param objectMapper 标准 ObjectMapper
     * @return 解析后的键值对
     */
    public static Map<String, Object> parseLlmJsonMap(String raw, ObjectMapper objectMapper)
            throws JsonProcessingException {
        String json = extractJsonObject(raw);
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("模型未返回有效 JSON");
        }
        json = normalizeLlmJsonText(json);
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return normalizeLlmJsonKeys(map);
        } catch (JsonProcessingException strictError) {
            ObjectMapper lenient = objectMapper.copy()
                    .configure(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature(), true)
                    .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
                    .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
            Map<String, Object> map = lenient.readValue(json, new TypeReference<Map<String, Object>>() {});
            return normalizeLlmJsonKeys(map);
        }
    }

    /** 修复模型常见 JSON 笔误 */
    private static String normalizeLlmJsonText(String json) {
        String normalized = json;
        normalized = normalized.replace("\"_is.completed\"", "\"_is_completed\"");
        normalized = normalized.replace("'_is.completed'", "'_is_completed'");
        normalized = UNQUOTED_IS_COMPLETED.matcher(normalized).replaceAll("$1\"_is_completed\"$3");
        return normalized;
    }

    private static final Pattern UNQUOTED_IS_COMPLETED =
            Pattern.compile("([{,]\\s*)_is\\.completed(\\s*:)");

    /** 统一模型返回的字段名 */
    private static Map<String, Object> normalizeLlmJsonKeys(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return map != null ? map : Map.of();
        }
        boolean changed = false;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if ("_is.completed".equals(key)) {
                key = "_is_completed";
                changed = true;
            }
            out.put(key, entry.getValue());
        }
        return changed ? out : map;
    }
}
