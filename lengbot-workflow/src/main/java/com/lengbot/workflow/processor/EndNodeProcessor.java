package com.lengbot.workflow.processor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.enums.NodeType;
import com.lengbot.workflow.NodeExecutionContext;
import com.lengbot.workflow.NodeExecutionResult;
import com.lengbot.workflow.NodeProcessor;
import com.lengbot.workflow.WorkflowNode;
import com.lengbot.workflow.WorkflowNodeDataUtils;
import com.lengbot.workflow.WorkflowPromptUtils;
import com.lengbot.workflow.WorkflowResultUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结束节点处理器
 * <p>按 outputType + textTemplate/jsonParams 显式配置最终输出，禁止 Map.toString 作为对话回复</p>
 *
 * @author finch
 * @since 2026-05-24
 */
@Component
public class EndNodeProcessor implements NodeProcessor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public NodeType getType() {
        return NodeType.END;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        Map<String, Object> nodeData = context.getCurrentNodeData() != null
                ? context.getCurrentNodeData() : Map.of();
        String result = resolveFinalResult(context, nodeData);

        Map<String, Object> outputs = new HashMap<>();
        outputs.put("result", result);
        context.getVariables().put("result", result);

        return NodeExecutionResult.builder()
                .outputs(outputs)
                .finished(true)
                .build();
    }

    private String resolveFinalResult(NodeExecutionContext context, Map<String, Object> nodeData) {
        String outputType = firstNonBlank(
                WorkflowNodeDataUtils.parseString(nodeData.get("outputType")),
                WorkflowNodeDataUtils.parseString(nodeData.get("output_type")),
                "text");

        if ("json".equalsIgnoreCase(outputType)) {
            return buildJsonResult(context, nodeData);
        }

        String template = firstNonBlank(
                WorkflowNodeDataUtils.parseString(nodeData.get("textTemplate")),
                WorkflowNodeDataUtils.parseString(nodeData.get("text_template")),
                WorkflowNodeDataUtils.parseString(nodeData.get("output")),
                "{{output}}");

        String rendered = WorkflowPromptUtils.render(template, context);
        if (rendered != null && !rendered.isBlank() && !rendered.equals(template)) {
            return rendered.trim();
        }

        Object heuristic = resolveHeuristicResult(context);
        if (heuristic != null) {
            return WorkflowResultUtils.formatAsText(heuristic);
        }
        return rendered != null ? rendered.trim() : "";
    }

    @SuppressWarnings("unchecked")
    private String buildJsonResult(NodeExecutionContext context, Map<String, Object> nodeData) {
        Object jsonParamsObj = nodeData.get("jsonParams");
        if (jsonParamsObj == null) {
            jsonParamsObj = nodeData.get("json_params");
        }
        if (!(jsonParamsObj instanceof List<?> params) || params.isEmpty()) {
            return WorkflowResultUtils.formatAsText(resolveHeuristicResult(context));
        }
        Map<String, Object> json = new HashMap<>();
        for (Object item : params) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            String key = WorkflowNodeDataUtils.parseString(row.get("key"));
            if (key == null || key.isBlank()) {
                continue;
            }
            String valueExpr = WorkflowNodeDataUtils.parseString(row.get("value"));
            Object resolved = null;
            if (valueExpr != null && !valueExpr.isBlank()) {
                resolved = com.lengbot.workflow.WorkflowVariableUtils.resolveValue(valueExpr, context);
                if (resolved == null || valueExpr.equals(String.valueOf(resolved))) {
                    resolved = WorkflowPromptUtils.render(valueExpr, context);
                }
            }
            json.put(key.trim(), resolved);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            return WorkflowResultUtils.formatAsText(json);
        }
    }


    private Object resolveHeuristicResult(NodeExecutionContext context) {
        Object llmOutput = context.getVariables().get("llmOutput");
        if (llmOutput != null) {
            return llmOutput;
        }
        Object output = context.getVariables().get("output");
        if (output != null) {
            return output;
        }
        Object existingResult = context.getVariables().get("result");
        if (existingResult != null) {
            return existingResult;
        }

        if (context.getWorkflow() != null && context.getWorkflow().getNodes() != null) {
            for (int i = context.getWorkflow().getNodes().size() - 1; i >= 0; i--) {
                WorkflowNode node = context.getWorkflow().getNodes().get(i);
                if (node.getType() == NodeType.LLM) {
                    Object nodeOutput = context.getNodeOutputs().get(node.getId());
                    if (nodeOutput instanceof Map<?, ?> map && map.containsKey("llmOutput")) {
                        return map.get("llmOutput");
                    }
                }
            }
        }

        Object lastOutput = context.getNodeOutputs().values().stream()
                .reduce((first, second) -> second)
                .orElse(null);
        if (lastOutput instanceof Map<?, ?> map) {
            if (map.containsKey("output")) {
                return map.get("output");
            }
            if (map.containsKey("result")) {
                return map.get("result");
            }
        }
        return lastOutput;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
