package com.lengbot.workflow.processor;

import com.lengbot.util.ModelCalls;

import com.lengbot.common.BizException;
import com.lengbot.enums.ErrorCode;
import com.lengbot.enums.NodeType;
import com.lengbot.model.ModelFactory;
import com.lengbot.util.LlmTraceContext;
import com.lengbot.util.Msgs;
import com.lengbot.workflow.NodeExecutionContext;
import com.lengbot.workflow.NodeExecutionResult;
import com.lengbot.workflow.NodeProcessor;
import com.lengbot.workflow.NodeTimeoutRetryHelper;
import com.lengbot.workflow.WorkflowChatExposure;
import com.lengbot.workflow.WorkflowPromptUtils;
import com.lengbot.workflow.WorkflowEdge;
import com.lengbot.workflow.WorkflowNodeDataUtils;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * LLM节点处理器
 * <p>调用大模型生成响应，支持流式输出和对话记忆</p>
 *
 * @author finch
 * @since 2026-05-24
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmNodeProcessor implements NodeProcessor {

    private final ModelFactory modelFactory;

    @Override
    public NodeType getType() {
        return NodeType.LLM;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeExecutionResult execute(NodeExecutionContext context) {
        // 1. 从节点数据获取配置
        Map<String, Object> nodeData = context.getCurrentNodeData();
        if (nodeData == null) {
            nodeData = new HashMap<>();
        }

        int llmTimeoutSeconds = NodeTimeoutRetryHelper.resolveReadTimeoutSeconds(nodeData, NodeType.LLM);

        // 2. 获取提供商 ID 与具体模型名
        Long providerId = resolveProviderId(nodeData, context.getAgentConfig());
        if (providerId == null) {
            log.warn("[LlmNodeProcessor] 未配置 providerId，节点ID={}, nodeDataKeys={}",
                    context.getCurrentNodeId(), nodeData.keySet());
            String errMsg = "LLM节点未配置模型提供商，请在节点配置中选择提供商和模型";
            return NodeExecutionResult.builder()
                    .streamContent(errMsg)
                    .finished(false)
                    .build();
        }

        String modelName = resolveModelName(nodeData);

        // 3. 构建消息列表（含对话历史）
        List<Msg> messages = buildMessages(nodeData, context);

        // 4. 构建 LLM 上下文快照（用于链路追踪）
        List<Map<String, Object>> llmContextSnapshot = buildLlmContextSnapshot(messages);

        // 5. 调用 LLM（流式 or 非流式）
        Model model = modelFactory.getModel(providerId);
        Consumer<String> streamCallback = WorkflowChatExposure.shouldLlmStreamChunks(
                context.getWorkflow(), context.getCurrentNodeId(), nodeData)
                ? context.getOnStreamChunk()
                : null;
        boolean useStream = streamCallback != null;

        int[] tokenUsage = {0, 0};
        String llmOutput;
        if (useStream) {
            llmOutput = callStream(model, messages, streamCallback, tokenUsage, llmTimeoutSeconds);
        } else {
            llmOutput = callSync(model, messages, tokenUsage, llmTimeoutSeconds);
        }

        // 6. 获取下一个节点
        List<WorkflowEdge> outEdges = context.getWorkflow().getOutEdges(context.getCurrentNodeId());
        String nextNodeId = outEdges.isEmpty() ? null : outEdges.get(0).getTarget();

        // 7. 构建输出
        Map<String, Object> outputs = new HashMap<>();
        outputs.put("llmOutput", llmOutput);

        Map<String, Object> traceData = new HashMap<>();
        traceData.put("llmMessages", llmContextSnapshot);
        traceData.put("inputTokens", tokenUsage[0]);
        traceData.put("outputTokens", tokenUsage[1]);

        log.info("[LlmNodeProcessor] LLM调用完成: nodeId={}, stream={}, outputLength={}",
                context.getCurrentNodeId(), useStream, llmOutput.length());

        return NodeExecutionResult.builder()
                .nextNodeId(nextNodeId)
                .outputs(outputs)
                .traceData(traceData)
                .streamContent(llmOutput)
                .finished(false)
                .build();
    }

    /**
     * 构建 LLM 上下文快照（用于链路追踪）
     */
    private List<Map<String, Object>> buildLlmContextSnapshot(List<Msg> messages) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (Msg m : messages) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("content", m.getTextContent());
            snapshot.add(entry);
        }
        return snapshot;
    }

    /**
     * 构建完整消息列表：系统提示词 + 对话历史 + 用户提示词
     */
    @SuppressWarnings("unchecked")
    private List<Msg> buildMessages(Map<String, Object> nodeData, NodeExecutionContext context) {
        List<Msg> messages = new ArrayList<>();

        // 系统提示词
        String sysPromptRaw = WorkflowNodeDataUtils.parseString(nodeData.get("sysPrompt"));
        if (sysPromptRaw != null) {
            String systemPrompt = WorkflowPromptUtils.render(sysPromptRaw, context);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(Msgs.system(systemPrompt));
            }
        }

        // 对话历史：根据 short_memory 配置决定是否注入及注入轮次
        List<?> rawHistory = resolveHistory(nodeData, context.getVariables());
        if (rawHistory != null) {
            for (Object item : rawHistory) {
                if (item instanceof Map<?, ?> map) {
                    String role = map.get("role") != null ? map.get("role").toString() : "user";
                    String content = map.get("content") != null ? map.get("content").toString() : "";
                    if (content.isBlank()) continue;
                    if ("assistant".equals(role)) {
                        messages.add(Msgs.assistant(content));
                    } else {
                        messages.add(Msgs.user(content));
                    }
                }
            }
        }

        // 用户提示词（模板渲染）
        String promptTemplate = String.valueOf(nodeData.getOrDefault("promptTemplate", "{{input}}"));
        String userPrompt = WorkflowPromptUtils.render(promptTemplate, context);
        log.info("[LlmNodeProcessor] promptTemplate={}, renderedUserPrompt={}, variableKeys={}",
                promptTemplate, userPrompt.length() > 200 ? userPrompt.substring(0, 200) + "..." : userPrompt, context.getVariables().keySet());
        messages.add(Msgs.user(userPrompt));

        return messages;
    }

    /**
     * 根据 short_memory 配置解析对话历史
     * <p>enabled=false → 不注入历史；type=self → 按 round 截断；type=custom → 使用指定变量</p>
     */
    @SuppressWarnings("unchecked")
    private List<?> resolveHistory(Map<String, Object> nodeData, Map<String, Object> variables) {
        Object memoryObj = nodeData.get("short_memory");
        Map<String, Object> memory = parseMap(memoryObj);
        if (memory == null || !Boolean.TRUE.equals(memory.get("enabled"))) {
            return null;
        }

        String type = String.valueOf(memory.getOrDefault("type", "self"));
        List<?> historyList;

        if ("custom".equals(type)) {
            // 自定义缓存：从指定变量读取
            String paramKey = WorkflowNodeDataUtils.parseString(memory.get("paramKey"));
            if (paramKey == null || paramKey.isBlank()) return null;
            Object custom = variables.get(paramKey);
            historyList = custom instanceof List<?> list ? list : null;
        } else {
            // 本节点缓存：从 history_list 读取
            Object historyObj = variables.get("history_list");
            historyList = historyObj instanceof List<?> list ? list : null;
        }

        if (historyList == null || historyList.isEmpty()) return null;

        // 按轮次截断：每轮 = 1 user + 1 assistant = 2 条消息
        int round = 3;
        Object roundObj = memory.get("round");
        if (roundObj instanceof Number n) {
            round = n.intValue();
        }
        int maxMessages = round * 2;
        log.info("[LlmNodeProcessor] 记忆模块: type={}, round={}, 历史消息总数={}, 截断上限={}", type, round, historyList.size(), maxMessages);
        if (historyList.size() <= maxMessages) return historyList;
        return historyList.subList(historyList.size() - maxMessages, historyList.size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(Object obj) {
        if (obj instanceof Map) return (Map<String, Object>) obj;
        if (obj instanceof String s && !s.isBlank()) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Map.class);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 同步调用 LLM（带超时保护）
     */
    private String callSync(Model model, List<Msg> messages,
                            int[] tokenUsage, int timeoutSeconds) {
        try {
            ChatResponse response = CompletableFuture.supplyAsync(() ->
                            LlmTraceContext.callWithoutTrace(() ->
                                    ModelCalls.call(model, messages)))
                    .orTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                    .join();
            accumulateUsage(response, tokenUsage);
            return Msgs.extractText(response);
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                log.error("[LlmNodeProcessor] LLM同步调用超时: timeout={}s", timeoutSeconds);
                throw new BizException(ErrorCode.BAD_REQUEST.getCode(),
                        "LLM调用超时（" + timeoutSeconds + "秒），请检查模型服务状态");
            }
            throw e;
        }
    }

    /**
     * 流式调用 LLM，逐 token 回调（带超时保护）
     */
    private String callStream(Model model, List<Msg> messages,
                              Consumer<String> onChunk,
                              int[] tokenUsage, int timeoutSeconds) {
        StringBuilder full = new StringBuilder();
        try {
            model.stream(messages, null, null)
                    .doOnError(e -> log.error("[LlmNodeProcessor] 流式调用异常: {}", e.getMessage(), e))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .toStream()
                    .forEach(response -> {
                        accumulateUsage(response, tokenUsage);
                        String text = Msgs.extractText(response);
                        if (text != null && !text.isEmpty()) {
                            full.append(text);
                            onChunk.accept(text);
                        }
                    });
        } catch (Exception e) {
            if (isTimeoutException(e)) {
                log.error("[LlmNodeProcessor] LLM流式调用超时: timeout={}s", timeoutSeconds);
                throw new BizException(ErrorCode.BAD_REQUEST.getCode(),
                        "LLM调用超时（" + timeoutSeconds + "秒），请检查模型服务状态");
            }
            throw e;
        }
        return full.toString();
    }

    private boolean isTimeoutException(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof TimeoutException || cause instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            // Reactor timeout 抛出的是 java.util.concurrent.TimeoutException
            if (cause.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 累加流式/非流式响应中的 Token 用量
     */
    private void accumulateUsage(ChatResponse response, int[] tokenUsage) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        var usage = response.getUsage();
        if (usage == null) {
            return;
        }
        tokenUsage[0] += usage.getInputTokens();
        tokenUsage[1] += usage.getOutputTokens();
    }

    /**
     * 解析 Agent.config JSON
     */
    private Long resolveProviderId(Map<String, Object> nodeData, String agentConfigJson) {
        Long providerId = WorkflowNodeDataUtils.parseLongId(nodeData.get("providerId"));
        if (providerId != null) {
            return providerId;
        }
        if (nodeData.get("modelId") instanceof Number) {
            return ((Number) nodeData.get("modelId")).longValue();
        }
        Map<String, Object> agentConfig = parseAgentConfig(agentConfigJson);
        return WorkflowNodeDataUtils.parseLongId(agentConfig.get("providerId"));
    }

    /**
     * 解析具体模型名称（字符串 modelId 或 model 字段）
     */
    private String resolveModelName(Map<String, Object> nodeData) {
        String model = WorkflowNodeDataUtils.parseString(nodeData.get("model"));
        if (model != null) {
            return model;
        }
        Object modelIdVal = nodeData.get("modelId");
        if (modelIdVal instanceof String && !((String) modelIdVal).isEmpty()) {
            return (String) modelIdVal;
        }
        return WorkflowNodeDataUtils.parseString(nodeData.get("modelName"));
    }

    /**
     * 解析 Agent.config JSON
     */
    private Map<String, Object> parseAgentConfig(String configJson) {
        if (configJson == null || configJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(configJson, Map.class);
        } catch (Exception e) {
            log.warn("[LlmNodeProcessor] 解析Agent.config失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
