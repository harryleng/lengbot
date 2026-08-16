package com.lengbot.workflow.processor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.entity.McpServer;
import com.lengbot.enums.NodeType;
import com.lengbot.service.McpClientService;
import com.lengbot.service.McpServerService;
import com.lengbot.workflow.NodeExecutionContext;
import com.lengbot.workflow.NodeExecutionResult;
import com.lengbot.workflow.NodeProcessor;
import com.lengbot.workflow.WorkflowMappingUtils;
import com.lengbot.workflow.WorkflowNodeDataUtils;
import com.lengbot.workflow.WorkflowPromptUtils;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具节点：按 MCP Server 名称和工具名调用远程工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpNodeProcessor extends AbstractFlowNodeProcessor implements NodeProcessor {

    private final McpServerService mcpServerService;
    private final McpClientService mcpClientService;
    private final ObjectMapper objectMapper;

    @Override
    public NodeType getType() {
        return NodeType.MCP;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        Map<String, Object> nodeData = context.getCurrentNodeData() != null
                ? context.getCurrentNodeData() : Map.of();

        Long serverId = WorkflowNodeDataUtils.parseLongId(nodeData.get("mcpServerId"));
        McpServer server = null;
        if (serverId != null) {
            server = mcpServerService.getById(serverId);
        }
        if (server == null) {
            String serverName = WorkflowNodeDataUtils.parseString(nodeData.get("mcpServerName"));
            if (serverName == null) {
                serverName = WorkflowNodeDataUtils.parseString(nodeData.get("serverName"));
            }
            if (serverName != null && !serverName.isBlank()) {
                server = mcpServerService.getOne(new LambdaQueryWrapper<McpServer>()
                        .eq(McpServer::getName, serverName)
                        .last("LIMIT 1"));
            }
        }
        if (server == null) {
            throw new IllegalArgumentException("MCP 节点未找到对应服务，请配置 mcpServerName 或 mcpServerId");
        }

        String toolName = WorkflowNodeDataUtils.parseString(nodeData.get("toolName"));
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("MCP 节点未配置 toolName");
        }

        Map<String, Object> toolParams = buildToolParams(nodeData, context);
        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(toolParams);
        } catch (Exception e) {
            throw new IllegalArgumentException("MCP 参数序列化失败: " + e.getMessage(), e);
        }

        final McpServer mcpServer = server;
        List<ToolBase> callbacks = mcpClientService.getAllToolCallbacks(mcpServer.getId());
        ToolBase target = callbacks.stream()
                .filter(cb -> toolName.equals(cb.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "MCP 服务 [" + mcpServer.getName() + "] 中未找到工具: " + toolName));

        log.info("[McpNodeProcessor] 调用 MCP 工具: server={}, tool={}, args={}",
                mcpServer.getName(), toolName, argsJson);

        // AgentScope: 使用 ToolCallParam 替代 Spring AI ToolCallParam
        Map<String, Object> args = new HashMap<>();
        try {
            args.putAll(objectMapper.readValue(argsJson,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)));
        } catch (Exception e) {
            log.warn("[McpNodeProcessor] 工具参数非标准 JSON，按原文传递: {}", argsJson);
        }
        ToolCallParam toolCallParam = ToolCallParam.builder().input(args).build();
        String result;
        try {
            ToolResultBlock resultBlock = target.callAsync(toolCallParam).block();
            result = extractResultText(resultBlock);
        } catch (Exception e) {
            throw new IllegalArgumentException("MCP 工具执行失败: " + e.getMessage(), e);
        }

        Map<String, Object> outputs = new HashMap<>();
        outputs.put("output", result);
        outputs.put("mcpResult", result);
        outputs.put("toolName", toolName);
        outputs.put("mcpServerName", mcpServer.getName());

        return NodeExecutionResult.builder()
                .nextNodeId(resolveNextNodeId(context))
                .outputs(outputs)
                .streamContent(result)
                .build();
    }

    /**
     * 从 ToolResultBlock 提取文本结果（多重降级处理）
     */
    private String extractResultText(ToolResultBlock resultBlock) {
        if (resultBlock == null) {
            return "";
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (var block : resultBlock.getOutput()) {
                if (block instanceof io.agentscope.core.message.TextBlock tb) {
                    sb.append(tb.getText());
                }
            }
            return sb.toString();
        } catch (Exception e1) {
            try {
                return resultBlock.toString();
            } catch (Exception e2) {
                return "";
            }
        }
    }

  @SuppressWarnings("unchecked")
    private Map<String, Object> buildToolParams(Map<String, Object> nodeData, NodeExecutionContext context) {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> variables = context.getVariables() != null ? context.getVariables() : Map.of();
        Object inputParamsRaw = nodeData.get("inputParams");
        if (inputParamsRaw == null) {
            inputParamsRaw = nodeData.get("input_params");
        }
        if (inputParamsRaw instanceof String str && !str.isBlank() && !"{}".equals(str.trim())) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(str, new TypeReference<Map<String, Object>>() {});
                parsed.forEach((k, v) -> params.put(k, renderParamValue(v, context)));
                return params;
            } catch (Exception e) {
                log.warn("[McpNodeProcessor] 解析 inputParams JSON 失败: {}", e.getMessage());
            }
        }
        if (inputParamsRaw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                String key = row.get("key") != null ? row.get("key").toString() : null;
                if (key == null || key.isBlank()) {
                    continue;
                }
                params.put(key, renderParamValue(row.get("value"), context));
            }
        }
        if (params.isEmpty() && variables != null) {
            params.put("query", variables.get("query"));
            params.put("input", variables.getOrDefault("input", variables.get("query")));
        }
        return params;
    }

    private Object renderParamValue(Object value, NodeExecutionContext context) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            Object resolved = WorkflowMappingUtils.resolveTemplateValue(str, context);
            if (resolved != null) {
                // 变量解析结果为 JSON 字符串时，还原为 Java 对象（防止数组/对象被序列化为字符串传给 MCP）
                if (resolved instanceof String jsonStr) {
                    String trimmed = jsonStr.trim();
                    if ((trimmed.startsWith("[") && trimmed.endsWith("]"))
                            || (trimmed.startsWith("{") && trimmed.endsWith("}"))) {
                        try {
                            return objectMapper.readValue(trimmed, Object.class);
                        } catch (Exception ignored) {
                            // 非合法 JSON，按普通字符串处理
                        }
                    }
                }
                return resolved;
            }
            return WorkflowPromptUtils.render(str, context);
        }
        return value;
    }
}
