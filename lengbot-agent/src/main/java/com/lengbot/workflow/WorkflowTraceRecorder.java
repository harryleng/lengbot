package com.lengbot.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.dto.LlmTraceSpanDTO;
import com.lengbot.vo.WorkflowTestResultVO;
import com.lengbot.entity.Agent;
import com.lengbot.entity.LlmTrace;
import com.lengbot.entity.Message;
import com.lengbot.service.AgentService;
import com.lengbot.service.AgentVersionService;
import com.lengbot.service.LlmTraceService;
import com.lengbot.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流 llm_trace 构建与更新：首次挂起写入、resume/abandon 后刷新完整链路
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowTraceRecorder {

    private static final Set<String> UI_KEYS = Set.of("label", "description", "icon", "color", "position");

    private final LlmTraceService llmTraceService;
    private final ObjectMapper objectMapper;
    private final MessageService messageService;
    private final AgentService agentService;
    private final AgentVersionService agentVersionService;

    /**
     * Chat 工作流首次执行后写入 trace（含挂起态）
     */
    public void recordFromChatExecution(String requestId, Long sessionId, Long agentId, String agentName,
                                        Long userId, String userInput, WorkflowDefinition workflow,
                                        List<Map<String, Object>> events, String result,
                                        long startTimeMs, boolean workflowSuspended) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        try {
            LlmTrace trace = buildTraceEntity(requestId, sessionId, agentId, agentName, userId, userInput,
                    workflow, events, result, startTimeMs, workflowSuspended, null);
            llmTraceService.upsertWorkflowTrace(trace);
        } catch (Exception e) {
            log.error("[WorkflowTraceRecorder] 首次写入 trace 失败: agentId={}, requestId={}, error={}",
                    agentId, requestId, e.getMessage(), e);
        }
    }

    /**
     * Chat 工作流执行异常时写入失败 trace
     */
    public void recordFailureFromChat(String requestId, Long sessionId, Long agentId, String agentName,
                                      Long userId, String replyContent, String errorMessage, long startTimeMs) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        try {
            LlmTrace trace = new LlmTrace();
            trace.setRequestId(requestId);
            trace.setSessionId(sessionId);
            trace.setUserId(userId);
            trace.setAgentId(agentId);
            trace.setAgentName(agentName);
            trace.setTraceSource("workflow");
            trace.setStatus("failed");
            trace.setTotalDurationMs(System.currentTimeMillis() - startTimeMs);
            trace.setReplyContent(replyContent);
            trace.setErrorMessage(errorMessage);
            trace.setSpans("[]");
            llmTraceService.upsertWorkflowTrace(trace);
        } catch (Exception e) {
            log.error("[WorkflowTraceRecorder] 异常 trace 写入失败: agentId={}, requestId={}, error={}",
                    agentId, requestId, e.getMessage(), e);
        }
    }

    /**
     * Chat 人工确认恢复完成后，按 requestId 刷新 trace（补全 confirm 之后节点）
     */
    public void refreshAfterChatResume(Long agentId, Long messageId, WorkflowTestResultVO result) {
        if (messageId == null || result == null || result.getNodeEvents() == null) {
            return;
        }
        try {
            Message msg = messageService.getById(messageId);
            if (msg == null) {
                return;
            }
            RefreshContext ctx = buildRefreshContext(agentId, msg, result.getNodeEvents(), result.getOutput(),
                    Boolean.TRUE.equals(result.getSuspended()), null);
            if (ctx == null) {
                return;
            }
            LlmTrace trace = buildTraceEntity(ctx.requestId(), ctx.sessionId(), ctx.agentId(), ctx.agentName(),
                    ctx.userId(), ctx.userInput(), ctx.workflow(), ctx.events(), ctx.result(),
                    ctx.startTimeMs(), ctx.suspended(), ctx.errorOverride());
            llmTraceService.upsertWorkflowTrace(trace);
        } catch (Exception e) {
            log.error("[WorkflowTraceRecorder] resume 刷新 trace 失败: messageId={}, error={}",
                    messageId, e.getMessage(), e);
        }
    }

    /**
     * Chat 用户放弃人工确认后，将 trace 标记为失败并更新 confirm 节点状态
     */
    @SuppressWarnings("unchecked")
    public void refreshAfterChatAbandon(Long agentId, Long messageId, List<Map<String, Object>> events) {
        if (messageId == null || events == null) {
            return;
        }
        try {
            Message msg = messageService.getById(messageId);
            if (msg == null) {
                return;
            }
            String reply = msg.getContent();
            RefreshContext ctx = buildRefreshContext(agentId, msg, events, reply, false, "用户放弃人工确认");
            if (ctx == null) {
                return;
            }
            LlmTrace trace = buildTraceEntity(ctx.requestId(), ctx.sessionId(), ctx.agentId(), ctx.agentName(),
                    ctx.userId(), ctx.userInput(), ctx.workflow(), ctx.events(), ctx.result(),
                    ctx.startTimeMs(), false, ctx.errorOverride());
            llmTraceService.upsertWorkflowTrace(trace);
        } catch (Exception e) {
            log.error("[WorkflowTraceRecorder] abandon 刷新 trace 失败: messageId={}, error={}",
                    messageId, e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private RefreshContext buildRefreshContext(Long agentId, Message msg, List<Map<String, Object>> events,
                                               String result, boolean suspended, String errorOverride) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (msg.getMetadata() != null && !msg.getMetadata().isBlank()) {
            try {
                meta.putAll(objectMapper.readValue(msg.getMetadata(), Map.class));
            } catch (Exception ignored) {
                // metadata 解析失败时仍尝试从已有 trace 恢复
            }
        }
        String requestId = meta.get("requestId") != null ? String.valueOf(meta.get("requestId")) : null;
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        LlmTrace existing = llmTraceService.findByRequestId(requestId);
        long startTimeMs = existing != null && existing.getCreateTime() != null
                ? existing.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                : System.currentTimeMillis();
        String userInput = extractUserInput(existing);

        Integer configVersion = meta.get("configVersion") instanceof Number n ? n.intValue() : null;
        WorkflowDefinition workflow = agentVersionService.loadWorkflowDefinitionForChat(agentId, configVersion);

        Agent agent = agentService.getById(agentId);
        String agentName = agent != null ? agent.getName() : (existing != null ? existing.getAgentName() : null);
        Long userId = existing != null ? existing.getUserId() : (agent != null ? agent.getUserId() : null);

        return new RefreshContext(requestId, msg.getSessionId(), agentId, agentName, userId, userInput,
                workflow, events, result, startTimeMs, suspended, errorOverride);
    }

    private LlmTrace buildTraceEntity(String requestId, Long sessionId, Long agentId, String agentName, Long userId,
                                      String userInput, WorkflowDefinition workflow, List<Map<String, Object>> events,
                                      String result, long startTimeMs, boolean workflowSuspended,
                                      String errorOverride) throws Exception {
        Map<String, WorkflowNode> nodeDefMap = new HashMap<>();
        if (workflow != null && workflow.getNodes() != null) {
            for (WorkflowNode node : workflow.getNodes()) {
                nodeDefMap.put(node.getId(), node);
            }
        }

        List<LlmTraceSpanDTO> spans = new ArrayList<>();
        long totalDurationMs = System.currentTimeMillis() - startTimeMs;
        boolean hasError = events.stream().anyMatch(e ->
                "workflow_node_complete".equals(e.get("type")) && Boolean.FALSE.equals(e.get("success")));
        String rootStatus = errorOverride != null ? "failed"
                : (hasError ? "failed" : (workflowSuspended ? "running" : "completed"));

        Map<String, Object> rootAttrs = buildRootAttrs(userInput, workflow, events, result, nodeDefMap);
        spans.add(LlmTraceSpanDTO.of("workflow_run", null, "workflow_run",
                startTimeMs, totalDurationMs, rootStatus, rootAttrs));

        Map<String, Map<String, Object>> startEvents = new HashMap<>();
        Map<String, Map<String, Object>> completeEvents = new HashMap<>();
        for (Map<String, Object> event : events) {
            String type = (String) event.get("type");
            String nodeId = (String) event.get("nodeId");
            if (nodeId == null) {
                continue;
            }
            if ("workflow_node_start".equals(type)) {
                startEvents.put(nodeId, event);
            } else if ("workflow_node_complete".equals(type)) {
                completeEvents.put(nodeId, event);
            }
        }

        int[] llmTokenAgg = {0, 0};
        List<Map<String, Object>> orderedCompletes = completeEvents.values().stream()
                .sorted(Comparator.comparingInt(c -> {
                    Object si = c.get("stepIndex");
                    return si instanceof Number ? ((Number) si).intValue() : Integer.MAX_VALUE;
                }))
                .toList();
        for (Map<String, Object> complete : orderedCompletes) {
            String nodeId = (String) complete.get("nodeId");
            if (nodeId == null) {
                continue;
            }
            buildNodeSpan(nodeId, startEvents.get(nodeId), complete, nodeDefMap.get(nodeId),
                    spans, llmTokenAgg, startTimeMs);
        }

        String errorMessage = errorOverride;
        if (errorMessage == null && "failed".equals(rootStatus)) {
            for (Map<String, Object> event : events) {
                if ("workflow_node_complete".equals(event.get("type")) && Boolean.FALSE.equals(event.get("success"))) {
                    Object msg = event.get("message");
                    if (msg != null && !String.valueOf(msg).isBlank()) {
                        errorMessage = String.valueOf(msg);
                        break;
                    }
                }
            }
        }

        LlmTrace trace = new LlmTrace();
        trace.setRequestId(requestId);
        trace.setSessionId(sessionId);
        trace.setUserId(userId);
        trace.setAgentId(agentId);
        trace.setAgentName(agentName);
        trace.setModel(null);
        trace.setTraceSource("workflow");
        trace.setStatus(rootStatus);
        trace.setInputTokens(llmTokenAgg[0]);
        trace.setOutputTokens(llmTokenAgg[1]);
        trace.setTotalTokens(llmTokenAgg[0] + llmTokenAgg[1]);
        trace.setToolCallCount(0);
        trace.setTotalDurationMs(totalDurationMs);
        trace.setReplyContent(result);
        trace.setErrorMessage(errorMessage);
        trace.setSpans(objectMapper.writeValueAsString(spans));
        return trace;
    }

    private Map<String, Object> buildRootAttrs(String userInput, WorkflowDefinition workflow,
                                               List<Map<String, Object>> events, String result,
                                               Map<String, WorkflowNode> nodeDefMap) {
        Map<String, Object> rootAttrs = new HashMap<>();
        rootAttrs.put("nodeCount", nodeDefMap.size());
        rootAttrs.put("eventCount", events.size());
        rootAttrs.put("resultPreview", result != null ? truncate(result, 200) : "");
        if (userInput != null && !userInput.isBlank()) {
            rootAttrs.put("userInput", userInput);
        }
        if (workflow != null && workflow.getEdges() != null) {
            List<Map<String, Object>> edgeList = new ArrayList<>();
            for (WorkflowEdge edge : workflow.getEdges()) {
                Map<String, Object> edgeMap = new LinkedHashMap<>();
                edgeMap.put("id", edge.getId());
                edgeMap.put("source", edge.getSource());
                edgeMap.put("target", edge.getTarget());
                if (edge.getLabel() != null) {
                    edgeMap.put("label", edge.getLabel());
                }
                if (edge.getSourceHandle() != null) {
                    edgeMap.put("sourceHandle", edge.getSourceHandle());
                }
                edgeList.add(edgeMap);
            }
            rootAttrs.put("edges", edgeList);
        }
        if (workflow != null && workflow.getNodes() != null) {
            List<Map<String, Object>> nodeList = new ArrayList<>();
            for (WorkflowNode n : workflow.getNodes()) {
                Map<String, Object> nodeMap = new LinkedHashMap<>();
                nodeMap.put("id", n.getId());
                nodeMap.put("type", n.getType() != null ? n.getType().getCode() : "");
                if (n.getPosition() != null) {
                    nodeMap.put("position", n.getPosition());
                }
                if (n.getParentNode() != null) {
                    nodeMap.put("parentNode", n.getParentNode());
                }
                if (n.getData() != null && !n.getData().isEmpty()) {
                    nodeMap.put("data", new LinkedHashMap<>(n.getData()));
                }
                String label = n.getData() != null ? (String) n.getData().get("label") : null;
                nodeMap.put("label", label != null ? label : n.getId());
                nodeList.add(nodeMap);
            }
            rootAttrs.put("nodes", nodeList);
        }
        return rootAttrs;
    }

    @SuppressWarnings("unchecked")
    private void buildNodeSpan(String nodeId, Map<String, Object> start, Map<String, Object> complete,
                               WorkflowNode nodeDef, List<LlmTraceSpanDTO> spans, int[] llmTokenAgg,
                               long workflowStartTime) {
        String nodeType = (String) complete.get("nodeType");
        String nodeLabel = (String) complete.get("nodeLabel");
        boolean success = Boolean.TRUE.equals(complete.get("success"));
        Object durationObj = complete.get("durationMs");
        long durationMs = durationObj instanceof Number ? ((Number) durationObj).longValue() : 0;
        int stepIndex = complete.get("stepIndex") instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
        long nodeStartMs;
        if (start != null && start.get("startTime") instanceof Number st) {
            nodeStartMs = st.longValue();
        } else if (stepIndex != Integer.MAX_VALUE) {
            nodeStartMs = workflowStartTime + stepIndex;
        } else {
            nodeStartMs = System.currentTimeMillis() - durationMs;
        }

        Map<String, Object> nodeConfig = extractNodeConfig(nodeDef, nodeType);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("nodeType", nodeType);
        attrs.put("nodeLabel", nodeLabel != null ? nodeLabel : nodeId);
        if (stepIndex != Integer.MAX_VALUE) {
            attrs.put("stepIndex", stepIndex);
        }
        if (!nodeConfig.isEmpty()) {
            attrs.put("config", nodeConfig);
        }
        if (start != null && start.containsKey("input")) {
            attrs.put("input", start.get("input"));
        }
        if (complete.containsKey("outputs")) {
            attrs.put("outputs", complete.get("outputs"));
        }
        if (complete.containsKey("traceData")) {
            Object existing = attrs.get("outputs");
            if (existing instanceof Map<?, ?> existingMap) {
                Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) existingMap);
                merged.putAll((Map<String, Object>) complete.get("traceData"));
                attrs.put("outputs", merged);
            } else {
                attrs.put("outputs", complete.get("traceData"));
            }
        }
        if (complete.containsKey("detail")) {
            attrs.put("detail", truncate(String.valueOf(complete.get("detail")), 500));
        }
        attrs.put("success", success);
        if (complete.containsKey("message")) {
            attrs.put("message", complete.get("message"));
        }

        String spanStatus = success ? "completed" : "failed";
        spans.add(LlmTraceSpanDTO.of("node:" + nodeId, "workflow_run", "node:" + nodeType,
                nodeStartMs, durationMs, spanStatus, attrs));

        if ("llm".equals(nodeType) || "classifier".equals(nodeType)) {
            buildLlmSpan(nodeId, nodeConfig, complete, nodeStartMs, durationMs, success, spans, llmTokenAgg);
        }
    }

    private void buildLlmSpan(String nodeId, Map<String, Object> nodeConfig, Map<String, Object> complete,
                              long nodeStartMs, long durationMs, boolean success,
                              List<LlmTraceSpanDTO> spans, int[] llmTokenAgg) {
        String model = extractString(nodeConfig, "model");
        String sysPrompt = extractString(nodeConfig, "sysPrompt");
        String promptTemplate = extractString(nodeConfig, "promptTemplate");

        Map<String, Object> llmAttrs = new HashMap<>();
        if (model != null) {
            llmAttrs.put("model", model);
        }
        if (sysPrompt != null) {
            llmAttrs.put("sysPrompt", truncate(sysPrompt, 300));
        }
        if (promptTemplate != null) {
            llmAttrs.put("promptTemplate", truncate(promptTemplate, 300));
        }
        llmAttrs.put("streaming", Boolean.TRUE.equals(nodeConfig.get("enableStreaming")));

        int inputTokens = 0;
        int outputTokens = 0;
        Object traceDataObj = complete.get("traceData");
        if (traceDataObj instanceof Map<?, ?> traceMap) {
            Object inVal = traceMap.get("inputTokens");
            Object outVal = traceMap.get("outputTokens");
            if (inVal instanceof Number n) {
                inputTokens = n.intValue();
            }
            if (outVal instanceof Number n) {
                outputTokens = n.intValue();
            }
        }
        llmAttrs.put("inputTokens", inputTokens);
        llmAttrs.put("outputTokens", outputTokens);
        llmTokenAgg[0] += inputTokens;
        llmTokenAgg[1] += outputTokens;

        spans.add(LlmTraceSpanDTO.of("llm:" + nodeId, "node:" + nodeId, "llm_call",
                nodeStartMs, durationMs, success ? "completed" : "failed", llmAttrs));
    }

    private Map<String, Object> extractNodeConfig(WorkflowNode nodeDef, String nodeType) {
        if (nodeDef == null || nodeDef.getData() == null) {
            return Map.of();
        }
        Map<String, Object> data = nodeDef.getData();
        Map<String, Object> config = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            String key = e.getKey();
            if (UI_KEYS.contains(key) || "debugStatus".equals(key)) {
                continue;
            }
            if (e.getValue() != null) {
                config.put(key, e.getValue());
            }
        }
        return config;
    }

    @SuppressWarnings("unchecked")
    private String extractUserInput(LlmTrace existing) {
        if (existing == null || existing.getSpans() == null || existing.getSpans().isBlank()) {
            return null;
        }
        try {
            List<LlmTraceSpanDTO> spans = objectMapper.readValue(existing.getSpans(), new TypeReference<>() {});
            for (LlmTraceSpanDTO span : spans) {
                if ("workflow_run".equals(span.getName()) && span.getAttributes() != null) {
                    Object userInput = span.getAttributes().get("userInput");
                    return userInput != null ? String.valueOf(userInput) : null;
                }
            }
        } catch (Exception e) {
            log.debug("[WorkflowTraceRecorder] 解析已有 userInput 失败: {}", e.getMessage());
        }
        return null;
    }

    private static String extractString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private record RefreshContext(String requestId, Long sessionId, Long agentId, String agentName, Long userId,
                                  String userInput, WorkflowDefinition workflow, List<Map<String, Object>> events,
                                  String result, long startTimeMs, boolean suspended, String errorOverride) {
    }
}
