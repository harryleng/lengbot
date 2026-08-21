package com.lengbot.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具入参 JSON Schema 校验器
 * <p>对 LLM 返回的工具入参做强校验，避免字段缺失/类型错误时 NPE；
 * 校验失败抛出 {@link ToolValidationException}，调用方将结构化错误回喂给 LLM 触发重试</p>
 * <p>支持的 Schema 子集（覆盖 ToolRegistrar 生成 + API Tool 自定义场景的 95%）：
 * type/required/properties/enum/items；不支持 $ref、oneOf 等高级特性</p>
 *
 * @author lw
 * @since 2026-07-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolInputSchemaValidator {

    private final ObjectMapper objectMapper;

    /**
     * 校验工具入参
     *
     * @param inputSchemaJson 工具的 inputSchema（JSON 字符串，由 ToolRegistrar 生成或用户配置）
     * @param argsJson        LLM 返回的工具入参（JSON 字符串）
     * @throws ToolValidationException 校验失败时抛出，message 已组装为对 LLM 友好的多行文本
     */
    public void validate(String inputSchemaJson, String argsJson) {
        if (inputSchemaJson == null || inputSchemaJson.isBlank()
                || "{}".equals(inputSchemaJson.trim())) {
            // 无 schema 视为放开校验，兼容老 API Tool
            return;
        }
        JsonNode schema;
        try {
            schema = objectMapper.readTree(inputSchemaJson);
        } catch (Exception e) {
            log.warn("[ToolSchema] inputSchema 非合法 JSON，跳过校验: {}", e.getMessage());
            return;
        }
        JsonNode args;
        try {
            args = objectMapper.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
        } catch (Exception e) {
            throw new ToolValidationException("工具入参不是合法 JSON：" + e.getMessage());
        }
        List<String> errors = new ArrayList<>();
        validateNode(schema, args, "$", errors);
        if (!errors.isEmpty()) {
            throw new ToolValidationException(formatErrors(errors));
        }
    }

    /**
     * 递归校验节点
     *
     * @param schema 当前节点对应的 schema
     * @param value  当前节点值
     * @param path   当前节点路径（如 "$.url"），用于错误定位
     * @param errors 累积错误列表
     */
    private void validateNode(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return;
        }
        // 1. type 校验
        JsonNode typeNode = schema.get("type");
        if (typeNode != null && !typeNode.isNull()) {
            String expectedType = typeNode.asText();
            if (!matchesType(value, expectedType)) {
                errors.add(String.format("- 字段 %s：类型应为 %s，实际为 %s",
                        path, expectedType, actualType(value)));
                return;
            }
        }
        // 2. enum 校验
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray() && !value.isMissingNode()) {
            boolean matched = false;
            for (JsonNode opt : enumNode) {
                if (opt.equals(value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(String.format("- 字段 %s：枚举值为 %s，实际为 %s",
                        path, enumNode.toString(), value.toString()));
            }
        }
        // 3. object：required + properties 递归
        if (value.isObject()) {
            validateObject(schema, value, path, errors);
        } else if (value.isArray()) {
            validateArray(schema, value, path, errors);
        }
    }

    /**
     * object 节点校验：required 必填 + properties 子节点递归
     */
    private void validateObject(JsonNode schema, JsonNode value, String path, List<String> errors) {
        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            for (JsonNode req : requiredNode) {
                String reqName = req.asText();
                JsonNode child = value.get(reqName);
                if (child == null || child.isMissingNode() || child.isNull()) {
                    errors.add(String.format("- 字段 %s.%s：必填，但未提供", path, reqName));
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> prop = fields.next();
                String propName = prop.getKey();
                JsonNode childValue = value.get(propName);
                if (childValue == null || childValue.isMissingNode()) {
                    continue;
                }
                validateNode(prop.getValue(), childValue, path + "." + propName, errors);
            }
        }
    }

    /**
     * array 节点校验：items 子节点递归
     */
    private void validateArray(JsonNode schema, JsonNode value, String path, List<String> errors) {
        JsonNode items = schema.get("items");
        if (items == null || items.isMissingNode()) {
            return;
        }
        int idx = 0;
        for (JsonNode element : value) {
            validateNode(items, element, path + "[" + idx + "]", errors);
            idx++;
        }
    }

    /**
     * 类型匹配（JSON Schema type vs Jackson JsonNode）
     */
    private boolean matchesType(JsonNode value, String expectedType) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            // null/缺失由 required 把关，type 不拦
            return true;
        }
        return switch (expectedType) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> true;
        };
    }

    private String actualType(JsonNode value) {
        if (value == null || value.isMissingNode()) {
            return "missing";
        }
        JsonNodeType t = value.getNodeType();
        return switch (t) {
            case STRING -> "string";
            case NUMBER -> value.isIntegralNumber() ? "integer" : "number";
            case BOOLEAN -> "boolean";
            case OBJECT -> "object";
            case ARRAY -> "array";
            case NULL -> "null";
            default -> t.name().toLowerCase();
        };
    }

    private String formatErrors(List<String> errors) {
        Set<String> deduped = new LinkedHashSet<>(errors);
        StringBuilder sb = new StringBuilder("工具调用参数校验失败：");
        for (String e : deduped) {
            sb.append("\n").append(e);
        }
        sb.append("\n\n请按 Schema 修正参数后重试。");
        return sb.toString();
    }
}
