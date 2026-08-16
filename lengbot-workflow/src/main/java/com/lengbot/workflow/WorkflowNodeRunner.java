package com.lengbot.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.enums.NodeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 单节点执行器（供主流程与子图复用，避免 SubgraphExecutor 依赖 WorkflowExecutorService 产生循环依赖）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowNodeRunner {

    private final NodeProcessorRegistry registry;
    private final ObjectMapper objectMapper;

    /**
     * 在已有上下文中执行指定节点并合并输出到 variables
     * <p>当上下文携带 workflowEvents 时，自动推送子节点执行事件（用于循环/批处理容器内部节点）</p>
     */
    public NodeExecutionResult executeNodeInContext(NodeExecutionContext context, String nodeId) {
        WorkflowDefinition workflow = context.getWorkflow();
        WorkflowNode node = workflow.getNode(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在: " + nodeId);
        }
        context.setCurrentNodeId(nodeId);
        context.setCurrentNodeData(node.getData());

        String nodeLabel = resolveNodeLabel(node);
        String nodeTypeCode = node.getType() != null ? node.getType().getCode() : "";
        boolean emitEvents = context.getWorkflowEvents() != null;
        long nodeStartMs = 0;

        // 1. 推送子节点开始事件
        if (emitEvents) {
            nodeStartMs = System.currentTimeMillis();
            String executionId = buildExecutionId(context, nodeId);
            Map<String, Object> startEvent = new HashMap<>();
            startEvent.put("type", "workflow_node_start");
            startEvent.put("nodeId", nodeId);
            startEvent.put("executionId", executionId);
            startEvent.put("nodeType", nodeTypeCode);
            startEvent.put("nodeLabel", nodeLabel);
            if (context.getParentNodeId() != null) {
                startEvent.put("parentNodeId", context.getParentNodeId());
            }
            if (context.getIterationIndex() != null) {
                startEvent.put("iterationIndex", context.getIterationIndex());
            }
            Map<String, Object> inputPreview = buildNodeInputPreview(node, context);
            if (!inputPreview.isEmpty()) {
                startEvent.put("input", inputPreview);
            }
            emitEvent(context, startEvent);
        }

        NodeProcessor processor = registry.getProcessor(node.getType());
        log.debug("[WorkflowNodeRunner] 执行节点: nodeId={}, type={}", nodeId, node.getType());

        boolean nodeSuccess = true;
        String completeMessage = "执行完成";
        NodeExecutionResult nodeResult = null;
        Exception nodeFailure = null;

        try {
            NodeResilienceEventContext resilienceCtx = NodeResilienceEventContext.builder()
                    .nodeId(nodeId)
                    .nodeLabel(nodeLabel)
                    .nodeType(nodeTypeCode)
                    .executionId(emitEvents ? buildExecutionId(context, nodeId) : null)
                    .parentNodeId(context.getParentNodeId())
                    .iterationIndex(context.getIterationIndex())
                    .eventEmitter(emitEvents ? e -> emitEvent(context, e) : null)
                    .build();
            // 带超时 + 重试的节点执行
            nodeResult = NodeTimeoutRetryHelper.executeWithTimeoutAndRetry(
                    nodeId, node.getType(), node.getData(),
                    () -> processor.execute(context),
                    resilienceCtx);
            if (nodeResult.getOutputs() != null) {
                context.getNodeOutputs().put(nodeId, nodeResult.getOutputs());
                WorkflowVariableScope.mergeNodeOutputs(context, node, nodeResult.getOutputs());
            }
        } catch (Exception e) {
            nodeSuccess = false;
            nodeFailure = e;
            completeMessage = NodeResilienceMessageFormatter.formatUserMessage(e);
            log.error("[WorkflowNodeRunner] 子图节点执行失败: nodeId={}, error={}", nodeId, e.getMessage(), e);
            NodeExecutionResult observability = ParameterExtractParseException.toObservabilityResult(e);
            if (observability != null) {
                nodeResult = observability;
            }
        }

        // 2. 推送子节点完成事件
        if (emitEvents) {
            String executionId = buildExecutionId(context, nodeId);
            Map<String, Object> completeEvent = new HashMap<>();
            completeEvent.put("type", "workflow_node_complete");
            completeEvent.put("nodeId", nodeId);
            completeEvent.put("executionId", executionId);
            completeEvent.put("nodeType", nodeTypeCode);
            completeEvent.put("nodeLabel", nodeLabel);
            completeEvent.put("message", completeMessage);
            completeEvent.put("success", nodeSuccess);
            completeEvent.put("durationMs", System.currentTimeMillis() - nodeStartMs);
            if (!nodeSuccess) {
                completeEvent.put("userMessage", completeMessage);
                completeEvent.put("failureReason", NodeResilienceMessageFormatter.resolveFailureReason(nodeFailure));
            }
            if (context.getParentNodeId() != null) {
                completeEvent.put("parentNodeId", context.getParentNodeId());
            }
            if (context.getIterationIndex() != null) {
                completeEvent.put("iterationIndex", context.getIterationIndex());
            }
            if (nodeResult != null && nodeResult.getOutputs() != null && !nodeResult.getOutputs().isEmpty()) {
                if ("tool".equals(nodeTypeCode)) {
                    Map<String, Object> toolOutputs = WorkflowToolEventOutputs.build(nodeResult.getOutputs(), objectMapper);
                    if (!toolOutputs.isEmpty()) {
                        completeEvent.put("outputs", toolOutputs);
                    }
                } else {
                    completeEvent.put("outputs", nodeResult.getOutputs());
                }
                Object toolName = nodeResult.getOutputs().get("toolName");
                if (toolName != null && !String.valueOf(toolName).isBlank()) {
                    completeEvent.put("toolName", toolName);
                }
            }
            if (nodeResult != null && nodeResult.getTraceData() != null && !nodeResult.getTraceData().isEmpty()) {
                completeEvent.put("traceData", nodeResult.getTraceData());
            }
            emitEvent(context, completeEvent);
        }

        if (!nodeSuccess) {
            throw new IllegalStateException("子图节点执行失败: " + nodeId + ": " + completeMessage);
        }

        if (nodeResult.isFinished() || node.getType() == NodeType.END) {
            return NodeExecutionResult.builder()
                    .nextNodeId(null)
                    .outputs(nodeResult.getOutputs())
                    .streamContent(nodeResult.getStreamContent())
                    .finished(true)
                    .build();
        }
        String nextNodeId = nodeResult.getNextNodeId();
        if (nextNodeId == null) {
            List<WorkflowEdge> outEdges = workflow.getOutEdges(nodeId);
            nextNodeId = outEdges.isEmpty() ? null : outEdges.get(0).getTarget();
            return NodeExecutionResult.builder()
                    .nextNodeId(nextNodeId)
                    .outputs(nodeResult.getOutputs())
                    .streamContent(nodeResult.getStreamContent())
                    .finished(false)
                    .build();
        }
        return nodeResult;
    }

    private void emitEvent(NodeExecutionContext context, Map<String, Object> event) {
        if (context.getWorkflowEvents() != null) {
            synchronized (context.getWorkflowEvents()) {
                context.getWorkflowEvents().add(event);
            }
        }
        if (context.getOnEvent() != null) {
            synchronized (context.getOnEvent()) {
                context.getOnEvent().accept(event);
            }
        }
    }

    private String buildExecutionId(NodeExecutionContext context, String nodeId) {
        String parent = context.getParentNodeId() != null ? context.getParentNodeId() : "-";
        String iteration = context.getIterationIndex() != null ? String.valueOf(context.getIterationIndex()) : "-";
        return parent + ":" + nodeId + ":" + iteration;
    }

    private String resolveNodeLabel(WorkflowNode node) {
        if (node.getData() != null && node.getData().containsKey("label")) {
            Object label = node.getData().get("label");
            if (label != null && !label.toString().isEmpty()) {
                return label.toString();
            }
        }
        return node.getType() != null ? node.getType().getDesc() : "节点";
    }

    private Map<String, Object> buildNodeInputPreview(WorkflowNode node, NodeExecutionContext context) {
        Map<String, Object> preview = new HashMap<>();
        if (node == null || context == null || node.getData() == null) {
            return preview;
        }
        if (node.getType() == NodeType.RETRIEVAL) {
            String query = WorkflowVariableUtils.resolveInputText(
                    WorkflowNodeDataUtils.parseString(node.getData().get("inputVariable")),
                    context.getVariables(),
                    context.getUserInput());
            preview.put("query", query);
            Object knowledgeId = node.getData().get("knowledgeId");
            if (knowledgeId != null) {
                preview.put("knowledgeId", knowledgeId);
            }
            return preview;
        }
        Object inputParams = node.getData().get("inputParams");
        if (inputParams == null) {
            inputParams = node.getData().get("input_params");
        }
        if (inputParams instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                Object key = row.get("key");
                if (key == null || key.toString().isBlank()) {
                    continue;
                }
                preview.put(key.toString(), WorkflowMappingUtils.resolveTemplateValue(row.get("value"), context));
            }
        }
        return preview;
    }
}
