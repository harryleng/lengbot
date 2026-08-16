package com.lengbot.workflow;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流变量引用解析：支持 {{nodeId.field}}、${nodeId.field}、{{sys.query}} 与扁平兼容层
 */
public final class WorkflowReferenceResolver {

    private static final Pattern REF_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}|\\$\\{([^}]+)\\}");

    private WorkflowReferenceResolver() {
    }

    /**
     * 基于完整上下文渲染模板
     */
    public static String renderWithContext(String template, NodeExecutionContext context) {
        if (template == null) {
            return "";
        }
        if (context == null) {
            return template;
        }
        return renderTemplate(template, context.getVariables(), context.getScopedVariables(), context.getSysVariables());
    }

    /**
     * 基于扁平 variables 渲染（兼容旧调用）
     */
    public static String renderWithVariables(String template, Map<String, Object> variables) {
        if (template == null) {
            return "";
        }
        return renderTemplate(template, variables, null, null);
    }

    /**
     * 解析引用路径
     */
    public static Object resolvePath(String path, NodeExecutionContext context) {
        if (context == null) {
            return null;
        }
        return resolvePath(path, context.getVariables(), context.getScopedVariables(), context.getSysVariables());
    }

    private static String renderTemplate(String template, Map<String, Object> flat,
                                         Map<String, Map<String, Object>> scoped,
                                         Map<String, Object> sys) {
        Matcher matcher = REF_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String path = matcher.group(1) != null ? matcher.group(1).trim() : matcher.group(2).trim();
            Object value = resolvePath(path, flat, scoped, sys);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(formatValue(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Object resolvePath(String path, Map<String, Object> flat,
                                      Map<String, Map<String, Object>> scoped,
                                      Map<String, Object> sys) {
        if (path == null || path.isBlank()) {
            return null;
        }
        path = path.trim();

        if (path.startsWith("sys.")) {
            String key = path.substring(4);
            if (sys != null && sys.containsKey(key)) {
                return sys.get(key);
            }
            if (flat != null) {
                return flat.get(key);
            }
            return null;
        }

        if (path.contains(".")) {
            int dot = path.indexOf('.');
            String nodeId = path.substring(0, dot);
            String fieldPath = path.substring(dot + 1);

            if ("sys".equals(nodeId)) {
                if (sys != null) {
                    return getNestedValue(sys, fieldPath);
                }
                if (flat != null) {
                    return getNestedValue(flat, fieldPath);
                }
                return null;
            }

            if (scoped != null && scoped.containsKey(nodeId)) {
                Object scopedVal = getNestedValue(scoped.get(nodeId), fieldPath);
                if (scopedVal != null) {
                    return scopedVal;
                }
            }
            if (flat != null && flat.get(nodeId) instanceof Map<?, ?> nodeBucket) {
                Object bucketVal = getNestedValue((Map<String, Object>) nodeBucket, fieldPath);
                if (bucketVal != null) {
                    return bucketVal;
                }
            }
            if (flat != null) {
                Object nested = WorkflowVariableUtils.getNestedValue(flat, path);
                if (nested != null) {
                    return nested;
                }
            }
        }

        if (flat != null && flat.containsKey(path)) {
            return flat.get(path);
        }
        if (sys != null && sys.containsKey(path)) {
            return sys.get(path);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object getNestedValue(Map<String, Object> map, String path) {
        if (map == null || path == null || path.isBlank()) {
            return null;
        }
        if (!path.contains(".")) {
            return map.get(path.trim());
        }
        String[] parts = path.split("\\.");
        Object current = map.get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!(current instanceof Map<?, ?> nested)) {
                return null;
            }
            current = ((Map<String, Object>) nested).get(parts[i]);
        }
        return current;
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return WorkflowResultUtils.formatAsText(value);
        }
        return String.valueOf(value);
    }
}
