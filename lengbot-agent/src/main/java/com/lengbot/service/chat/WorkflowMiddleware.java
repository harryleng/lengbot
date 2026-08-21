package com.lengbot.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.enums.AgentType;
import com.lengbot.enums.MessageRole;
import com.lengbot.service.AgentVersionService;
import com.lengbot.util.SensitiveWordFilter;
import com.lengbot.workflow.WorkflowDefinition;
import com.lengbot.workflow.WorkflowExecutorService;
import com.lengbot.workflow.NodeResilienceMessageFormatter;
import com.lengbot.workflow.WorkflowTraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.function.Consumer;

import static com.lengbot.service.chat.ToolEventGenerator.*;

/**
 * 工作流中间件
 * <p>WORKFLOW 类型 Agent 执行工作流 DAG，跳过后续 LLM 中间件</p>
 *
 * @author lw
 * @since 2026-05-24
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowMiddleware implements ChatMiddleware {

    private final WorkflowExecutorService workflowExecutor;
    private final AgentVersionService agentVersionService;
    private final MessageMiddleware messageMiddleware;
    private final TraceMiddleware traceMiddleware;
    private final WorkflowTraceRecorder workflowTraceRecorder;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;

    @Override
    public Flux<String> execute(ChatContext ctx, ChatMiddlewareChain next) {
        if (ctx.getAgent() == null || ctx.getAgent().getAgentType() != AgentType.WORKFLOW) {
            return next.proceed(ctx);
        }

        log.info("[WorkflowMiddleware] 开始执行工作流: agentId={}, sessionId={}, configVersion={}",
                ctx.getAgent().getId(), ctx.getSessionId(), ctx.getRequest().getConfigVersion());

        // 1. 持久化用户消息（工作流不走 MessageMiddleware 链）；重新生成时不重复落库
        if (Boolean.TRUE.equals(ctx.getRequest().getRegenerate())) {
            Long deleteId = ctx.getRequest().getDeleteAssistantMessageId();
            if (deleteId != null) {
                messageMiddleware.deleteAssistantMessageById(ctx.getSessionId(), deleteId);
            }
        } else {
            messageMiddleware.saveMessage(ctx.getSessionId(), MessageRole.USER, ctx.getRequest().getMessage());
        }

        return Flux.<String>create(sink -> Schedulers.boundedElastic().schedule(() -> {
            long t0 = System.currentTimeMillis();
            try {
                List<Map<String, Object>> workflowEvents = Collections.synchronizedList(new ArrayList<>());
                Object workflowEmitLock = new Object();
                Consumer<Map<String, Object>> emit = event -> {
                    synchronized (workflowEmitLock) {
                        ctx.getWorkflowEventsList().add(event);
                        try {
                            sink.next(STATUS_PREFIX + objectMapper.writeValueAsString(event));
                        } catch (Exception ex) {
                            sink.error(ex);
                        }
                    }
                };

                // LLM 流式回调：逐 token 推送到前端
                final boolean[] streamed = {false};
                Consumer<String> streamChunk = chunk -> {
                    streamed[0] = true;
                    try {
                        Map<String, Object> chunkEvent = new LinkedHashMap<>();
                        chunkEvent.put("type", "workflow_llm_chunk");
                        chunkEvent.put("content", chunk);
                        sink.next(STATUS_PREFIX + objectMapper.writeValueAsString(chunkEvent));
                    } catch (Exception ex) {
                        log.warn("[WorkflowMiddleware] 流式 chunk 推送失败: {}", ex.getMessage());
                    }
                };

                WorkflowDefinition workflow = agentVersionService.loadWorkflowDefinitionForChat(
                        ctx.getAgent().getId(), ctx.getRequest().getConfigVersion());

                String executorResult;
                if (workflow == null || workflow.getNodes() == null || workflow.getNodes().isEmpty()) {
                    executorResult = "工作流尚未发布或为空，请先在编排页发布工作流，或切换到暂存草稿调试";
                } else {
                    executorResult = workflowExecutor.executeWithDefinition(
                            ctx.getAgent(),
                            workflow,
                            ctx.getSessionId(),
                            ctx.getRequest().getMessage(),
                            workflowEvents,
                            emit,
                            null,
                            streamChunk
                    );
                }

                boolean workflowSuspended = workflowEvents.stream()
                        .anyMatch(e -> "workflow_suspended".equals(e.get("type")));

                String result;
                if (workflowSuspended) {
                    // 挂起：仅保留 confirm 前已产生的流式正文，不把占位文案当作「已完成回复」
                    result = executorResult != null ? executorResult : "";
                    if (!result.isEmpty()) {
                        SensitiveWordFilter.FilterResult filtered = SensitiveWordFilter.filterAiOutput(
                                result, ctx.getConfigMap(), ctx.getAgent().getId(), ctx.getSessionId());
                        result = filtered.text();
                        ctx.getFullReply().append(result);
                    }
                } else {
                    result = resolveAssistantContent(executorResult, workflowEvents);
                    if (result != null && !result.isEmpty()) {
                        SensitiveWordFilter.FilterResult filtered = SensitiveWordFilter.filterAiOutput(
                                result, ctx.getConfigMap(), ctx.getAgent().getId(), ctx.getSessionId());
                        result = filtered.text();
                        ctx.getFullReply().append(result);
                    }
                }

                Map<String, Object> metadataMap = new LinkedHashMap<>();
                metadataMap.put("workflowEvents", workflowEvents);
                if (workflowSuspended) {
                    metadataMap.put("workflowSuspended", true);
                    for (int i = workflowEvents.size() - 1; i >= 0; i--) {
                        Map<String, Object> ev = workflowEvents.get(i);
                        if ("workflow_suspended".equals(ev.get("type")) && ev.get("runId") != null) {
                            metadataMap.put("workflowRunId", ev.get("runId"));
                        }
                        if ("workflow_confirm_required".equals(ev.get("type")) && ev.get("confirmForm") != null) {
                            metadataMap.put("workflowConfirmForm", ev.get("confirmForm"));
                        }
                    }
                }
                if (ctx.getRequestId() != null && !ctx.getRequestId().isBlank()) {
                    metadataMap.put("requestId", ctx.getRequestId());
                }
                if (ctx.getRequest().getConfigVersion() != null) {
                    metadataMap.put("configVersion", ctx.getRequest().getConfigVersion());
                }
                if (workflowEvents.stream().anyMatch(e -> "workflow_node_complete".equals(e.get("type"))
                        && Boolean.FALSE.equals(e.get("success")))) {
                    metadataMap.put("workflowFailed", true);
                }
                ctx.getRagMetadataHolder()[0] = objectMapper.writeValueAsString(metadataMap);

                // 流式已逐 token 推送，挂起时不补发正文（避免前端误判为「已完成」）
                if (!workflowSuspended && !streamed[0] && result != null && !result.isEmpty()) {
                    sink.next(result);
                }
                sink.next(METADATA_PREFIX + ctx.getRagMetadataHolder()[0]);
                sink.complete();

                // 2. 助手消息由 ChatServiceImpl.buildDoneEvent 统一落库，避免重复写入两条 assistant 消息
                String contentToSave = ctx.getFullReply().toString();
                if (contentToSave.isEmpty() && !workflowEvents.isEmpty() && !workflowSuspended) {
                    contentToSave = resolveAssistantContent(null, workflowEvents);
                    if (!contentToSave.isEmpty()) {
                        ctx.getFullReply().append(contentToSave);
                    }
                }

                // 3. 异步写入工作流调用链 trace
                final String traceResult = result;
                final WorkflowDefinition traceWorkflow = workflow;
                taskExecutor.execute(() -> workflowTraceRecorder.recordFromChatExecution(
                        ctx.getRequestId(),
                        ctx.getSessionId(),
                        ctx.getAgent().getId(),
                        ctx.getAgent().getName(),
                        ctx.getAgent().getUserId(),
                        ctx.getRequest() != null ? ctx.getRequest().getMessage() : null,
                        traceWorkflow,
                        workflowEvents,
                        traceResult,
                        t0,
                        workflowSuspended));

                if (workflowSuspended) {
                    String suspendedRunId = workflowEvents.stream()
                            .filter(e -> "workflow_suspended".equals(e.get("type")))
                            .map(e -> e.get("runId"))
                            .filter(Objects::nonNull)
                            .map(Object::toString)
                            .reduce((first, second) -> second)
                            .orElse("");
                    log.info("[WorkflowMiddleware] 工作流已挂起等待人工确认: agentId={}, nodes={}, runId={}",
                            ctx.getAgent().getId(), workflowEvents.size(), suspendedRunId);
                } else {
                    log.info("[WorkflowMiddleware] 工作流执行完成: agentId={}, nodes={}, resultLength={}",
                            ctx.getAgent().getId(), workflowEvents.size(), ctx.getFullReply().length());
                }
            } catch (Exception e) {
                log.error("[WorkflowMiddleware] 工作流执行失败: agentId={}, error={}",
                        ctx.getAgent().getId(), e.getMessage(), e);
                String userMsg = NodeResilienceMessageFormatter.formatUserMessage(e);
                Map<String, Object> metadataMap = new LinkedHashMap<>();
                metadataMap.put("workflowFailed", true);
                metadataMap.put("workflowError", Map.of(
                        "message", userMsg,
                        "failureReason", NodeResilienceMessageFormatter.resolveFailureReason(e)
                ));
                if (ctx.getRequestId() != null && !ctx.getRequestId().isBlank()) {
                    metadataMap.put("requestId", ctx.getRequestId());
                }
                try {
                    sink.next(METADATA_PREFIX + objectMapper.writeValueAsString(metadataMap));
                } catch (Exception jsonEx) {
                    log.warn("[WorkflowMiddleware] 失败 metadata 序列化异常: {}", jsonEx.getMessage());
                }
                sink.complete();

                taskExecutor.execute(() -> workflowTraceRecorder.recordFailureFromChat(
                        ctx.getRequestId(),
                        ctx.getSessionId(),
                        ctx.getAgent().getId(),
                        ctx.getAgent().getName(),
                        ctx.getAgent().getUserId(),
                        userMsg,
                        e.getMessage(),
                        t0));
            }
        })).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 无模型文本输出时，从工作流节点结果生成可展示的回复摘要
     */
    @SuppressWarnings("unchecked")
    static String resolveAssistantContent(String result, List<Map<String, Object>> events) {
        if (result != null && !result.isBlank()) {
            return result;
        }
        if (events == null || events.isEmpty()) {
            return "";
        }
        // 挂起等待人工确认时，不回溯更早节点的 LLM 输出当作「最终回复」
        boolean suspended = events.stream().anyMatch(e -> "workflow_suspended".equals(e.get("type")));
        if (suspended) {
            for (int i = events.size() - 1; i >= 0; i--) {
                Map<String, Object> ev = events.get(i);
                if (!"workflow_confirm_required".equals(ev.get("type"))) {
                    continue;
                }
                Object form = ev.get("confirmForm");
                if (form instanceof Map<?, ?> formMap) {
                    Object msg = formMap.get("message");
                    if (msg != null && !msg.toString().isBlank()) {
                        return msg.toString();
                    }
                }
            }
            return "";
        }
        // 1. 节点失败时不写入对话正文，由前端根据 workflowEvents 展示专用错误样式
        for (int i = events.size() - 1; i >= 0; i--) {
            Map<String, Object> e = events.get(i);
            if (!"workflow_node_complete".equals(e.get("type"))) {
                continue;
            }
            if (Boolean.FALSE.equals(e.get("success"))) {
                return "";
            }
        }
        // 2. 从最后一个成功节点提取输出
        for (int i = events.size() - 1; i >= 0; i--) {
            Map<String, Object> e = events.get(i);
            if (!"workflow_node_complete".equals(e.get("type"))) {
                continue;
            }
            if (Boolean.FALSE.equals(e.get("success"))) {
                continue;
            }
            Object detail = e.get("detail");
            if (detail != null && !detail.toString().isBlank()) {
                return detail.toString();
            }
            Object outputs = e.get("outputs");
            if (outputs instanceof Map<?, ?> outputMap && !outputMap.isEmpty()) {
                for (String key : List.of("result", "output", "text", "answer", "llmOutput")) {
                    Object val = outputMap.get(key);
                    if (val != null && !val.toString().isBlank()) {
                        return val.toString();
                    }
                }
                // 跳过 classifier 等中间节点的结构化输出，继续向上查找
                String nodeType = e.get("nodeType") != null ? e.get("nodeType").toString() : "";
                if ("classifier".equals(nodeType) || "condition".equals(nodeType)) {
                    continue;
                }
                return outputMap.values().iterator().next().toString();
            }
        }
        return "工作流已执行完成";
    }
}
