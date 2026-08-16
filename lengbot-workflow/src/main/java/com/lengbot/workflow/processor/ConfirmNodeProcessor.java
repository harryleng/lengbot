package com.lengbot.workflow.processor;

import com.lengbot.enums.NodeType;
import com.lengbot.workflow.NodeExecutionContext;
import com.lengbot.workflow.NodeExecutionResult;
import com.lengbot.workflow.NodeProcessor;
import com.lengbot.workflow.WorkflowNodeDataUtils;
import com.lengbot.workflow.WorkflowVariableUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工确认节点：执行时挂起工作流，等待用户填写表单后 resume 继续
 */
@Slf4j
@Component
public class ConfirmNodeProcessor extends AbstractFlowNodeProcessor implements NodeProcessor {

    @Override
    public NodeType getType() {
        return NodeType.CONFIRM;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeExecutionResult execute(NodeExecutionContext context) {
        Map<String, Object> nodeData = context.getCurrentNodeData() != null
                ? context.getCurrentNodeData() : Map.of();

        String message = WorkflowNodeDataUtils.parseString(nodeData.get("message"));
        if (message == null || message.isBlank()) {
            message = "请填写以下信息并确认后继续";
        } else {
            message = resolveTemplateText(message, context);
        }

        List<Map<String, Object>> formFields = resolveFormFields(nodeData, context);

        Map<String, Object> suspendPayload = new HashMap<>();
        suspendPayload.put("nodeId", context.getCurrentNodeId());
        suspendPayload.put("message", message);
        suspendPayload.put("formFields", formFields);

        log.info("[ConfirmNodeProcessor] 挂起等待人工确认: nodeId={}, fields={}",
                context.getCurrentNodeId(), formFields.size());

        return NodeExecutionResult.builder()
                .suspended(true)
                .nextNodeId(resolveNextNodeId(context))
                .suspendPayload(suspendPayload)
                .outputs(Map.of())
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolveFormFields(Map<String, Object> nodeData, NodeExecutionContext context) {
        Object raw = nodeData.get("formFields");
        if (raw == null) {
            raw = nodeData.get("form_fields");
        }
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return defaultFormFields();
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String fieldType = normalizeFieldType(map.get("type"));
            if ("info".equals(fieldType)) {
                Map<String, Object> field = new HashMap<>();
                field.put("key", WorkflowNodeDataUtils.parseString(map.get("key")));
                field.put("label", resolveTemplateText(WorkflowNodeDataUtils.parseString(map.get("label")), context));
                field.put("type", "info");
                Object defaultValue = map.get("defaultValue");
                if (defaultValue == null) {
                    defaultValue = map.get("default_value");
                }
                if (defaultValue != null) {
                    field.put("defaultValue", resolveTemplateValue(defaultValue, context));
                }
                fields.add(field);
                continue;
            }
            Map<String, Object> field = new HashMap<>();
            String key = WorkflowNodeDataUtils.parseString(map.get("key"));
            if (key == null || key.isBlank()) {
                continue;
            }
            field.put("key", key);
            field.put("label", resolveTemplateText(WorkflowNodeDataUtils.parseString(map.get("label")), context));
            field.put("type", normalizeFieldType(map.get("type")));
            field.put("required", Boolean.TRUE.equals(map.get("required")));
            Object defaultValue = map.get("defaultValue");
            if (defaultValue == null) {
                defaultValue = map.get("default_value");
            }
            if (defaultValue != null) {
                field.put("defaultValue", resolveTemplateValue(defaultValue, context));
            }
            Object options = map.get("options");
            if (options instanceof List<?> optList && !optList.isEmpty()) {
                field.put("options", optList);
            }
            fields.add(field);
        }
        return fields.isEmpty() ? defaultFormFields() : fields;
    }

    /** 渲染 message / info.label 等模板字符串 */
    private String resolveTemplateText(String text, NodeExecutionContext context) {
        if (text == null || text.isBlank() || !text.contains("{{")) {
            return text;
        }
        Object resolved = WorkflowVariableUtils.resolveValue(text, context);
        return resolved != null ? String.valueOf(resolved) : text;
    }

    /** 渲染字段 defaultValue 模板（保留非字符串原值） */
    private Object resolveTemplateValue(Object value, NodeExecutionContext context) {
        if (value instanceof String str && str.contains("{{")) {
            Object resolved = WorkflowVariableUtils.resolveValue(str, context);
            return resolved != null ? resolved : value;
        }
        return value;
    }

    private List<Map<String, Object>> defaultFormFields() {
        List<Map<String, Object>> fields = new ArrayList<>();
        Map<String, Object> info = new HashMap<>();
        info.put("key", "_info");
        info.put("label", "请确认是否继续执行后续步骤");
        info.put("type", "info");
        fields.add(info);
        Map<String, Object> choice = new HashMap<>();
        choice.put("key", "choice");
        choice.put("label", "您的选择");
        choice.put("type", "radio");
        choice.put("required", true);
        choice.put("options", List.of("继续", "取消"));
        fields.add(choice);
        return fields;
    }

    private String normalizeFieldType(Object type) {
        String t = type != null ? String.valueOf(type).trim().toLowerCase() : "text";
        return switch (t) {
            case "textarea", "number", "select", "radio", "info" -> t;
            default -> "text";
        };
    }
}
