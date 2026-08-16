package com.lengbot.workflow.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.dto.CodeExecResultDTO;
import com.lengbot.enums.NodeType;
import com.lengbot.service.sandbox.SandboxService;
import com.lengbot.workflow.NodeExecutionContext;
import com.lengbot.workflow.NodeExecutionResult;
import com.lengbot.workflow.NodeProcessor;
import com.lengbot.workflow.NodeTimeoutRetryHelper;
import com.lengbot.workflow.WorkflowMappingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本节点：委托 {@link SandboxService} 执行脚本（JavaScript / Python / Groovy / Java）并写入输出变量
 * <p>约定入口：JS/Python/Groovy 使用 {@code main(params)}；Java 为 Janino {@code run(params)} 方法体。</p>
 *
 * @author finch
 * @since 2026-06-24
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScriptNodeProcessor extends AbstractFlowNodeProcessor implements NodeProcessor {

    private final SandboxService sandboxService;
    private final ObjectMapper objectMapper;

    @Override
    public NodeType getType() {
        return NodeType.SCRIPT;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        Map<String, Object> nodeData = context.getCurrentNodeData();
        if (nodeData == null) {
            return passThrough(context, "result", null);
        }

        Map<String, Object> params = buildScriptParams(nodeData, context);
        String script = stringVal(nodeData.get("scriptContent"));
        String language = stringVal(nodeData.get("scriptLanguage"));
        if (script.isBlank()) {
            throw new IllegalArgumentException("脚本内容不能为空");
        }

        int readTimeoutSec = NodeTimeoutRetryHelper.resolveReadTimeoutSeconds(nodeData, NodeType.SCRIPT);
        long timeoutMs = Math.max(1000L, readTimeoutSec * 1000L);

        // 委托统一沙盒执行
        String lang = language.isBlank() ? "javascript" : language;
        CodeExecResultDTO result = sandboxService.executeCode(script, lang, params, timeoutMs);

        if (!result.isSuccess()) {
            // 失败策略：defaultValue 则返回默认输出，否则抛异常
            String errorStrategy = stringVal(nodeData.get("errorStrategy"));
            if ("defaultValue".equals(errorStrategy)) {
                Map<String, Object> defaultOutputs = parseDefaultOutput(nodeData);
                context.getVariables().putAll(defaultOutputs);
                return NodeExecutionResult.builder()
                        .nextNodeId(resolveNextNodeId(context))
                        .outputs(defaultOutputs)
                        .build();
            }
            throw new IllegalArgumentException("脚本执行失败: " + result.getError());
        }

        // 解析返回值为 Map（保持现有 output 映射逻辑）
        Object rawResult = parseReturnValue(result.getReturnValue());
        Map<String, Object> outputs = normalizeOutputs(rawResult, nodeData);
        context.getVariables().putAll(outputs);

        return NodeExecutionResult.builder()
                .nextNodeId(resolveNextNodeId(context))
                .outputs(outputs)
                .build();
    }

    /**
     * 解析默认输出 JSON（失败策略为 defaultValue 时使用）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDefaultOutput(Map<String, Object> nodeData) {
        Map<String, Object> outputs = new HashMap<>();
        Object defaultOutputObj = nodeData.get("defaultOutput");
        if (defaultOutputObj instanceof String s && !s.isBlank()) {
            try {
                Object parsed = objectMapper.readValue(s, Object.class);
                if (parsed instanceof Map<?, ?> map) {
                    map.forEach((k, v) -> outputs.put(String.valueOf(k), v));
                } else {
                    outputs.put("result", parsed);
                }
            } catch (Exception e) {
                log.warn("[ScriptNodeProcessor] 默认输出JSON解析失败: {}", e.getMessage());
                outputs.put("result", s);
            }
        }
        if (outputs.isEmpty()) {
            outputs.put("result", "");
        }
        return outputs;
    }

    private Map<String, Object> buildScriptParams(Map<String, Object> nodeData, NodeExecutionContext context) {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> variables = context.getVariables() != null ? context.getVariables() : Map.of();
        Object inputParams = nodeData.get("inputParams");
        if (inputParams == null) {
            inputParams = nodeData.get("input_params");
        }
        if (inputParams instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) continue;
                String key = stringVal(row.get("key"));
                if (key.isBlank()) continue;
                String valueExpr = stringVal(row.get("value"));
                params.put(key, WorkflowMappingUtils.resolveTemplateValue(valueExpr, context));
            }
        }
        Object fallbackInput = variables.getOrDefault("input", context.getUserInput());
        Object fallbackQuery = variables.getOrDefault("query", fallbackInput);
        if (isBlankValue(params.get("input")) && fallbackInput != null) {
            params.put("input", fallbackInput);
        }
        if (isBlankValue(params.get("query")) && fallbackQuery != null) {
            params.put("query", fallbackQuery);
        }
        if (params.isEmpty()) {
            params.put("query", fallbackQuery);
            params.put("input", fallbackInput);
        }
        return params;
    }

    /**
     * 解析返回值：尝试 JSON 反序列化为 Map，否则作为原始值
     */
    private Object parseReturnValue(String returnValue) {
        if (returnValue == null || returnValue.isBlank()) return null;
        String trimmed = returnValue.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return objectMapper.readValue(trimmed, Object.class);
            } catch (Exception ignored) {
            }
        }
        return trimmed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeOutputs(Object rawResult, Map<String, Object> nodeData) {
        Map<String, Object> outputs = new HashMap<>();
        if (rawResult instanceof Map<?, ?> map) {
            map.forEach((k, v) -> outputs.put(String.valueOf(k), v));
        } else if (rawResult != null) {
            outputs.put("result", rawResult);
        }
        Object outputParams = nodeData.get("outputParams");
        if (outputParams == null) {
            outputParams = nodeData.get("output_params");
        }
        if (outputParams instanceof List<?> list && !list.isEmpty() && outputs.isEmpty()) {
            String key = stringVal(((Map<?, ?>) list.get(0)).get("key"));
            if (!key.isBlank()) {
                outputs.put(key, rawResult);
            }
        }
        if (outputs.isEmpty()) {
            outputs.put("result", rawResult);
        }
        return outputs;
    }

    private String stringVal(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private boolean isBlankValue(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }
}
