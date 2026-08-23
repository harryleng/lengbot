package com.lengbot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.agent.harness.AgentEventSseBridge;
import com.lengbot.agent.harness.HarnessAgentFactory;
import com.lengbot.agent.harness.SubAgentEventBridge;
import com.lengbot.agent.tool.knowledge.QueryKnowledgeTool;
import com.lengbot.agent.tool.memory.UserMemoryToolCallbackFactory;
import com.lengbot.constant.ConfigKeys;
import com.lengbot.constant.RagResultType;
import com.lengbot.constant.ToolResultPrefixes;
import com.lengbot.dto.ChatRequestDTO;
import com.lengbot.dto.LlmTraceSpanDTO;
import com.lengbot.dto.MemoryExtractDTO;
import com.lengbot.entity.Agent;
import com.lengbot.entity.Knowledge;
import com.lengbot.entity.ModelProvider;
import com.lengbot.entity.ToolCall;
import com.lengbot.enums.MessageRole;
import com.lengbot.enums.MessageType;
import com.lengbot.enums.ModelProviderType;
import com.lengbot.model.MimoChatClient;
import com.lengbot.service.*;
import com.lengbot.service.chat.*;
import com.lengbot.subagent.DelegateSubAgentTool;
import com.lengbot.subagent.spi.SubAgentDefinition;
import com.lengbot.subagent.spi.SubAgentDefinitionResolver;
import com.lengbot.tool.ToolEventEmitter;
import com.lengbot.tool.builtin.AskUserTool;
import com.lengbot.util.*;
import com.lengbot.vo.RagReferenceVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.lengbot.service.chat.ToolEventGenerator.*;

/**
 * AI对话服务实现类
 *
 * @author lw
 * @since 2026-05-19
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    // ==================== AgentScope 消息/工具参数辅助方法 ====================

    /** AgentScope 工具入参为 Map<String,Object>，序列化为 JSON 字符串供后续业务使用 */
    private String toolInputToString(Map<String, Object> input) {
        if (input == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception ignored) {
            return String.valueOf(input);
        }
    }

    /**
     * 构造工具的 RuntimeContext，供 AgentScope 2.0.1 下通过 ToolCallParam.runtimeContext(...) 透传给工具。
     * <p>WriteTodosTool / QueryKnowledgeTool / UserMemoryToolCallbackFactory 均从 getRuntimeContext() 读取
     * agentId、sessionId、userId、chatContext、currentTodos 等；迁移后须显式注入，否则上下文为空导致
     * write_todos 合并丢项、知识库/记忆检索拿不到 agentId。</p>
     */
    private RuntimeContext buildToolRuntimeContext(Long agentId, ChatContext chatContext,
                                                   Long sessionId, String requestId, String callArgs) {
        RuntimeContext.Builder b = RuntimeContext.builder();
        if (sessionId != null) {
            b.sessionId(String.valueOf(sessionId));
        }
        if (chatContext != null && chatContext.getUserId() != null) {
            b.userId(String.valueOf(chatContext.getUserId()));
        }
        b.put("agentId", agentId)
         .put("sessionId", sessionId != null ? sessionId.toString() : "default")
         .put("requestId", requestId)
         .put("userId", chatContext != null ? chatContext.getUserId() : null)
         .put("chatContext", chatContext)
         .put("currentTodos", chatContext != null ? chatContext.getCurrentTodosSnapshot() : null)
         .put("toolInput", callArgs);
        return b.build();
    }

    /** 从 ToolResultBlock 的 output 中提取首个文本块内容 */
    private String toolResultToText(ToolResultBlock block) {
        if (block == null || block.getOutput() == null) {
            return "";
        }
        return block.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .collect(java.util.stream.Collectors.joining());
    }

    /** 将工具参数 JSON 字符串解析为 Map（用于构造 ToolCallParam.input） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToolArgsToMap(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argsJson, Map.class);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private final AgentService agentService;
    /** 向量存储与相似度检索服务 */
    private final EmbeddingService embeddingService;

    /** 文本向量生成服务（AgentScope 引擎） */
    private final TextEmbeddingService textEmbeddingService;
    private final KnowledgeService knowledgeService;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final ToolCallService toolCallService;

    /** 生产级 HarnessAgent 工厂（Phase 1：按 agent 配置构建带工具的 HarnessAgent）。 */
    private final HarnessAgentFactory harnessAgentFactory;

    /** v2 AgentEvent -> 主链路 SSE 协议桥接器。 */
    private final AgentEventSseBridge agentEventSseBridge;

    /** Harness 原生子 agent（agent_spawn）事件 -> subagent_* SSE 协议桥接器（C3 Phase 2）。 */
    private final SubAgentEventBridge subAgentEventBridge;

    /** Agent 绑定子 agent 定义的解析器（C3 Phase 1：harness agent_spawn 注册用）。 */
    private final SubAgentDefinitionResolver subAgentDefinitionResolver;

    /**
     * 对话内核选择开关：{@code legacy}（默认，走 processToolCallsRecursively）
     * 或 {@code harness}（走 AgentScope HarnessAgent + Toolkit 原生工具，Phase 1 起支持工具）。
     */
    @org.springframework.beans.factory.annotation.Value("${lengbot.chat.engine:legacy}")
    private String chatEngineMode;

    // 中间件
    private final InitMiddleware initMiddleware;
    private final MentionMiddleware mentionMiddleware;
    private final UserSensitiveMiddleware userSensitiveMiddleware;
    private final WorkflowMiddleware workflowMiddleware;
    private final SkillPrepMiddleware skillPrepMiddleware;
    private final MessageMiddleware messageMiddleware;
    private final ToolPrepMiddleware toolPrepMiddleware;
    private final TraceMiddleware traceMiddleware;
    private final MimoChatClient mimoChatClient;
    private final ModelProviderService modelProviderService;
    private final TokenBudgetService tokenBudgetService;
    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;
    private final ToolEventGenerator toolEventGenerator;
    private final ToolArgsSanitizer toolArgsSanitizer;
    private final RagParamResolver ragParamResolver;
    private final SessionAttachmentRegistrar sessionAttachmentRegistrar;
    private final SubAgentService subAgentService;
    private final UserMemoryService userMemoryService;
    private final WorkspaceMemoryService workspaceMemoryService;
    private final ChatAbortRegistry chatAbortRegistry;
    private final com.lengbot.subagent.service.SubAgentTaskService subAgentTaskService;

    /** SSE 心跳注释行（SSE 协议：以冒号开头的行是注释，客户端应忽略） */
    private static final String HEARTBEAT_PREFIX = ":heartbeat";

    /** 工具执行超时时间（秒），与 {@link com.lengbot.constant.ChatConstants#TOOL_EXECUTION_TIMEOUT_SECONDS} 一致 */
    private static final long TOOL_EXECUTION_TIMEOUT_SECONDS = com.lengbot.constant.ChatConstants.TOOL_EXECUTION_TIMEOUT_SECONDS;

    /** 工具调用上下文裁剪阈值（字符数），超出时压缩早期工具调用轮次，约 15K tokens */
    private static final int MAX_TOOL_CONTEXT_CHARS = 60000;
    /** 裁剪时保留最近 N 轮工具调用，确保 LLM 有足够上下文 */
    private static final int TOOL_ROUNDS_TO_KEEP = 2;

    @Autowired
    @Qualifier("lengBotExecutor")
    private Executor lengBotExecutor;

    @Override
    public String chat(ChatRequestDTO request) {
        // 1. 初始化上下文
        ChatContext ctx = ChatContext.of(request);
        ctx.setRequestId(String.valueOf(System.nanoTime()));
        initMiddleware.init(ctx);
        mentionMiddleware.prepare(ctx);
        Long agentId = ctx.getAgent() != null ? ctx.getAgent().getId() : null;
        SensitiveWordFilter.FilterResult userCheck = SensitiveWordFilter.checkUserInput(
                request.getMessage(), ctx.getConfigMap(), agentId, ctx.getSessionId());
        if (userCheck.blocked()) {
            messageMiddleware.saveMessage(ctx.getSessionId(), MessageRole.ASSISTANT, userCheck.text());
            return userCheck.text();
        }
        skillPrepMiddleware.prepare(ctx);
        messageMiddleware.prepare(ctx);
        toolPrepMiddleware.prepare(ctx);

        log.info("[Chat] 用户消息: sessionId={}, agentId={}, message={}", ctx.getSessionId(),
                agentId, request.getMessage());

        // 2. 调用模型获取回复（带工具调用循环）
        processChatWithToolCalls(ctx);
        ctx.finalizeInlineThinking();
        String reply = ctx.getFullReply().toString();

        log.info("[Chat] AI回复: sessionId={}, length={}", ctx.getSessionId(), reply != null ? reply.length() : 0);

        // 3. 构建metadata并持久化AI回复（toolEvents 单独写入 tool_events 列）
        String metadataStr = buildChatMetadata(ctx);
        String toolEventsStr = serializeToolEvents(
                ToolEventCompactUtil.compactForPersistence(ctx.getToolEventsList(), reply));
        int totalTokens = ctx.getInputTokenHolder()[0] + ctx.getOutputTokenHolder()[0];
        Long messageId = messageMiddleware.saveMessage(ctx.getSessionId(), MessageRole.ASSISTANT,
                reply, metadataStr, toolEventsStr, totalTokens, MessageType.TEXT, null, null);
        ctx.setAssistantMessageId(messageId);

        // 3.0 记录 Token 消耗
        if (ctx.getUserId() != null) {
            tokenBudgetService.recordUsage(ctx.getUserId(), ctx.getInputTokenHolder()[0], ctx.getOutputTokenHolder()[0]);
        }
        // 3.0.1 API Key 配额扣减
        Long apiKeyId = ctx.getRequest().getApiKeyId();
        if (apiKeyId != null) {
            apiKeyService.checkAndConsumeQuota(apiKeyId, totalTokens);
        }

        // 3.1 批量写入工具调用记录
        if (!ctx.getPendingToolCalls().isEmpty()) {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            for (ToolCall tc : ctx.getPendingToolCalls()) {
                tc.setMessageId(messageId);
                if (tc.getCreatedAt() == null) {
                    tc.setCreatedAt(now);
                }
            }
            toolCallService.saveBatch(ctx.getPendingToolCalls());
        }

        // 4. 异步生成标题
        taskExecutor.execute(() -> traceMiddleware.generateTitle(ctx.getSessionId(), ctx.getAgent(), ctx.getConfigMap()));

        return reply;
    }

    /**
     * 非流式对话：处理带工具调用的多轮对话
     */
    private String processChatWithToolCalls(ChatContext ctx) {
        int maxSteps = resolveMaxExecutionSteps(ctx.getConfigMap());
        int retryTimes = resolveModelRetryTimes(ctx.getConfigMap());
        StringBuilder fullReply = ctx.getFullReply();
        List<Map<String, Object>> toolEventsList = ctx.getToolEventsList();
        String requestId = ctx.getRequestId();
        Map<String, ToolBase> toolCallbackMap = ctx.getToolCallbackMap();
        Agent agent = ctx.getAgent();

        for (int depth = 0; depth < maxSteps; depth++) {
            ChatResponse response = callModelWithRetry(ctx, retryTimes);
            if (response == null) {
                return fullReply.toString();
            }

            accumulateStreamUsage(response, ctx.getInputTokenHolder(), ctx.getOutputTokenHolder());
            Msg assistantMsg = Msg.builderForRole(MsgRole.ASSISTANT).content(response.getContent()).build();

            // 检查reasoningContent（AgentScope 下从 response metadata 提取，若不可用则依赖 inline thinking 解析）
            // 无工具调用 → 直接返回结果
            if (assistantMsg == null || !Msgs.hasToolCalls(response)) {
                String text = Msgs.extractText(response);
                if (text != null && !text.isEmpty()) {
                    ctx.appendTraceCompleteReply(text);
                }
                // 如果 metadata 没有 reasoningContent，尝试解析 inline thinking 标签
                if (ctx.getReasoningContent().length() == 0 && text != null && !text.isEmpty()) {
                    InlineThinkingStreamParser.ParseResult parsed = InlineThinkingStreamParser.parseComplete(text);
                    if (!parsed.reasoningDelta().isEmpty()) {
                        ctx.appendReasoningContent(parsed.reasoningDelta());
                    }
                    text = parsed.contentDelta();
                }
                String filtered = SensitiveWordFilter.filterAiOutput(
                        text != null ? text : "", ctx.getConfigMap(), agent.getId(), ctx.getSessionId()).text();
                fullReply.append(filtered);
                return fullReply.toString();
            }

            // 有工具调用 → 执行工具并继续循环
            ctx.getMessages().add(assistantMsg);
            List<ToolUseBlock> toolCalls = Msgs.extractToolUses(response);

            List<ToolResultBlock> toolResponses = new ArrayList<>();
            appendAssistantLeadingTextBeforeToolCall(ctx, agent, Msgs.extractText(response));
            int toolContentOffset = resolveToolBlockOffset(ctx);

            // 目前非流式只处理第一个工具调用（简化处理）
            ToolUseBlock firstTool = toolCalls.get(0);
            String toolName = firstTool.getName();
            String toolArgs = toolInputToString(firstTool.getInput());
            ctx.getToolCallCountHolder()[0]++;

            String safeArgs = toolArgs;

            // 记录工具调用开始（SubAgent 委派走专用 subagent_call 事件）
            long toolCallId = appendToolCallStart(ctx, toolEventsList, null, toolName, safeArgs, toolContentOffset);

            // 执行工具
            String toolResult = executeToolCallback(toolCallbackMap, toolName, safeArgs, agent.getId(), ctx.getSessionId(), requestId, null, ctx);

            // 暂存工具调用记录（复用 toolCallId 作为主键，前端按 id 拉取完整结果）
            ToolCall toolCallLog = new ToolCall();
            toolCallLog.setId(toolCallId);
            toolCallLog.setToolName(toolName);
            toolCallLog.setToolInput(safeArgs);
            toolCallLog.setToolOutput(toolResult);
            toolCallLog.setStatus(ToolResultPrefixes.isError(toolResult) ? "error" : "success");
            toolCallLog.setErrorMessage(ToolResultPrefixes.isError(toolResult) ? toolResult : null);
            ctx.getPendingToolCalls().add(toolCallLog);

            // 记录知识库检索结果
            if ("query_knowledge".equals(toolName)) {
                List<Map<String, Object>> kbResults = QueryKnowledgeTool.getSearchResults(requestId);
                if (!kbResults.isEmpty()) {
                    ctx.getRagMetadataHolder()[0] = buildRagMetadataJson(kbResults);
                }
            }

            // 记录工具结果（SubAgent 委派走 subagent_result）
            appendToolCallResult(ctx, toolEventsList, null, toolName, safeArgs, toolResult, toolContentOffset, toolCallId);

            toolResponses.add(ToolResultBlock.builder()
                    .id(firstTool.getId())
                    .name(toolName)
                    .output(TextBlock.builder().text(toolResult).build())
                    .build());

            ctx.getMessages().add(ToolResultMessage.builder()
                    .results(toolResponses)
                    .build());

            // ask_user 工具执行后中断循环，等待用户回复
            boolean hasAskUser = toolResponses.stream()
                    .anyMatch(r -> AskUserTool.TOOL_NAME.equals(r.getName()));
            if (hasAskUser) {
                log.info("[Chat][Trace] ask_user 工具调用，中断工具循环，等待用户回复");
                break;
            }
        }

        return fullReply.toString();
    }

    /**
     * 带重试的模型调用
     */
    private ChatResponse callModelWithRetry(ChatContext ctx, int retryTimes) {
        Exception lastException = null;
        for (int attempt = 0; attempt <= retryTimes; attempt++) {
            try {
                return ModelCalls.call(ctx.getChatModel(), ctx.getMessages(), ctx.getToolOptions());
            } catch (Exception e) {
                lastException = e;
                if (attempt < retryTimes) {
                    long delayMs = (long) Math.pow(2, attempt) * 1000;
                    log.warn("[Chat] 模型调用失败，第{}次重试，等待{}ms: {}", attempt + 1, delayMs, e.getMessage());
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        if (lastException != null) {
            log.error("[Chat] 模型调用最终失败: {}", lastException.getMessage());
        }
        return null;
    }

    /**
     * 构建知识库检索结果的metadata JSON
     */
    private String buildRagMetadataJson(List<Map<String, Object>> kbResults) {
        try {
            Map<String, Object> metadataMap = new LinkedHashMap<>();
            List<RagReferenceVO> refs = kbResults.stream().map(this::mapToRagReference).toList();
            metadataMap.put("ragReferences", refs);
            return objectMapper.writeValueAsString(metadataMap);
        } catch (Exception e) {
            log.warn("[Chat] 构建RAG metadata失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建非流式对话的metadata
     */
    private String buildChatMetadata(ChatContext ctx) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();

            // 1. 添加RAG检索结果
            String ragMeta = ctx.getRagMetadataHolder()[0];
            if (ragMeta != null && !ragMeta.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> existing = objectMapper.readValue(ragMeta, Map.class);
                meta.putAll(existing);
            }

            // 2. 添加工具事件 offset（toolEvents 本体已拆到 message.tool_events 独立列）
            List<Map<String, Object>> toolEventsList = ctx.getToolEventsList();
            if (!toolEventsList.isEmpty()) {
                List<Map<String, Object>> compactEvents = ToolEventCompactUtil.compactForPersistence(toolEventsList);
                List<Integer> offsets = ToolEventCompactUtil.extractToolBlockOffsets(compactEvents);
                if (!offsets.isEmpty()) {
                    meta.put("toolBlockOffsets", offsets);
                }
            }

            // 3. 添加reasoningContent
            if (ctx.getReasoningContent().length() > 0) {
                meta.put("reasoningContent", com.lengbot.util.TextNormalizeUtil.sanitizeForDatabase(
                        ctx.getReasoningContent().toString()));
            }

            // 4. 添加requestId
            if (ctx.getRequestId() != null && !ctx.getRequestId().isBlank()) {
                meta.put("requestId", ctx.getRequestId());
            }

            return meta.isEmpty() ? null : objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("[Chat] 构建chat metadata失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Flux<String> chatStream(ChatRequestDTO request) {
        ChatContext ctx = ChatContext.of(request);
        ctx.setRequestId(String.valueOf(System.nanoTime()));
        chatAbortRegistry.register(ctx.getRequestId(), ctx);

        // Init → Mention → 用户敏感词 → Workflow → SkillPrep → Message → ToolPrep → Trace → [core]
        List<ChatMiddleware> middlewares = List.of(
                initMiddleware, mentionMiddleware, userSensitiveMiddleware, workflowMiddleware,
                skillPrepMiddleware, messageMiddleware, toolPrepMiddleware, traceMiddleware);
        ChatServiceCore core = this::streamCore;

        return Flux.just(REQUEST_ID_PREFIX + ctx.getRequestId())
                .concatWith(ChatMiddlewareChain.of(middlewares, core).proceed(ctx))
                .concatWith(Mono.fromCallable(() -> buildDoneEvent(ctx)))
                .doOnCancel(() -> ctx.requestAbort("CLIENT_DISCONNECT"))
                .doFinally(signal -> {
                    chatAbortRegistry.remove(ctx.getRequestId());
                    if (signal == reactor.core.publisher.SignalType.CANCEL) {
                        ctx.requestAbort("CLIENT_DISCONNECT");
                        log.info("[Chat] stream cancelled: requestId={}, sessionId={}",
                                ctx.getRequestId(), ctx.getSessionId());
                    }
                });
    }

    @Override
    public void stopStream(String requestId, Long userId) {
        // 1. 中断主对话：置 aborted，in-flight LLM 轮次下一拍即停
        boolean aborted = chatAbortRegistry.abort(requestId, userId);
        if (!aborted) {
            log.info("[Chat] 停止对话未命中活跃流: requestId=[{}], userId=[{}]", requestId, userId);
        }
        // 2. 连带取消该请求下运行中的 SubAgent 子任务（taskContext.aborted 仅为快照，需显式置取消）
        int cancelled = subAgentTaskService.cancelByParentRequestId(requestId);
        if (cancelled > 0) {
            log.info("[Chat] 停止对话连带取消子任务: requestId=[{}], affected=[{}]", requestId, cancelled);
        }
    }

    /**
     * 构建 [DONE] 事件：先持久化 AI 回复，再返回带消息ID的完成标记
     * <p>此方法在 Mono.fromCallable 中执行（Flux 最后一个元素），此时流式内容已全部累加。
     * Trace 记录等后置操作由 TraceMiddleware.doOnComplete 处理；标题生成在本方法助手消息落库后触发。</p>
     */
    private String buildDoneEvent(ChatContext ctx) {
        long totalTokens = ctx.getInputTokenHolder()[0] + ctx.getOutputTokenHolder()[0];
        if (ctx.isStreamFailed()) {
            return toolEventGenerator.doneWithMetadata(ctx.getUserMessageId(), null, totalTokens,
                    buildStreamFailureMetadata(ctx));
        }
        // 用户输入敏感词拦截：UserSensitiveMiddleware 已落库 USER + ASSISTANT 两条消息，
        // 直接返回带 IDs 的 [DONE]，跳过助手消息重复保存与标题/记忆抽取等后置流程
        if (ctx.isSensitiveUserBlocked()) {
            return toolEventGenerator.doneWithMetadata(
                    ctx.getUserMessageId(), ctx.getAssistantMessageId(), totalTokens, null);
        }

        try {
            Long agentId = ctx.getAgent() != null ? ctx.getAgent().getId() : null;

            // 0. 记录 Token 消耗到预算服务
            if (ctx.getUserId() != null) {
                tokenBudgetService.recordUsage(ctx.getUserId(), ctx.getInputTokenHolder()[0], ctx.getOutputTokenHolder()[0]);
            }
            // 0.1 API Key 配额扣减
            Long apiKeyId = ctx.getRequest().getApiKeyId();
            if (apiKeyId != null) {
                apiKeyService.checkAndConsumeQuota(apiKeyId, totalTokens);
            }

            // 1. 持久化 AI 回复
            // 注意：流式链路中 fullReply 已在过程中通过 SensitiveWordFilter 过滤（processChunk/filterAiOutput）
            // 此处直接使用，避免重复过滤导致内容不一致（替换策略下多次替换会改变内容）
            ctx.finalizeInlineThinking();
            String fullReplyText = ctx.getFullReply().toString();
            // 仅做数据库安全清理（非法字符），不做敏感词二次过滤
            String replyToSave = com.lengbot.util.TextNormalizeUtil.sanitizeForAiMessage(fullReplyText, 0);
            String metadataStr = buildPersistMetadata(ctx, replyToSave);
            // toolEvents 单独序列化到 message.tool_events 列（与 metadata 解耦）
            String toolEventsStr = serializeToolEvents(buildPersistToolEvents(ctx, replyToSave));
            Long assistantMessageId = messageMiddleware.saveMessage(
                    ctx.getSessionId(), MessageRole.ASSISTANT,
                    replyToSave, metadataStr, toolEventsStr,
                    (int) totalTokens, MessageType.TEXT, null, null);
            ctx.setAssistantMessageId(assistantMessageId);

            // 1.1 批量写入工具调用记录
            if (!ctx.getPendingToolCalls().isEmpty()) {
                java.time.LocalDateTime now = java.time.LocalDateTime.now();
                for (ToolCall tc : ctx.getPendingToolCalls()) {
                    tc.setMessageId(assistantMessageId);
                    if (tc.getCreatedAt() == null) {
                        tc.setCreatedAt(now);
                    }
                }
                toolCallService.saveBatch(ctx.getPendingToolCalls());
            }
            ctx.getFullReply().setLength(0);
            ctx.getFullReply().append(replyToSave);

            // 1.2 助手消息已落库，异步生成会话标题（须晚于 TraceMiddleware.doOnComplete）
            scheduleTitleGeneration(ctx);

            // 1.3 助手消息已落库后再异步抽取长期记忆，避免影响主回复完成事件
            try {
                userMemoryService.extractAsync(buildMemoryExtractRequest(ctx));
            } catch (Exception e) {
                log.warn("[Chat] 调度长期记忆抽取失败: {}", e.getMessage());
            }

            // 1.4 记录当日工作日志（轻量：记录用户本轮诉求），与主回复完成事件解耦
            try {
                workspaceMemoryService.recordTurn(
                        ctx.getUserId(),
                        ctx.getSessionId(),
                        ctx.getAgent() != null ? ctx.getAgent().getId() : null,
                        ctx.getRequest() != null ? ctx.getRequest().getMessage() : null,
                        ctx.getFullReply() != null ? ctx.getFullReply().toString() : "");
            } catch (Exception e) {
                log.warn("[Chat] 调度每日工作日志记录失败: {}", e.getMessage());
            }

            // 2. 返回带消息ID、Token数和完整metadata的 [DONE] 事件
            return toolEventGenerator.doneWithMetadata(ctx.getUserMessageId(), assistantMessageId, totalTokens, metadataStr);
        } catch (Exception e) {
            log.error("[Chat] 构建[DONE]事件异常: {}", e.getMessage(), e);
            return DONE_PREFIX;
        }
    }

    /**
     * 构建长期记忆抽取入参：从对话上下文提取纯数据字段，并在编排层判定本轮是否已主动保存记忆。
     *
     * @param ctx 对话上下文
     * @return 记忆抽取入参
     */
    private MemoryExtractDTO buildMemoryExtractRequest(ChatContext ctx) {
        MemoryExtractDTO request = new MemoryExtractDTO();
        request.setUserId(ctx.getUserId());
        request.setSessionId(ctx.getSessionId());
        request.setAgentId(ctx.getAgent() != null ? ctx.getAgent().getId() : null);
        request.setSourceMessageId(ctx.getUserMessageId());
        request.setUserMessage(ctx.getRequest() != null ? ctx.getRequest().getMessage() : null);
        request.setAssistantReply(ctx.getFullReply() != null ? ctx.getFullReply().toString() : "");
        request.setMemorySaved(hasMemorySaveToolCall(ctx));
        return request;
    }

    /**
     * 判定本轮对话是否已通过 memory_save 工具主动保存记忆。
     *
     * @param ctx 对话上下文
     * @return 已保存返回 true
     */
    private boolean hasMemorySaveToolCall(ChatContext ctx) {
        if (ctx.getToolEventsList() == null || ctx.getToolEventsList().isEmpty()) {
            return false;
        }
        return ctx.getToolEventsList().stream()
                .anyMatch(event -> UserMemoryToolCallbackFactory.SAVE_TOOL_NAME.equals(String.valueOf(event.get("toolName"))));
    }

    private String buildStreamFailureMetadata(ChatContext ctx) {
        try {
            Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("error", Map.of(
                    "message", ctx.getStreamErrorMessage() != null ? ctx.getStreamErrorMessage() : "未知错误",
                    "code", ctx.getStreamErrorCode() != null ? ctx.getStreamErrorCode() : "UNKNOWN"));
            if (ctx.getRequestId() != null && !ctx.getRequestId().isBlank()) {
                meta.put("requestId", ctx.getRequestId());
            }
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return null;
        }
    }

    private void markStreamFailure(ChatContext ctx, Throwable e) {
        ctx.setStreamFailed(true);
        ctx.setStreamErrorMessage(classifyErrorMessage(e));
        ctx.setStreamErrorCode(classifyErrorCode(e));
    }

    /**
     * 异步生成会话标题：在 user + assistant 均已持久化后调度
     */
    private void scheduleTitleGeneration(ChatContext ctx) {
        if (ctx.getSessionId() == null) {
            return;
        }
        taskExecutor.execute(() -> traceMiddleware.generateTitle(
                ctx.getSessionId(), ctx.getAgent(), ctx.getConfigMap()));
    }

    /**
     * 构建持久化 metadata：合并 ragMetadata + reasoningContent + sensitiveBlock + requestId
     * <p>toolEvents 已拆到 message.tool_events 独立列，不再写入 metadata（避免 metadata 暴增），
     * 由 {@link #buildPersistToolEvents} 单独产出</p>
     *
     * @param ctx          对话上下文
     * @param finalContent 最终落库正文（用于对齐 toolEvents contentOffset）
     */
    private String buildPersistMetadata(ChatContext ctx, String finalContent) {
        try {
            Map<String, Object> meta = new java.util.LinkedHashMap<>();
            String ragMeta = ctx.getRagMetadataHolder()[0];
            if (ragMeta != null && !ragMeta.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> existing = objectMapper.readValue(ragMeta, Map.class);
                meta.putAll(existing);
            }
            // toolEvents 拆到独立列存储（见 buildPersistToolEvents），metadata 仅保留 toolBlockOffsets
            // 用于前端按 contentOffset 切分正文与工具块；offsets 来自同一份 compactEvents。
            List<Map<String, Object>> compactEvents = buildPersistToolEvents(ctx, finalContent);
            if (!compactEvents.isEmpty()) {
                List<Integer> offsets = ToolEventCompactUtil.extractToolBlockOffsets(compactEvents);
                if (!offsets.isEmpty()) {
                    meta.put("toolBlockOffsets", offsets);
                }
            }
            // 敏感拦截时不再暴露 reasoningContent（拦截前累积的思考也不应透出）；
            // 仅保留 sensitiveBlock 标记与必要 ID 字段
            if (!ctx.isSensitiveAiBlocked() && ctx.getReasoningContent().length() > 0) {
                meta.put("reasoningContent", com.lengbot.util.TextNormalizeUtil.sanitizeForDatabase(
                        ctx.getReasoningContent().toString()));
            }
            if (ctx.isSensitiveAiBlocked()) {
                meta.put("sensitiveBlock", "ai_output");
            }
            if (ctx.getRequestId() != null && !ctx.getRequestId().isBlank()) {
                meta.put("requestId", ctx.getRequestId());
            }
            // 用户主动中止：落库标记，供历史加载渲染「输出已终止」样式
            if (ctx.isAborted()) {
                meta.put("aborted", true);
                if (ctx.getAbortReason() != null && !ctx.getAbortReason().isBlank()) {
                    meta.put("abortReason", ctx.getAbortReason());
                }
            }
            // 未完成待办告警：本轮结束时仍有 pending/in_progress 项时，前端在消息末尾渲染醒目提示
            // 用于 AI 违反 prompt 硬约束（必须完成所有 todos 才能结束）时的兜底告警
            List<Map<String, String>> incompleteTodos = collectIncompleteTodos(ctx);
            if (!incompleteTodos.isEmpty()) {
                meta.put("incompleteTodos", incompleteTodos);
            }
            return meta.isEmpty() ? null : objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("[Chat] 构建持久化metadata失败: {}", e.getMessage());
            return ctx.getRagMetadataHolder()[0];
        }
    }

    /**
     * 构建持久化 toolEvents JSON：压缩 + 按 finalContent 对齐 contentOffset，
     * 写入 message.tool_events 独立列（与 metadata 解耦）。
     *
     * @param ctx          对话上下文
     * @param finalContent 最终落库正文（用于对齐 contentOffset）
     * @return 压缩后的事件列表；空时返回空列表
     */
    private List<Map<String, Object>> buildPersistToolEvents(ChatContext ctx, String finalContent) {
        List<Map<String, Object>> toolEventsList = ctx.getToolEventsList();
        if (toolEventsList == null || toolEventsList.isEmpty()) {
            return List.of();
        }
        return ToolEventCompactUtil.compactForPersistence(toolEventsList, finalContent);
    }

    /**
     * 序列化 toolEvents 列表为 JSON 字符串，用于 message.tool_events 落库。
     *
     * @param compactEvents 已压缩对齐的事件列表
     * @return JSON 字符串；空列表时返回 null
     */
    private String serializeToolEvents(List<Map<String, Object>> compactEvents) {
        if (compactEvents == null || compactEvents.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(compactEvents);
        } catch (Exception e) {
            log.warn("[Chat] 序列化 toolEvents 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 收集本轮结束时仍未完成的 todos（pending / in_progress），供前端渲染「未完成」告警。
     * <p>读取 {@link ChatContext#getCurrentTodosSnapshot()} —— 该快照由 ToolPrepMiddleware 初始化、
     * 每次 write_todos 成功后由 executeToolCallback 回写，反映本轮最新状态</p>
     *
     * @param ctx 对话上下文
     * @return 未完成 todos 列表（每项含 id/content/status）；空列表表示全部完成
     */
    private List<Map<String, String>> collectIncompleteTodos(ChatContext ctx) {
        List<Map<String, String>> snapshot = ctx.getCurrentTodosSnapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> incomplete = new java.util.ArrayList<>();
        for (Map<String, String> todo : snapshot) {
            String status = todo.get("status");
            if ("pending".equalsIgnoreCase(status) || "in_progress".equalsIgnoreCase(status)) {
                incomplete.add(todo);
            }
        }
        return incomplete;
    }

    /** SSE 心跳间隔（秒） */
    private static final int HEARTBEAT_INTERVAL_SECONDS = 15;

    /**
     * 流式核心：递归工具调用循环
     * <p>创建 Sinks.Many 用于工具执行期间的实时状态推送。
     * 工具内部通过 {@code ToolEventEmitter.emit()} 写入 Sink，
     * 此处订阅 Sink 将 tool_status 事件实时发送给前端。</p>
     * <p>合并心跳 Flux 防止代理/网关断连；doOnError 发送结构化错误事件。</p>
     */
    private Flux<String> streamCore(ChatContext ctx) {
        ctx.setStartTime(System.currentTimeMillis());
        Long agentId = ctx.getAgent() != null ? ctx.getAgent().getId() : null;
        ctx.setSensitiveStreamState(new SensitiveWordFilter.StreamState(
                ctx.getConfigMap(), agentId, ctx.getSessionId()));

        Sinks.Many<String> eventSink = Sinks.many().multicast().onBackpressureBuffer();
        ctx.setRealtimeStatusEmitter(json -> eventSink.tryEmitNext(STATUS_PREFIX + json));
        Flux<String> toolStatusFlux = eventSink.asFlux()
                .map(msg -> msg != null && msg.startsWith(STATUS_PREFIX)
                        ? msg
                        : STATUS_PREFIX + toolEventGenerator.toolStatusEvent(msg, 0));

        // 1.1 心跳保活：每 15 秒发送 SSE 注释行，防止代理/网关断连
        // 心跳随主内容流首条数据触发后持续发送，主内容流完成时自动停止（takeUntil）。
        // 不能直接 mergeWith 无限心跳流，否则 mergeWith 永不 complete，[DONE] 永远发不出去。
        Flux<String> heartbeatFlux = Flux.interval(Duration.ofSeconds(HEARTBEAT_INTERVAL_SECONDS))
                .map(tick -> HEARTBEAT_PREFIX);

        // 内核选择：harness（默认）用 AgentScope HarnessAgent 替代手搓工具循环；
        // legacy 走 processToolCallsRecursively 回滚。工具经 Toolkit 原生 function-calling（Phase 1 起支持）。
        Flux<String> kernelFlux = shouldUseHarnessKernel(ctx)
                ? streamViaHarnessWithRetry(ctx, eventSink, 0, resolveModelRetryTimes(ctx.getConfigMap()))
                : processToolCallsRecursively(ctx, 0, System.currentTimeMillis(), eventSink);

        Flux<String> modelFlux = kernelFlux
                .onErrorResume(e -> {
                    log.error("[Chat] 流式处理异常: {}", e.getMessage(), e);
                    markStreamFailure(ctx, e);
                    return Flux.just(STATUS_PREFIX
                            + toolEventGenerator.errorEvent(ctx.getStreamErrorMessage(), ctx.getStreamErrorCode()));
                })
                .doFinally(signal -> eventSink.tryEmitComplete());

        // 主内容流会被订阅两次：一次作为输出，一次被下方 takeUntilOther 当作完成信号。
        // modelFlux 是冷流，若不做热化，第二次订阅会整条流水线重跑一遍——工具真实执行两次，
        // 且其 tool_result 等事件经共享的 eventSink 泄漏进输出，导致结果回显两次、OCR 等慢工具耗时翻倍。
        // publish().autoConnect(2) 保证两个订阅者共享同一次上游执行，且等两者都订阅后才启动（不丢事件）。
        Flux<String> coreContent = toolStatusFlux.mergeWith(modelFlux)
                .publish()
                .autoConnect(2);

        // 心跳在主内容流完成时停止；mergeWith 取两者都完成的时间点
        return coreContent.mergeWith(heartbeatFlux.takeUntilOther(coreContent));
    }

    /**
     * 判断本次会话是否可由 HarnessAgent 内核接管。
     *
     * <p>门槛：{@code lengbot.chat.engine=harness}（默认 legacy）。流式与非流式均在 harness 分支内处理
     * （流式 {@code streamEvents} / 非流式 {@code call}），Phase 2 起不再因非流式回退 legacy。</p>
     * <p>工具经 Toolkit 原生 function-calling，副作用由 {@code HarnessToolCallback} 装饰器承接。</p>
     */
    private boolean shouldUseHarnessKernel(ChatContext ctx) {
        return "harness".equalsIgnoreCase(chatEngineMode);
    }

    /**
     * Harness 内核整轮重试包装：流式中途异常（典型如 DeepSeek 的 SSE Connection reset）
     * 且非致命错误时，回滚本轮已累积的正文/思考/敏感状态，通知前端清空已展示内容，
     * 指数退避后整轮重跑（重新构建 HarnessAgent）。
     *
     * <p><b>粒度</b>：按整轮 turn 重试——HarnessAgent 内部工具循环不暴露中间状态，
     * 与 {@code SubAgentRuntime.runViaHarness} 的整轮重试一致；故重试会重跑工具，
     * 对有副作用的非幂等工具存在重复执行风险（与子 agent 同一约定）。</p>
     * <p><b>不重试</b>：致命错误（认证失败/无可用提供商等 {@link com.lengbot.util.ModelErrorClassifier#isFatal}），
     * 以及重试次数耗尽后向上抛出，由 {@code streamCore} 的 onErrorResume 转 error 帧。</p>
     */
    private Flux<String> streamViaHarnessWithRetry(ChatContext ctx, Sinks.Many<String> eventSink,
                                                   int attempt, int retryTimes) {
        int fullReplyLengthBefore = ctx.getFullReply().length();
        return streamViaHarness(ctx, eventSink)
                .onErrorResume(e -> {
                    if (attempt < retryTimes && !com.lengbot.util.ModelErrorClassifier.isFatal(e)) {
                        int retryNo = attempt + 1;
                        long delayMs = (long) Math.pow(2, attempt) * 1000;
                        log.warn("[Chat][Harness] 流式中途异常，第{}次重试，等待{}ms: error={}",
                                retryNo, delayMs, e.getMessage());
                        eventSink.tryEmitNext(STATUS_PREFIX + toolEventGenerator.errorRetryEvent(
                                "AI连接异常，正在重试中 " + retryNo + "/" + retryTimes,
                                classifyErrorCode(e), retryNo, retryTimes, true));
                        // 回滚本轮已累积的正文/思考，避免重跑后内容重复
                        if (ctx.getFullReply().length() > fullReplyLengthBefore) {
                            ctx.getFullReply().setLength(fullReplyLengthBefore);
                        }
                        if (ctx.getReasoningContent() != null) {
                            ctx.getReasoningContent().setLength(0);
                        }
                        ctx.resetStreamTextTracking();
                        Long agentId = ctx.getAgent() != null ? ctx.getAgent().getId() : null;
                        ctx.setSensitiveStreamState(new SensitiveWordFilter.StreamState(
                                ctx.getConfigMap(), agentId, ctx.getSessionId()));
                        return Mono.delay(Duration.ofMillis(delayMs))
                                .thenMany(streamViaHarnessWithRetry(ctx, eventSink, attempt + 1, retryTimes));
                    }
                    return Flux.error(e);
                });
    }

    /**
     * HarnessAgent 内核分支：用 AgentScope 原生 Agent 产出流式回复。
     *
     * <p><b>复用主链路正文管线</b>：AgentScope 的 {@code Msg.getTextContent()} 与主链路
     * {@code ChatResponse} 一样返回<b>累积全文</b>，因此这里把文本喂给同一套
     * {@code feedStreamTextChunk} + {@code fluxFromInlineThinking} 流程——增量抽取、
     * inline-thinking 拆分、敏感词流式过滤、{@code ctx.fullReply} 回填全部沿用既有实现，
     * 保证两条链路落库与前端渲染行为一致。</p>
     *
     * <p><b>token 回填</b>：{@code buildDoneEvent} 依赖 {@code inputTokenHolder/outputTokenHolder}
     * 做计费与统计，故从事件携带的 {@code ChatUsage} 累加回去。</p>
     */
    private Flux<String> streamViaHarness(ChatContext ctx, Sinks.Many<String> eventSink) {
        Agent agent = ctx.getAgent();
        Long agentId = agent != null ? agent.getId() : null;
        int[] inputTokenHolder = ctx.getInputTokenHolder();
        int[] outputTokenHolder = ctx.getOutputTokenHolder();
        long llmCallStart = System.currentTimeMillis();

        // 工具用装饰器包装：接管工具事件（appendToolCallStart/Result）+ 副作用（对齐 legacy executeToolCallback）
        List<ToolBase> wrappedTools = new ArrayList<>();
        Map<String, ToolBase> toolMap = ctx.getToolCallbackMap();
        if (toolMap != null) {
            for (ToolBase t : toolMap.values()) {
                if (t != null) {
                    wrappedTools.add(new HarnessToolCallback(t, ctx, eventSink));
                }
            }
        }

        // 富 RuntimeContext：工具经 param.getRuntimeContext() 读取共享上下文（agentId/sessionId/requestId/userId/chatContext/currentTodos）。
        // 不复用 buildToolRuntimeContext——它强制 put toolInput（streamEvents 级别无单一工具入参），且 ConcurrentHashMap 不允许 null value，
        // currentTodos/toolInput 为 null 时会 NPE。此处逐项 null 守卫，仅放共享值。
        RuntimeContext.Builder rcb = RuntimeContext.builder();
        if (ctx.getSessionId() != null) {
            rcb.sessionId(String.valueOf(ctx.getSessionId()));
        }
        if (ctx.getUserId() != null) {
            rcb.userId(String.valueOf(ctx.getUserId()));
        }
        if (agentId != null) {
            rcb.put("agentId", agentId);
        }
        rcb.put("sessionId", ctx.getSessionId() != null ? ctx.getSessionId().toString() : "default");
        if (ctx.getRequestId() != null) {
            rcb.put("requestId", ctx.getRequestId());
        }
        if (ctx.getUserId() != null) {
            rcb.put("userId", ctx.getUserId());
        }
        rcb.put("chatContext", ctx);
        if (ctx.getCurrentTodosSnapshot() != null) {
            rcb.put("currentTodos", ctx.getCurrentTodosSnapshot());
        }
        RuntimeContext runtimeContext = rcb.build();

        // HarnessAgent 的 hook 不允许 inputMessages 含 SYSTEM 消息（须走 sysPrompt）。
        // MessageMiddleware 把系统提示词（agent 人设+平台约束+工具预算+子agent协议+用户记忆等）拼成 SYSTEM 消息，
        // 此处拆分：SYSTEM 文本 -> sysPrompt，其余 -> conversation 传 streamEvents。
        String sysPrompt = null;
        List<Msg> conversationMsgs = new ArrayList<>();
        if (ctx.getMessages() != null) {
            for (Msg m : ctx.getMessages()) {
                if (m != null && m.getRole() == MsgRole.SYSTEM) {
                    String t = m.getTextContent();
                    if (t != null && !t.isBlank()) {
                        sysPrompt = sysPrompt == null ? t : sysPrompt + "\n\n" + t;
                    }
                } else if (m != null) {
                    conversationMsgs.add(m);
                }
            }
        }

        HarnessAgent ha = harnessAgentFactory.build(
                ctx.getChatModel(), sysPrompt, wrappedTools,
                resolveMaxExecutionSteps(ctx.getConfigMap()), List.of(),
                null,
                resolveSubAgentDefinitions(ctx), ctx);

        // 子 agent 事件归组桥接（C3 Phase 2）：agent_spawn 子事件 + 父流工具事件统一在此映射 subagent_*
        SubAgentEventBridge.Session subAgentSession = subAgentEventBridge.create(ctx);

        AgentEventSseBridge.BridgeOptions opts = AgentEventSseBridge.BridgeOptions.builder()
                .onContentDelta(delta -> onHarnessContentDelta(ctx, delta))
                .onReasoningDelta(delta -> onHarnessReasoningDelta(ctx, delta))
                .onSubAgentEvent(subAgentSession::handle)
                .build();

        return Flux.using(
                () -> ha,
                a -> {
                    if (!isStreamOutputEnabled(ctx.getConfigMap())) {
                        // 非流式：harnessAgent.call() 阻塞跑完整轮（含工具循环），结果一次性下发。
                        // 工具事件经装饰器实时推 eventSink（call 期间），与 legacy processBlockingRound 一致。
                        Msg msg = a.call(conversationMsgs, runtimeContext).block();
                        if (msg != null && msg.getUsage() != null) {
                            inputTokenHolder[0] += msg.getUsage().getInputTokens();
                            outputTokenHolder[0] += msg.getUsage().getOutputTokens();
                        }
                        String text = msg != null ? msg.getTextContent() : "";
                        if (text == null) {
                            text = "";
                        }
                        SensitiveWordFilter.FilterResult fr = SensitiveWordFilter.filterAiOutput(
                                text, ctx.getConfigMap(), agentId, ctx.getSessionId());
                        if (fr.blocked()) {
                            ctx.getFullReply().setLength(0);
                            ctx.getFullReply().append(SensitiveWordFilter.AI_BLOCK_MESSAGE);
                            ctx.setSensitiveAiBlocked(true);
                            return Flux.just(STATUS_PREFIX + toolEventGenerator.sensitiveBlockEvent(
                                    "ai_output", SensitiveWordFilter.AI_BLOCK_MESSAGE));
                        }
                        ctx.getFullReply().append(fr.text());
                        return Flux.just(fr.text());
                    }
                    // 流式：streamEvents + 桥接器
                    Flux<AgentEvent> events = a.streamEvents(conversationMsgs, runtimeContext);
                    // token 累计：v2 无独立 usage 事件，从 AgentResultEvent 的 Msg.usage 取
                    Flux<AgentEvent> tapped = events.doOnNext(event -> {
                        if (event instanceof AgentResultEvent r) {
                            Msg m = r.getResult();
                            if (m != null && m.getUsage() != null) {
                                inputTokenHolder[0] += m.getUsage().getInputTokens();
                                outputTokenHolder[0] += m.getUsage().getOutputTokens();
                            }
                        }
                        // 调试：把每个 AgentEvent 类型打到 SSE（[STATUS]agent_event_debug 帧）
                        emitAgentEventDebug(ctx, event);
                    });
                    return agentEventSseBridge.bridge(tapped, opts);
                },
                a -> {
                    try {
                        a.close();
                    } catch (Exception e) {
                        log.warn("[Chat][Harness] close agent err={}", e.getMessage());
                    }
                }
        ).doOnComplete(() -> {
            ctx.getSpans().add(LlmTraceSpanDTO.of("llm_harness", "s1", "llm_call", llmCallStart,
                    System.currentTimeMillis() - llmCallStart, "OK",
                    Map.of("engine", "harness",
                            "model", ctx.getConfigMap().getOrDefault("modelId", ""),
                            "inputTokens", inputTokenHolder[0],
                            "outputTokens", outputTokenHolder[0])));
            log.info("[Chat][Harness] 完成: sessionId={}, replyLen={}, tokens={}/{}",
                    ctx.getSessionId(), ctx.getFullReply().length(),
                    inputTokenHolder[0], outputTokenHolder[0]);
        });
    }

    /** 解析当前 Agent 绑定的可委派子 agent 定义（C3 Phase 1：注册 harness agent_spawn）。 */
    private Map<String, SubAgentDefinition> resolveSubAgentDefinitions(ChatContext ctx) {
        List<Long> ids = ctx.getBoundSubAgentIds();
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<String, SubAgentDefinition> defs = subAgentDefinitionResolver.resolve(ids);
        return defs != null ? defs : Map.of();
    }

    /** harness 正文增量：敏感词过滤（StreamState）+ fullReply 回填 + 命中 block 发 sensitive_block。 */
    private List<String> onHarnessContentDelta(ChatContext ctx, String delta) {
        if (ctx.isSensitiveAiBlocked() || ctx.isAborted()) {
            return List.of();
        }
        SensitiveWordFilter.StreamState ss = ctx.getSensitiveStreamState();
        String filtered = ss != null ? ss.processChunk(delta) : delta;
        if (ss != null && ss.isBlocked()) {
            ctx.getFullReply().setLength(0);
            ctx.getFullReply().append(SensitiveWordFilter.AI_BLOCK_MESSAGE);
            ctx.setSensitiveAiBlocked(true);
            return List.of(STATUS_PREFIX + toolEventGenerator.sensitiveBlockEvent("ai_output", SensitiveWordFilter.AI_BLOCK_MESSAGE));
        }
        if (filtered.isEmpty()) {
            return List.of();
        }
        ctx.getFullReply().append(filtered);
        return List.of(filtered);
    }

    /** harness 思考流增量：回填 ctx.reasoningContent，返回待下发增量（null/空不下发）。
     *  enableReasoning=false 时不流式思考--对齐 legacy：legacy 仅解析 <think> 标签内的思考，
     *  非 <think> 的独立 reasoning（如 deepseek THINKING_BLOCK）legacy 抓不到即不下发，harness 同样抑制。 */
    private String onHarnessReasoningDelta(ChatContext ctx, String delta) {
        if (ctx.isSensitiveAiBlocked() || ctx.isAborted()) {
            return null;
        }
        Object enabled = ctx.getConfigMap() != null
                ? ctx.getConfigMap().get(ConfigKeys.Agent.ENABLE_REASONING) : null;
        if (enabled != null
                && (Boolean.FALSE.equals(enabled) || "false".equalsIgnoreCase(String.valueOf(enabled)))) {
            return null;
        }
        return ctx.appendReasoningContent(delta);
    }

    /**
     * 调试：把 AgentEvent 类型作为 [STATUS]agent_event_debug 帧推到 SSE（经 eventSink）。
     * <p>前端 processSseLines 白名单无此 type，故不渲染到消息气泡--仅在浏览器 devtools ->
     * Network -> /api/chat/stream 请求的「EventStream/消息」面板可见原始帧。
     * 若要 UI 可见：改用 tool_status（状态栏）或裸文本（会进正文）。 */
    private void emitAgentEventDebug(ChatContext ctx, AgentEvent e) {
        if (ctx == null || ctx.getRealtimeStatusEmitter() == null || e == null) {
            return;
        }
        Map<String, Object> evt = new java.util.LinkedHashMap<>();
        evt.put("type", "agent_event_debug");
        if (e instanceof AgentStartEvent a) {
            evt.put("event", "AGENT_START");
            evt.put("name", a.getName());
        } else if (e instanceof TextBlockDeltaEvent d) {
            evt.put("event", "TEXT_BLOCK_DELTA");
            evt.put("delta", d.getDelta());
        } else if (e instanceof ToolCallStartEvent t) {
            evt.put("event", "TOOL_CALL_START");
            evt.put("toolName", t.getToolCallName());
        } else if (e instanceof SubagentExposedEvent s) {
            evt.put("event", "SUBAGENT_EXPOSED");
            evt.put("subagentId", s.getSubagentId());
            evt.put("agentId", s.getAgentId());
            evt.put("label", s.getLabel());
        } else if (e instanceof AgentEndEvent) {
            evt.put("event", "AGENT_END");
        } else if (e instanceof AgentResultEvent r) {
            evt.put("event", "AGENT_RESULT");
            Msg rm = r.getResult();
            if (rm != null && rm.getUsage() != null) {
                evt.put("inputTokens", rm.getUsage().getInputTokens());
                evt.put("outputTokens", rm.getUsage().getOutputTokens());
            }
        } else {
            evt.put("event", e.getClass().getSimpleName());
        }
        try {
            ctx.emitRealtimeStatus(objectMapper.writeValueAsString(evt));
        } catch (Exception ignored) {
        }
    }

    /** 构造纯文本 ToolResultBlock（统一结果回填用；id/name 传 null，符合「工具返回值」约定，由 Toolkit 回填）。 */
    private ToolResultBlock textToolResultBlock(String text, ToolResultState state) {
        return new ToolResultBlock(null, null, List.of(TextBlock.builder().text(text).build()), null, state);
    }

    /** 把 appendToolCallStart/Result 收集到 statusFluxes 的附属事件（skill_active/file-writing/todos_updated）
     *  转发到 eventSink。harness 分支没有 legacy 的 statusFluxes 拼接管线（legacy 用 Flux.concat 把它并入主输出），
     *  这里手动 drain。appendToolCallStart/Result 内部对 statusFluxes 直接 .add，故不能传 null。 */
    private void flushStatusFluxes(List<Flux<String>> statusFluxes, Sinks.Many<String> eventSink) {
        if (statusFluxes == null || statusFluxes.isEmpty() || eventSink == null) {
            return;
        }
        for (Flux<String> f : statusFluxes) {
            if (f == null) {
                continue;
            }
            f.subscribe(s -> {
                if (s != null && !s.isEmpty()) {
                    eventSink.tryEmitNext(s);
                }
            });
        }
    }

    /** harness 分支：appendToolCallResult + 把附属事件 flush 到 eventSink。 */
    private void emitToolResultHarness(ChatContext ctx, List<Map<String, Object>> toolEventsList,
                                       Sinks.Many<String> eventSink, String toolName, String args,
                                       String result, int contentOffset, long toolCallId) {
        List<Flux<String>> fluxes = new ArrayList<>();
        appendToolCallResult(ctx, toolEventsList, fluxes, toolName, args, result, contentOffset, toolCallId);
        flushStatusFluxes(fluxes, eventSink);
    }

    /**
     * HarnessAgent 工具执行装饰器（ChatServiceImpl 内部类）。
     *
     * <p>包裹原 {@link ToolBase}，在 {@code callAsync} 中复刻 legacy {@code executeToolCallback} +
     * {@code appendToolCallStart}/{@code appendToolCallResult} 全部行为：工具事件编排（tool_call/tool_result/
     * tool_complete，共享 toolCallId/contentOffset/toolEventsList 持久化）、中断检查、args 截断修复、
     * ToolEventEmitter sink（tool_status 实时推送）、超时、attachment 注册、write_todos 快照、ToolCall 记录。</p>
     *
     * <p>内部类以直接复用外层私有 helper 与字段。Toolkit build 时 {@code ToolRegistry.copyTo} 共享同一
     * AgentTool 实例（不克隆），故外部类引用在 agent 构建后仍有效。</p>
     */
    private final class HarnessToolCallback extends ToolBase {
        private final ToolBase delegate;
        private final ChatContext ctx;
        private final Sinks.Many<String> eventSink;

        HarnessToolCallback(ToolBase delegate, ChatContext ctx, Sinks.Many<String> eventSink) {
            super(delegate.getName(), delegate.getDescription(), delegate.getParameters(),
                    delegate.isReadOnly(), false, false, null, false, false);
            this.delegate = delegate;
            this.ctx = ctx;
            this.eventSink = eventSink;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            String toolName = delegate.getName();
            String callArgs = toolInputToString(param.getInput());
            Long sessionId = ctx.getSessionId();
            List<Map<String, Object>> toolEventsList = ctx.getToolEventsList();
            int contentOffset = resolveToolBlockOffset(ctx);

            // 工具调用开始：emit tool_call + 预生成 toolCallId（对齐 appendToolCallStart）
            // statusFluxes 收集 skill_active/file-writing 等附属事件，再 flush 到 eventSink（不能传 null）
            List<Flux<String>> startFluxes = new ArrayList<>();
            long toolCallId = appendToolCallStart(ctx, toolEventsList, startFluxes, toolName, callArgs, contentOffset);
            flushStatusFluxes(startFluxes, eventSink);

            // args 截断修复（写文件场景 maxTokens 截断）
            String effectiveArgs = callArgs;
            if (isLikelyTruncatedJson(effectiveArgs)) {
                String repaired = toolArgsSanitizer.tryRepairTruncatedWriteArgs(toolName, effectiveArgs);
                if (repaired != null) {
                    effectiveArgs = stripInternalRepairFlags(repaired);
                }
            }
            final String argsForCall = effectiveArgs != null ? effectiveArgs : "{}";
            final long timeoutSeconds = resolveToolExecutionTimeoutSeconds(toolName, argsForCall);
            final ToolCallParam effectiveParam = ToolCallParam.builder(param)
                    .input(parseToolArgsToMap(argsForCall)).build();

            return Mono.fromFuture(
                    CompletableFuture.supplyAsync(() -> {
                        if (ctx.isAborted()) {
                            return ToolResultPrefixes.failureJson("CLIENT_ABORTED");
                        }
                        ToolEventEmitter.setupSink(eventSink);
                        try {
                            ToolResultBlock block = delegate.callAsync(effectiveParam).block();
                            return toolResultToText(block);
                        } finally {
                            ToolEventEmitter.teardownSink();
                        }
                    }, lengBotExecutor).orTimeout(timeoutSeconds, TimeUnit.SECONDS)
            ).map(result -> {
                boolean isError = ToolResultPrefixes.isError(result);
                // ToolCall 记录（供 buildDoneEvent 批量落库）。toolCallId=0 为委派类工具（delegate_to_subagent），
                // appendToolCallStart 对委派返回 0 表示不入 tool_calls 表；若建 id=0 记录，多次委派会重复键。
                if (toolCallId > 0) {
                    ToolCall tc = new ToolCall();
                    tc.setId(toolCallId);
                    tc.setToolName(toolName);
                    tc.setToolInput(argsForCall);
                    tc.setToolOutput(result);
                    tc.setStatus(isError ? "error" : "success");
                    tc.setErrorMessage(isError ? result : null);
                    ctx.getPendingToolCalls().add(tc);
                }
                if (!isError) {
                    sessionAttachmentRegistrar.registerFromToolResult(sessionId, toolName, result);
                    if ("write_todos".equals(toolName)) {
                        updateCurrentTodosSnapshot(ctx, result);
                    }
                }
                // 工具结果：emit tool_result（对齐 appendToolCallResult，含 todos_updated/subagent 路由）
                emitToolResultHarness(ctx, toolEventsList, eventSink, toolName, argsForCall, result, contentOffset, toolCallId);
                return textToolResultBlock(result, isError ? ToolResultState.ERROR : ToolResultState.SUCCESS);
            }).onErrorResume(TimeoutException.class, e -> {
                log.error("[Chat][Harness][Tool] 执行超时: name={}, timeout={}s", toolName, timeoutSeconds);
                String fail = ToolResultPrefixes.failureJson("工具执行超时（" + timeoutSeconds + "秒），请稍后重试");
                emitToolResultHarness(ctx, toolEventsList, eventSink, toolName, argsForCall, fail, contentOffset, toolCallId);
                return Mono.just(textToolResultBlock(fail, ToolResultState.ERROR));
            }).onErrorResume(e -> {
                log.error("[Chat][Harness][Tool] 执行异常: name={}", toolName, e);
                String fail = ToolResultPrefixes.failureJson(ToolResultPrefixes.FAILURE + ": " + e.getMessage());
                emitToolResultHarness(ctx, toolEventsList, eventSink, toolName, argsForCall, fail, contentOffset, toolCallId);
                return Mono.just(textToolResultBlock(fail, ToolResultState.ERROR));
            });
        }
    }

    /** 把同步产出的 Flux（fluxFromInlineThinking 内部始终是即时集合流）收敛为 List，供 handler 返回。 */
    private List<String> collectFlux(Flux<String> flux) {
        List<String> items = flux.collectList().block();
        return items == null ? List.of() : items;
    }

    /**
     * 递归处理工具调用：调用LLM → 检测工具 → 执行 → 重新调用LLM
     *
     * @param ctx          管道上下文
     * @param depth        递归深度（防止无限循环）
     * @param llmCallStart 本轮LLM调用开始时间
     * @param eventSink    工具状态事件实时推送通道
     * @return Flux<String> 流式输出片段
     */
    private Flux<String> processToolCallsRecursively(ChatContext ctx, int depth, long llmCallStart,
                                                      Sinks.Many<String> eventSink) {
        if (ctx.isAborted()) {
            return Flux.empty();
        }
        // 上一轮或本轮已触发敏感拦截：跳过后续 LLM 调用，让 buildDoneEvent 立即收尾
        if (ctx.isSensitiveAiBlocked()) {
            return Flux.empty();
        }
        int maxSteps = resolveMaxExecutionSteps(ctx.getConfigMap());
        if (depth >= maxSteps) {
            log.warn("[Chat][Trace] 工具调用递归深度达到上限({})，停止循环", depth);
            return Flux.just("\n[工具调用轮次已达上限，请简化问题后重试]");
        }

        Model chatModel = ctx.getChatModel();
        List<Msg> messages = ctx.getMessages();
        GenerateOptions toolOptions = ctx.getToolOptions();
        Map<String, ToolBase> toolCallbackMap = ctx.getToolCallbackMap();
        Agent agent = ctx.getAgent();
        StringBuilder fullReply = ctx.getFullReply();
        String[] ragMetadataHolder = ctx.getRagMetadataHolder();
        int[] toolCallCountHolder = ctx.getToolCallCountHolder();
        int[] inputTokenHolder = ctx.getInputTokenHolder();
        int[] outputTokenHolder = ctx.getOutputTokenHolder();
        List<Map<String, Object>> toolEventsList = ctx.getToolEventsList();
        String requestId = ctx.getRequestId();
        List<LlmTraceSpanDTO> spans = ctx.getSpans();
        Map<String, Object> configMap = ctx.getConfigMap();
        StringBuilder reasoningContent = ctx.getReasoningContent();

        if (!isStreamOutputEnabled(configMap)) {
            return processBlockingRound(ctx, depth, llmCallStart, eventSink);
        }

        // MiMo 直连：联网搜索 / 视频等多模态
        ModelProvider provider = ctx.getProviderId() != null
                ? modelProviderService.getById(ctx.getProviderId()) : null;
        if (provider != null && provider.getType() == ModelProviderType.MIMO
                && mimoChatClient.shouldUseDirectApi(configMap, ctx.getRequest().getAttachments())
                && depth == 0) {
            return streamMimoDirect(ctx, depth, llmCallStart, provider, messages);
        }

        // 1. 调用LLM（流式）
        String llmSpanId = "llm_" + depth;
        boolean[] llmSpanAdded = {false};

        return streamModelWithRetry(ctx, chatModel, toolOptions, new ArrayList<>(messages), depth, eventSink)
                .concatMap(response -> {
                    // 已触发 AI 输出敏感拦截：跳过后续 chunk（含 metadata reasoning、正文增量），
                    // 由首条 sensitive_block + buildDoneEvent 最小 metadata 收尾
                    if (ctx.isSensitiveAiBlocked()) {
                        return Flux.empty();
                    }
                    Msg assistantMsg = Msg.builderForRole(MsgRole.ASSISTANT).content(response.getContent()).build();

                    // 2. 无工具调用 → 直接输出文本（结束递归）
                    if (assistantMsg == null || !Msgs.hasToolCalls(response)) {
                        // 先累加 Token（usage 常在最后一个空文本 chunk，不能因 stripped 为空而跳过）
                        accumulateStreamUsage(response, inputTokenHolder, outputTokenHolder);

                        String text = Msgs.extractText(response);
                        if (text == null) text = "";

                        List<String> streamItems = new ArrayList<>(2);

                        if (!text.isEmpty()) {
                            InlineThinkingStreamParser.ParseResult parsed = feedStreamTextChunk(ctx, text);
                            Flux<String> contentFlux = fluxFromInlineThinking(ctx, agent, parsed, () -> {
                                if (!llmSpanAdded[0]) {
                                    spans.add(LlmTraceSpanDTO.of(llmSpanId, "s1", "llm_call", llmCallStart,
                                            System.currentTimeMillis() - llmCallStart, "OK",
                                            Map.of("depth", depth, "model", configMap.getOrDefault("modelId", ""),
                                                    "inputTokens", inputTokenHolder[0], "outputTokens", outputTokenHolder[0],
                                                    "replyPreview", fullReply.length() > 500 ? fullReply.substring(0, 500) + "..." : fullReply.toString())));
                                    llmSpanAdded[0] = true;
                                }
                            });
                            return streamItems.isEmpty()
                                    ? contentFlux
                                    : Flux.fromIterable(streamItems).concatWith(contentFlux);
                        }

                        return streamItems.isEmpty() ? Flux.empty() : Flux.fromIterable(streamItems);
                    }

                    // 3. 有工具调用 → 执行工具
                    messages.add(assistantMsg);

                    accumulateStreamUsage(response, inputTokenHolder, outputTokenHolder);

                    // 3.0 先消费本 chunk 携带的正文（部分模型将正文与工具调用放在同一 chunk）。
                    //     必须在计算 toolContentOffset 之前完成，使 offset 精确反映"组件前已产出的正文长度"，
                    //     否则前端会按滞后的 offset 把正文从中间截断（如「好<组件>的」）。
                    Flux<String> leadingContentFlux = Flux.empty();
                    String assistantLeadingText = Msgs.extractText(response);
                    boolean leadingContentAppended = false;
                    if (assistantLeadingText != null && !assistantLeadingText.isEmpty()) {
                        InlineThinkingStreamParser.ParseResult leadingParsed = feedStreamTextChunk(ctx, assistantLeadingText);
                        leadingContentAppended = appendInlineThinkingContentDelta(ctx, agent, leadingParsed);
                        leadingContentFlux = fluxFromInlineThinking(ctx, agent, leadingParsed, null, false);
                    }

                    List<ToolUseBlock> toolCalls = Msgs.extractToolUses(response);
                    boolean asyncEnabled = Boolean.TRUE.equals(configMap.get("asyncToolCalls"));

                    if (!llmSpanAdded[0]) {
                        spans.add(LlmTraceSpanDTO.of(llmSpanId, "s1", "llm_call", llmCallStart,
                                System.currentTimeMillis() - llmCallStart, "OK",
                                Map.of("depth", depth, "model", configMap.getOrDefault("modelId", ""),
                                        "toolCount", toolCalls.size(),
                                        "toolNames", toolCalls.stream().map(ToolUseBlock::getName).toList().toString())));
                        llmSpanAdded[0] = true;
                    }

                    List<Flux<String>> statusFluxes = new ArrayList<>();
                    List<Map<String, Object>> kbResultsHolder = new ArrayList<>();
            List<ToolResultBlock> toolResponses = new ArrayList<>();
            if (!leadingContentAppended) {
                appendAssistantLeadingTextBeforeToolCall(ctx, agent, Msgs.extractText(response));
            }
            int toolContentOffset = resolveToolBlockOffset(ctx);

                    if (asyncEnabled && toolCalls.size() > 1) {
                        // 并行执行所有工具
                        log.info("[Chat][Trace] 工具调用(depth={}): {}个工具, 并行执行", depth, toolCalls.size());
                        List<CompletableFuture<String>> futures = new ArrayList<>();
                        for (ToolUseBlock tc : toolCalls) {
                            String tcArgs = toolInputToString(tc.getInput());
                            long tcToolCallId = appendToolCallStart(ctx, toolEventsList, statusFluxes, tc.getName(), tcArgs, toolContentOffset);
                            toolCallCountHolder[0]++;
                            final String tcName = tc.getName();
                            final String safeTcArgs = toolArgsSanitizer.forChatCall(tcArgs);
                            final Sinks.Many<String> sink = eventSink;
                            final long tcIdFinal = tcToolCallId;
                            futures.add(CompletableFuture.supplyAsync(() -> {
                                long tStart = System.currentTimeMillis();
                                // 绑定 Sink 到当前 worker 线程，使 emit() 实时推送
                                if (sink != null) {
                                    ToolEventEmitter.setupSink(sink);
                                }
                                String result;
                                try {
                                    result = executeToolCallback(toolCallbackMap, tcName, safeTcArgs,
                                            agent.getId(), ctx.getSessionId(), requestId, sink, ctx);
                                } finally {
                                    if (sink != null) {
                                        ToolEventEmitter.teardownSink();
                                    }
                                }
                                long tEnd = System.currentTimeMillis();
                                log.info("[Chat][Trace] 工具执行结果: name={}, 耗时={}ms, resultLength={}", tcName, tEnd - tStart, result.length());
                                spans.add(LlmTraceSpanDTO.of("tool_" + toolCallCountHolder[0], llmSpanId, "tool_execute",
                                        tStart, tEnd - tStart, "OK",
                                        buildToolTraceAttributes(tcName, tcArgs, result)));
                                appendSubAgentTraceSpans(spans, "tool_" + toolCallCountHolder[0], tcName, result, tStart);
                                if ("query_knowledge".equals(tcName)) {
                                    List<Map<String, Object>> kbResults = QueryKnowledgeTool.getSearchResults(requestId);
                                    synchronized (kbResultsHolder) { kbResultsHolder.addAll(kbResults); }
                                }
                                // 暂存工具调用记录（复用 tcIdFinal 作为主键，前端按 id 拉取完整结果）
                                ToolCall toolCallLog = new ToolCall();
                                toolCallLog.setId(tcIdFinal);
                                toolCallLog.setToolName(tcName);
                                toolCallLog.setToolInput(safeTcArgs);
                                toolCallLog.setToolOutput(result);
                                toolCallLog.setStatus(result.startsWith(ToolResultPrefixes.FAILURE) || result.startsWith(ToolResultPrefixes.NOT_FOUND) ? "error" : "success");
                                toolCallLog.setErrorMessage(result.startsWith(ToolResultPrefixes.FAILURE) ? result : null);
                                synchronized (ctx.getPendingToolCalls()) {
                                    ctx.getPendingToolCalls().add(toolCallLog);
                                }

                                appendToolCallResult(ctx, toolEventsList, statusFluxes, tcName, tcArgs, result, toolContentOffset, tcIdFinal);
                                return result;
                            }, lengBotExecutor));
                        }
                        for (int i = 0; i < toolCalls.size(); i++) {
                            ToolUseBlock tc = toolCalls.get(i);
                            String result = futures.get(i).join();
                            toolResponses.add(ToolResultBlock.builder()
                                    .id(tc.getId())
                                    .name(tc.getName())
                                    .output(TextBlock.builder().text(result).build())
                                    .build());
                        }
                    } else {
                        // 串行执行：只执行第一个工具
                        ToolUseBlock firstTool = toolCalls.get(0);
                        log.info("[Chat][Trace] 工具调用(depth={}): {}个工具, 只执行第一个: {}",
                                depth, toolCalls.size(), firstTool.getName());
                        String toolName = firstTool.getName();
                        String toolArgs = toolInputToString(firstTool.getInput());
                        toolCallCountHolder[0]++;

                        String safeArgs = toolArgs;
                        String callArgs = toolArgsSanitizer.forChatCall(safeArgs);
                        long toolCallId = appendToolCallStart(ctx, toolEventsList, statusFluxes, toolName, safeArgs, toolContentOffset);

                        long tToolStart = System.currentTimeMillis();
                        // 流式模式：绑定 Sink 使工具内部 emit() 实时推送给前端
                        ToolEventEmitter.setupSink(eventSink);
                        String toolResult;
                        try {
                            toolResult = executeToolCallback(toolCallbackMap, toolName, callArgs,
                                    agent.getId(), ctx.getSessionId(), requestId, eventSink, ctx);
                        } finally {
                            ToolEventEmitter.teardownSink();
                        }
                        long tToolEnd = System.currentTimeMillis();
                        log.info("[Chat][Trace] 工具执行结果: name={}, 耗时={}ms, resultLength={}", toolName, tToolEnd - tToolStart, toolResult.length());

                        spans.add(LlmTraceSpanDTO.of("tool_" + toolCallCountHolder[0], llmSpanId, "tool_execute",
                                tToolStart, tToolEnd - tToolStart, "OK",
                                buildToolTraceAttributes(toolName, safeArgs, toolResult)));
                        appendSubAgentTraceSpans(spans, "tool_" + toolCallCountHolder[0], toolName, toolResult, tToolStart);

                        if ("query_knowledge".equals(toolName)) {
                            List<Map<String, Object>> kbResults = QueryKnowledgeTool.getSearchResults(requestId);
                            if (!kbResults.isEmpty()) kbResultsHolder.addAll(kbResults);
                        }

                        // 暂存工具调用记录（复用 toolCallId 作为主键，前端按 id 拉取完整结果）
                        ToolCall toolCallLog = new ToolCall();
                        toolCallLog.setId(toolCallId);
                        toolCallLog.setToolName(toolName);
                        toolCallLog.setToolInput(safeArgs);
                        toolCallLog.setToolOutput(toolResult);
                        toolCallLog.setStatus(ToolResultPrefixes.isError(toolResult) ? "error" : "success");
                        toolCallLog.setErrorMessage(ToolResultPrefixes.isError(toolResult) ? toolResult : null);
                        ctx.getPendingToolCalls().add(toolCallLog);

                        appendToolCallResult(ctx, toolEventsList, statusFluxes, toolName, safeArgs, toolResult, toolContentOffset, toolCallId);
                        toolResponses.add(ToolResultBlock.builder()
                                .id(firstTool.getId())
                                .name(toolName)
                                .output(TextBlock.builder().text(toolResult).build())
                                .build());
                    }

                    messages.add(ToolResultMessage.builder()
                    .results(toolResponses)
                            .build());

                    List<Map<String, Object>> kbResultsRef = kbResultsHolder;
                    Flux<String> afterTool = Flux.defer(() -> {
                        if (!kbResultsRef.isEmpty() || !toolEventsList.isEmpty()) {
                            Map<String, Object> metadataMap = new java.util.LinkedHashMap<>();
                            if (!toolEventsList.isEmpty()) {
                                // toolEvents 拆到 message.tool_events 独立列；中间 metadata 仅承载 toolBlockOffsets
                                List<Map<String, Object>> compactEvents = ToolEventCompactUtil.compactForPersistence(toolEventsList);
                                List<Integer> offsets = ToolEventCompactUtil.extractToolBlockOffsets(compactEvents);
                                if (!offsets.isEmpty()) metadataMap.put("toolBlockOffsets", offsets);
                            }
                            if (!kbResultsRef.isEmpty()) {
                                List<RagReferenceVO> refs = kbResultsRef.stream().map(this::mapToRagReference).toList();
                                metadataMap.put("ragReferences", refs);
                            }
                            try {
                                ragMetadataHolder[0] = objectMapper.writeValueAsString(metadataMap);
                                return Flux.just(METADATA_PREFIX + ragMetadataHolder[0]);
                            } catch (Exception e) {
                                log.warn("[Chat] 序列化metadata失败: {}", e.getMessage());
                            }
                        }
                        return Flux.empty();
                    });

                    // tool_result 已由 appendToolCallResult 写入 statusFluxes，此处不再重复推送，
                    // 否则前端会收到两次相同的 tool_result 事件（工具卡片渲染两份）。
                    long nextLlmStart = System.currentTimeMillis();
                    final int resultContentOffset = toolContentOffset;
                    Flux<String> toolEventFlux = Flux.concat(statusFluxes)
                            .concatWith(Flux.just(STATUS_PREFIX + toolEventGenerator.toolCompleteEvent(resultContentOffset)))
                            .concatWith(afterTool);
                    // 正文先于组件事件下发，确保组件插在完整正文之后，不腰斩已产出内容
                    toolEventFlux = leadingContentFlux.concatWith(toolEventFlux);

                    // ask_user 工具执行后中断循环，等待用户回复
                    boolean hasAskUser = toolResponses.stream()
                            .anyMatch(r -> AskUserTool.TOOL_NAME.equals(r.getName()));
                    if (hasAskUser) {
                        log.info("[Chat][Trace] ask_user 工具调用，中断工具循环，等待用户回复");
                        return toolEventFlux;
                    }

                    trimToolCallContext(messages);
                    return toolEventFlux.concatWith(processToolCallsRecursively(ctx, depth + 1, nextLlmStart, eventSink));
                });
    }

    /**
     * 流式模型调用重试
     *
     * @param ctx       对话上下文
     * @param chatModel 模型实例
     * @param prompt    模型输入
     * @param depth     工具调用轮次
     * @param eventSink SSE 事件通道
     * @return 模型流式响应
     */
    private Flux<ChatResponse> streamModelWithRetry(ChatContext ctx, Model chatModel,
                                                     GenerateOptions toolOptions, List<Msg> messages,
                                                     int depth, Sinks.Many<String> eventSink) {
        int retryTimes = resolveModelRetryTimes(ctx.getConfigMap());
        return streamModelAttempt(ctx, chatModel, toolOptions, messages, depth, eventSink, 0, retryTimes);
    }

    private Flux<ChatResponse> streamModelAttempt(ChatContext ctx, Model chatModel,
                                                   GenerateOptions toolOptions, List<Msg> messages,
                                                   int depth, Sinks.Many<String> eventSink,
                                                   int attempt, int retryTimes) {
        // 记录本轮尝试前 fullReply 的长度，用于失败时回滚
        int fullReplyLengthBefore = ctx.getFullReply().length();
        boolean[] receivedResponse = {false};
        return chatModel.stream(messages, List.of(), toolOptions)
                .takeUntilOther(Mono.delay(Duration.ofMillis(200))
                        .repeat()
                        .filter(tick -> ctx.isAborted())
                        .next())
                .doOnSubscribe(sub -> ctx.resetStreamTextTracking())
                .doOnNext(response -> {
                    receivedResponse[0] = true;
                    // 尝试从 chunk 提取 tool_call args delta，节流推「正在生成 · 已输出 N 字」
                    handleToolCallArgsDelta(response, ctx);
                })
                .doFinally(signal -> {
                    // 本轮模型流结束，清理 args 累积器避免跨轮泄漏
                    if (ctx.getToolArgsAccumulators() != null) {
                        ctx.getToolArgsAccumulators().clear();
                    }
                })
                .onErrorResume(e -> {
                    if (!receivedResponse[0] && attempt < retryTimes) {
                        int retryNo = attempt + 1;
                        long delayMs = (long) Math.pow(2, attempt) * 1000;
                        log.warn("[Chat] 流式模型调用失败，第{}次重试，等待{}ms: depth={}, error={}", retryNo, delayMs, depth, e.getMessage());
                        eventSink.tryEmitNext(STATUS_PREFIX + toolEventGenerator.errorRetryEvent(
                                "AI连接异常，正在重试中 " + retryNo + "/" + retryTimes,
                                classifyErrorCode(e), retryNo, retryTimes));
                        // 重试前回滚 fullReply 到本轮尝试前的状态，避免内容重复累积
                        if (ctx.getFullReply().length() > fullReplyLengthBefore) {
                            ctx.getFullReply().setLength(fullReplyLengthBefore);
                        }
                        // 重置 SensitiveStreamState，避免增量过滤状态混乱
                        if (ctx.getSensitiveStreamState() != null) {
                            Long agentId = ctx.getAgent() != null ? ctx.getAgent().getId() : null;
                            ctx.setSensitiveStreamState(new SensitiveWordFilter.StreamState(
                                    ctx.getConfigMap(), agentId, ctx.getSessionId()));
                        }
                        return Mono.delay(Duration.ofMillis(delayMs))
                                .thenMany(streamModelAttempt(ctx, chatModel, toolOptions, messages, depth, eventSink, attempt + 1, retryTimes));
                    }
                    return Flux.error(e);
                });
    }

    /**
     * 非流式 LLM 轮次：call() 获取完整回复后一次性输出
     */
    private Flux<String> processBlockingRound(ChatContext ctx, int depth, long llmCallStart,
                                               Sinks.Many<String> eventSink) {
        int maxSteps = resolveMaxExecutionSteps(ctx.getConfigMap());
        if (depth >= maxSteps) {
            log.warn("[Chat][Trace] 工具调用递归深度达到上限({})，停止循环", depth);
            return Flux.just("\n[工具调用轮次已达上限，请简化问题后重试]");
        }

        Model chatModel = ctx.getChatModel();
        List<Msg> messages = ctx.getMessages();
        GenerateOptions toolOptions = ctx.getToolOptions();
        Map<String, ToolBase> toolCallbackMap = ctx.getToolCallbackMap();
        Agent agent = ctx.getAgent();
        StringBuilder fullReply = ctx.getFullReply();
        int[] toolCallCountHolder = ctx.getToolCallCountHolder();
        int[] inputTokenHolder = ctx.getInputTokenHolder();
        int[] outputTokenHolder = ctx.getOutputTokenHolder();
        List<Map<String, Object>> toolEventsList = ctx.getToolEventsList();
        String requestId = ctx.getRequestId();
        List<LlmTraceSpanDTO> spans = ctx.getSpans();
        Map<String, Object> configMap = ctx.getConfigMap();
        String[] ragMetadataHolder = ctx.getRagMetadataHolder();

        String llmSpanId = "llm_" + depth;
        int retryTimes = resolveModelRetryTimes(configMap);

        ChatResponse response = null;
        Exception lastException = null;
        for (int attempt = 0; attempt <= retryTimes; attempt++) {
            try {
                response = ModelCalls.call(chatModel, new ArrayList<>(messages), toolOptions);
                break;
            } catch (Exception e) {
                lastException = e;
                if (attempt < retryTimes) {
                    int retryNo = attempt + 1;
                    long delayMs = (long) Math.pow(2, attempt) * 1000;
                    log.warn("[Chat] 非流式模型调用失败，第{}次重试，等待{}ms: depth={}, error={}", retryNo, delayMs, depth, e.getMessage());
                    eventSink.tryEmitNext(STATUS_PREFIX + toolEventGenerator.errorRetryEvent(
                            "AI连接异常，正在重试中 " + retryNo + "/" + retryTimes,
                            classifyErrorCode(e), retryNo, retryTimes));
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        if (response == null) {
            markStreamFailure(ctx, lastException);
            return Flux.just(STATUS_PREFIX + toolEventGenerator.errorEvent(
                    ctx.getStreamErrorMessage(), ctx.getStreamErrorCode()));
        }
        accumulateStreamUsage(response, inputTokenHolder, outputTokenHolder);

        Msg assistantMsg = Msg.builderForRole(MsgRole.ASSISTANT).content(response.getContent()).build();

        // 无工具调用 → 一次性输出完整文本
        if (assistantMsg == null || !Msgs.hasToolCalls(response)) {
            String text = Msgs.extractText(response);
            if (text == null) {
                text = "";
            }
            if (!text.isEmpty()) {
                ctx.appendTraceCompleteReply(text);
            }
            // 解析 inline thinking 标签（Ollama deepseek-r1 等）
            if (ctx.getReasoningContent().length() == 0 && !text.isEmpty()) {
                InlineThinkingStreamParser.ParseResult parsed = InlineThinkingStreamParser.parseComplete(text);
                if (!parsed.reasoningDelta().isEmpty()) {
                    ctx.appendReasoningContent(parsed.reasoningDelta());
                }
                text = parsed.contentDelta();
            } else if (InlineThinkingStreamParser.containsThinkingTags(text)) {
                text = InlineThinkingStreamParser.stripTags(text);
            }
            if (text.isEmpty() && ctx.getReasoningContent().length() == 0) {
                return Flux.empty();
            }
            SensitiveWordFilter.FilterResult filtered = SensitiveWordFilter.filterAiOutput(
                    text, configMap, agent.getId(), ctx.getSessionId());
            if (filtered.blocked()) {
                fullReply.setLength(0);
                fullReply.append(filtered.text());
                // 非流式路径同样置标记位，让 buildPersistMetadata 跳过 reasoningContent 暴露
                ctx.setSensitiveAiBlocked(true);
                spans.add(LlmTraceSpanDTO.of(llmSpanId, "s1", "llm_call", llmCallStart,
                        System.currentTimeMillis() - llmCallStart, "OK",
                        Map.of("depth", depth, "model", configMap.getOrDefault("modelId", ""),
                                "inputTokens", inputTokenHolder[0], "outputTokens", outputTokenHolder[0],
                                "streamOutput", false)));
                return Flux.just(STATUS_PREFIX + toolEventGenerator.sensitiveBlockEvent("ai_output", filtered.text()));
            }
            // 如果有 reasoning 内容，发送 reasoning_content 事件
            String reasoningSaved = ctx.getReasoningContent().toString();
            if (!reasoningSaved.isEmpty()) {
                return Flux.just(
                        STATUS_PREFIX + toolEventGenerator.reasoningEvent(reasoningSaved),
                        filtered.text());
            }
            fullReply.append(filtered.text());
            spans.add(LlmTraceSpanDTO.of(llmSpanId, "s1", "llm_call", llmCallStart,
                    System.currentTimeMillis() - llmCallStart, "OK",
                    Map.of("depth", depth, "model", configMap.getOrDefault("modelId", ""),
                            "inputTokens", inputTokenHolder[0], "outputTokens", outputTokenHolder[0],
                            "streamOutput", false,
                            "replyPreview", fullReply.length() > 500 ? fullReply.substring(0, 500) + "..." : fullReply.toString())));
            return Flux.just(filtered.text());
        }

        // 有工具调用 → 执行工具后继续递归
        messages.add(assistantMsg);
        List<ToolUseBlock> toolCalls = Msgs.extractToolUses(response);
        boolean asyncEnabled = Boolean.TRUE.equals(configMap.get("asyncToolCalls"));

        spans.add(LlmTraceSpanDTO.of(llmSpanId, "s1", "llm_call", llmCallStart,
                System.currentTimeMillis() - llmCallStart, "OK",
                Map.of("depth", depth, "model", configMap.getOrDefault("modelId", ""),
                        "toolCount", toolCalls.size(),
                        "toolNames", toolCalls.stream().map(ToolUseBlock::getName).toList().toString(),
                        "streamOutput", false)));

        List<Flux<String>> statusFluxes = new ArrayList<>();
        List<Map<String, Object>> kbResultsHolder = new ArrayList<>();
        List<ToolResultBlock> toolResponses = new ArrayList<>();
        appendAssistantLeadingTextBeforeToolCall(ctx, agent, Msgs.extractText(response));
        int toolContentOffset = resolveToolBlockOffset(ctx);

        if (asyncEnabled && toolCalls.size() > 1) {
            log.info("[Chat][Trace] 工具调用(depth={}): {}个工具, 并行执行", depth, toolCalls.size());
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (ToolUseBlock tc : toolCalls) {
                String tcArgs = toolInputToString(tc.getInput());
                long tcToolCallId = appendToolCallStart(ctx, toolEventsList, statusFluxes, tc.getName(), tcArgs, toolContentOffset);
                toolCallCountHolder[0]++;
                final String tcName = tc.getName();
                final String safeTcArgs = toolArgsSanitizer.forChatCall(tcArgs);
                final int offsetFinal = toolContentOffset;
                final long tcIdFinal = tcToolCallId;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    long tStart = System.currentTimeMillis();
                    String result = executeToolCallback(toolCallbackMap, tcName, safeTcArgs, agent.getId(), ctx.getSessionId(), requestId, null, ctx);
                    long tEnd = System.currentTimeMillis();
                    spans.add(LlmTraceSpanDTO.of("tool_" + toolCallCountHolder[0], llmSpanId, "tool_execute",
                            tStart, tEnd - tStart, "OK",
                            buildToolTraceAttributes(tcName, tcArgs, result)));
                    appendSubAgentTraceSpans(spans, "tool_" + toolCallCountHolder[0], tcName, result, tStart);
                    if ("query_knowledge".equals(tcName)) {
                        List<Map<String, Object>> kbResults = QueryKnowledgeTool.getSearchResults(requestId);
                        synchronized (kbResultsHolder) {
                            kbResultsHolder.addAll(kbResults);
                        }
                    }
                    // 暂存工具调用记录（复用 tcIdFinal 作为主键，前端按 id 拉取完整结果）
                    ToolCall toolCallLog = new ToolCall();
                    toolCallLog.setId(tcIdFinal);
                    toolCallLog.setToolName(tcName);
                    toolCallLog.setToolInput(safeTcArgs);
                    toolCallLog.setToolOutput(result);
                    toolCallLog.setStatus(result.startsWith(ToolResultPrefixes.FAILURE) || result.startsWith(ToolResultPrefixes.NOT_FOUND) ? "error" : "success");
                    toolCallLog.setErrorMessage(result.startsWith(ToolResultPrefixes.FAILURE) ? result : null);
                    synchronized (ctx.getPendingToolCalls()) {
                        ctx.getPendingToolCalls().add(toolCallLog);
                    }

                    synchronized (toolEventsList) {
                        appendToolCallResult(ctx, toolEventsList, statusFluxes, tcName, tcArgs, result, offsetFinal, tcIdFinal);
                    }
                    return result;
                }, lengBotExecutor));
            }
            for (int i = 0; i < toolCalls.size(); i++) {
                ToolUseBlock tc = toolCalls.get(i);
                String result = futures.get(i).join();
                toolResponses.add(ToolResultBlock.builder()
                        .id(tc.getId())
                        .name(tc.getName())
                        .output(TextBlock.builder().text(result).build())
                        .build());
            }
        } else {
            ToolUseBlock firstTool = toolCalls.get(0);
            log.info("[Chat][Trace] 工具调用(depth={}): {}个工具, 只执行第一个: {}",
                    depth, toolCalls.size(), firstTool.getName());
            String toolName = firstTool.getName();
            String toolArgs = toolInputToString(firstTool.getInput());
            toolCallCountHolder[0]++;

            String safeArgs = toolArgs;
            String callArgs = toolArgsSanitizer.forChatCall(safeArgs);
            long toolCallId = appendToolCallStart(ctx, toolEventsList, statusFluxes, toolName, safeArgs, toolContentOffset);

            long tToolStart = System.currentTimeMillis();
            String toolResult = executeToolCallback(toolCallbackMap, toolName, callArgs, agent.getId(), ctx.getSessionId(), requestId, null, ctx);
            long tToolEnd = System.currentTimeMillis();
            spans.add(LlmTraceSpanDTO.of("tool_" + toolCallCountHolder[0], llmSpanId, "tool_execute",
                    tToolStart, tToolEnd - tToolStart, "OK",
                    buildToolTraceAttributes(toolName, safeArgs, toolResult)));
            appendSubAgentTraceSpans(spans, "tool_" + toolCallCountHolder[0], toolName, toolResult, tToolStart);

            if ("query_knowledge".equals(toolName)) {
                List<Map<String, Object>> kbResults = QueryKnowledgeTool.getSearchResults(requestId);
                if (!kbResults.isEmpty()) {
                    kbResultsHolder.addAll(kbResults);
                }
            }

            // 暂存工具调用记录（复用 toolCallId 作为主键，前端按 id 拉取完整结果）
            ToolCall toolCallLog = new ToolCall();
            toolCallLog.setId(toolCallId);
            toolCallLog.setToolName(toolName);
            toolCallLog.setToolInput(callArgs);
            toolCallLog.setToolOutput(toolResult);
            toolCallLog.setStatus(ToolResultPrefixes.isError(toolResult) ? "error" : "success");
            toolCallLog.setErrorMessage(ToolResultPrefixes.isError(toolResult) ? toolResult : null);
            ctx.getPendingToolCalls().add(toolCallLog);

            List<String> emittedEvents = ToolEventEmitter.drain();
            for (String event : emittedEvents) {
                toolEventsList.add(Map.of("type", "tool_status", "message", event,
                        "contentOffset", toolContentOffset));
                statusFluxes.add(Flux.just(STATUS_PREFIX + toolEventGenerator.toolStatusEvent(event, toolContentOffset)));
            }

            appendToolCallResult(ctx, toolEventsList, statusFluxes, toolName, safeArgs, toolResult, toolContentOffset, toolCallId);
            toolResponses.add(ToolResultBlock.builder()
                    .id(firstTool.getId())
                    .name(toolName)
                    .output(TextBlock.builder().text(toolResult).build())
                    .build());
        }

        messages.add(ToolResultMessage.builder()
                    .results(toolResponses)
                .build());

        List<Map<String, Object>> kbResultsRef = kbResultsHolder;
        Flux<String> afterTool = buildToolMetadataFlux(kbResultsRef, toolEventsList, ragMetadataHolder);

        // tool_result 已由 appendToolCallResult 写入 statusFluxes，此处不再重复推送，
        // 否则前端会收到两次相同的 tool_result 事件（工具卡片渲染两份）。
        final int resultContentOffset = toolContentOffset;
        Flux<String> toolEventFlux = Flux.concat(statusFluxes)
                .concatWith(Flux.just(STATUS_PREFIX + toolEventGenerator.toolCompleteEvent(resultContentOffset)))
                .concatWith(afterTool);
        trimToolCallContext(messages);
        return toolEventFlux.concatWith(processToolCallsRecursively(ctx, depth + 1, System.currentTimeMillis(), eventSink));
    }

    /**
     * 工具调用上下文裁剪：当消息列表总字符数超过阈值时，压缩早期工具调用轮次为摘要消息，
     * 防止多轮工具调用撑爆上下文窗口。
     *
     * @param messages 消息列表（会被原地修改）
     */
    private void trimToolCallContext(List<Msg> messages) {
        // 1. 压缩历史中的 write_file 大参数（对标 Yuxi L1），再按字符上限裁剪轮次
        ChatMessageContextUtil.normalizeMessagesForLlm(messages);
        ChatMessageContextUtil.trimToolCallContext(messages, MAX_TOOL_CONTEXT_CHARS, TOOL_ROUNDS_TO_KEEP);
    }

    private String executeToolCallback(Map<String, ToolBase> toolCallbackMap, String toolName,
                                       String callArgs, Long agentId, Long sessionId, String requestId,
                                       Sinks.Many<String> eventSink, ChatContext chatContext) {
        ToolBase callback = toolCallbackMap.get(toolName);
        if (callback != null) {
            try {
                if (chatContext != null && chatContext.isAborted()) {
                    return ToolResultPrefixes.failureJson("CLIENT_ABORTED");
                }
                // 参数可能因 maxTokens 在字符串中途被截断：写文件场景先尝试修复再执行
                String effectiveArgs = callArgs;
                if (isLikelyTruncatedJson(effectiveArgs)) {
                    String repaired = toolArgsSanitizer.tryRepairTruncatedWriteArgs(toolName, effectiveArgs);
                    if (repaired != null) {
                        effectiveArgs = stripInternalRepairFlags(repaired);
                        log.warn("[Chat] 工具参数疑似截断，已修复后执行: name={}, rawLen={}, repairedLen={}",
                                toolName, callArgs != null ? callArgs.length() : 0, effectiveArgs.length());
                    }
                }
                // 2.1 工具执行超时保护：CompletableFuture 包装 + get(timeout)，防止 MCP 工具卡死
                long timeoutSeconds = resolveToolExecutionTimeoutSeconds(toolName, effectiveArgs);
                final String argsForCall = effectiveArgs != null ? effectiveArgs : "{}";
                // AgentScope 2.0.1 上下文注入：通过 RuntimeContext 透传给工具（WriteTodosTool / 知识库 / 记忆工具依赖）
                RuntimeContext toolRuntimeContext = buildToolRuntimeContext(agentId, chatContext, sessionId, requestId, argsForCall);
                String result = CompletableFuture.supplyAsync(() -> {
                    try {
                        if (chatContext != null && chatContext.isAborted()) {
                            return ToolResultPrefixes.failureJson("CLIENT_ABORTED");
                        }
                        // 流式模式：绑定 Sink 使工具内部的 emit() 实时推送给前端
                        if (eventSink != null) {
                            ToolEventEmitter.setupSink(eventSink);
                        }
                        ToolResultBlock resultBlock = callback.callAsync(ToolCallParam.builder()
                                .input(parseToolArgsToMap(argsForCall))
                                .runtimeContext(toolRuntimeContext).build()).block();
                        return toolResultToText(resultBlock);
                    } finally {
                        if (eventSink != null) {
                            ToolEventEmitter.teardownSink();
                        }
                    }
                }, lengBotExecutor).get(timeoutSeconds, TimeUnit.SECONDS);
                if (chatContext != null && chatContext.isAborted()) {
                    return ToolResultPrefixes.failureJson("CLIENT_ABORTED");
                }
                if (!ToolResultPrefixes.isError(result)) {
                    sessionAttachmentRegistrar.registerFromToolResult(sessionId, toolName, result);
                    // write_todos 成功后把合并结果回写到 ChatContext，保证下次调用拿到最新基准（防丢项核心）
                    if ("write_todos".equals(toolName) && chatContext != null) {
                        updateCurrentTodosSnapshot(chatContext, result);
                    }
                }
                return result;
            } catch (TimeoutException e) {
                long timeoutSeconds = resolveToolExecutionTimeoutSeconds(toolName, callArgs);
                log.error("[Chat] 工具执行超时: name={}, timeout={}s", toolName, timeoutSeconds);
                return ToolResultPrefixes.failureJson("工具执行超时（" + timeoutSeconds + "秒），请稍后重试");
            } catch (Exception e) {
                // 工具参数 JSON 不完整（多为模型输出被 maxTokens 截断，字符串未闭合）
                if (isToolArgsParseError(e)) {
                    String repaired = toolArgsSanitizer.tryRepairTruncatedWriteArgs(toolName, callArgs);
                    if (repaired != null) {
                        try {
                            String retryArgs = stripInternalRepairFlags(repaired);
                            log.warn("[Chat] 工具参数解析失败后二次修复重试: name={}", toolName);
                            // 避免递归死循环：直接再调一次 callback（同步），同样注入 RuntimeContext
                            ToolResultBlock retryBlock = callback.callAsync(ToolCallParam.builder()
                                    .input(parseToolArgsToMap(retryArgs))
                                    .runtimeContext(buildToolRuntimeContext(agentId, chatContext, sessionId, requestId, retryArgs)).build()).block();
                            String retryResult = toolResultToText(retryBlock);
                            if (!ToolResultPrefixes.isError(retryResult)) {
                                sessionAttachmentRegistrar.registerFromToolResult(sessionId, toolName, retryResult);
                            }
                            return retryResult;
                        } catch (Exception retryEx) {
                            log.error("[Chat] 截断参数修复后仍失败: name={}, error={}", toolName, retryEx.getMessage());
                        }
                    }
                    log.error("[Chat] 工具参数解析失败(疑似模型输出被截断): name={}, argsLen={}, error={}",
                            toolName, callArgs != null ? callArgs.length() : 0, e.getMessage());
                    return ToolResultPrefixes.failureJson("工具参数不完整，请重新调用并完整传入所需参数后重试。");
                }
                log.error("[Chat] 工具执行异常: name={}, error={}", toolName, e.getMessage(), e);
                return ToolResultPrefixes.failureJson(ToolResultPrefixes.FAILURE + ": " + e.getMessage());
            }
        }
        log.warn("[Chat][Trace] 工具不存在: name={}, 可用工具={}", toolName, toolCallbackMap.keySet());
        return ToolResultPrefixes.failureJson(ToolResultPrefixes.NOT_FOUND + ": " + toolName);
    }

    /**
     * 解析 write_todos 工具结果，把合并后的 todos 回写到 ChatContext.currentTodosSnapshot。
     * <p>下次 write_todos 调用时，WriteTodosTool.loadHistoryTodos 拿到的就是本次合并结果，
     * 避免同一轮内多次调用因基准过期导致丢项或重复新增</p>
     *
     * @param chatContext 对话上下文
     * @param toolResult  write_todos 返回的 JSON 字符串，格式：{"success":true,"todos":[{id,content,status}]}
     */
    private void updateCurrentTodosSnapshot(ChatContext chatContext, String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(toolResult);
            if (!root.path("success").asBoolean(false)) {
                return;
            }
            com.fasterxml.jackson.databind.JsonNode todosNode = root.path("todos");
            if (!todosNode.isArray()) {
                return;
            }
            // 用 ArrayList 包装保证可变（loadCurrentTodos 返回的可能不可变）
            List<Map<String, String>> snapshot = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : todosNode) {
                Map<String, String> m = new java.util.LinkedHashMap<>();
                m.put("id", item.path("id").asText(""));
                m.put("content", item.path("content").asText(""));
                m.put("status", item.path("status").asText("pending"));
                snapshot.add(m);
            }
            chatContext.setCurrentTodosSnapshot(snapshot);
        } catch (Exception e) {
            log.warn("[Chat] 回写 todos 快照失败: error={}", e.getMessage());
        }
    }

    /** 粗判 JSON 是否因截断而不完整（无法 parse 或括号/引号不平衡） */
    private static boolean isLikelyTruncatedJson(String args) {
        if (args == null || args.isBlank()) {
            return false;
        }
        String trimmed = args.trim();
        if (!trimmed.startsWith("{")) {
            return true;
        }
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static String stripInternalRepairFlags(String json) {
        if (json == null) {
            return "{}";
        }
        // 去掉内部标记字段，避免 MethodToolCallback 因未知参数失败
        return json.replaceAll(",\\s*\"_repairedFromTruncation\"\\s*:\\s*true", "")
                .replaceAll("\"_repairedFromTruncation\"\\s*:\\s*true\\s*,?", "");
    }

    private long resolveToolExecutionTimeoutSeconds(String toolName, String callArgs) {
        if (!DelegateSubAgentTool.TOOL_NAME.equals(toolName)) {
            return TOOL_EXECUTION_TIMEOUT_SECONDS;
        }
        long timeoutSeconds = TOOL_EXECUTION_TIMEOUT_SECONDS;
        try {
            List<String> subNames = parseSubagentNames(callArgs);
            if (subNames.isEmpty()) {
                return timeoutSeconds;
            }
            long maxReadTimeout = timeoutSeconds;
            // 一次 IN 查询所有 SubAgent，替代循环内 N 次 getByName（v3.1 2.2.3）
            List<com.lengbot.entity.SubAgent> subAgents = subAgentService.listByNameIn(subNames);
            for (com.lengbot.entity.SubAgent subAgent : subAgents) {
                int readTimeout = subAgent.getReadTimeoutSeconds() != null
                        ? Math.max(10, Math.min(300, subAgent.getReadTimeoutSeconds()))
                        : (int) TOOL_EXECUTION_TIMEOUT_SECONDS;
                maxReadTimeout = Math.max(maxReadTimeout, readTimeout);
            }
            timeoutSeconds = Math.max(30L, maxReadTimeout + 30L);
        } catch (Exception e) {
            log.warn("[Chat] SubAgent tool timeout resolve failed, fallback to default: {}", e.getMessage());
        }
        return Math.min(timeoutSeconds, 360L);
    }

    private Flux<String> buildToolMetadataFlux(List<Map<String, Object>> kbResultsRef,
                                               List<Map<String, Object>> toolEventsList,
                                               String[] ragMetadataHolder) {
        return Flux.defer(() -> {
            if (!kbResultsRef.isEmpty() || !toolEventsList.isEmpty()) {
                Map<String, Object> metadataMap = new java.util.LinkedHashMap<>();
                if (!toolEventsList.isEmpty()) {
                    // toolEvents 拆到 message.tool_events 独立列；中间 metadata 仅承载 toolBlockOffsets
                    List<Map<String, Object>> compactEvents = ToolEventCompactUtil.compactForPersistence(toolEventsList);
                    List<Integer> offsets = ToolEventCompactUtil.extractToolBlockOffsets(compactEvents);
                    if (!offsets.isEmpty()) {
                        metadataMap.put("toolBlockOffsets", offsets);
                    }
                }
                if (!kbResultsRef.isEmpty()) {
                    List<RagReferenceVO> refs = kbResultsRef.stream().map(this::mapToRagReference).toList();
                    metadataMap.put("ragReferences", refs);
                }
                try {
                    ragMetadataHolder[0] = objectMapper.writeValueAsString(metadataMap);
                    return Flux.just(METADATA_PREFIX + ragMetadataHolder[0]);
                } catch (Exception e) {
                    log.warn("[Chat] 序列化metadata失败: {}", e.getMessage());
                }
            }
            return Flux.empty();
        });
    }

    /**
     * MiMo 直连流式（联网搜索 / 视频理解等）
     * <p>MiMo 特有逻辑（reasoning 提取、多模态处理）已内聚在 MimoChatClient 中，
     * 此处仅处理通用关注点：敏感词过滤、回复累积、日志</p>
     */
    private Flux<String> streamMimoDirect(ChatContext ctx, int depth, long llmCallStart,
                                          ModelProvider provider,
                                          List<Msg> messages) {
        StringBuilder fullReply = ctx.getFullReply();
        Map<String, Object> configMap = ctx.getConfigMap();
        SensitiveWordFilter.StreamState sensitiveState = ctx.getSensitiveStreamState();

        var mediaAttachments = ChatDocumentMessageUtil.filterMedia(ctx.getRequest().getAttachments());
        return mimoChatClient.streamChat(provider, configMap, messages, mediaAttachments)
                .concatMap(chunk -> {
                    // 已触发敏感拦截：丢弃后续 chunk，避免重复发 sensitive_block 与正文增量
                    if (ctx.isSensitiveAiBlocked()) {
                        return Flux.empty();
                    }
                    // MimoChatClient 已处理 reasoning 提取（emitReasoningContent），
                    // 此处直接透传 [STATUS] 事件，无需重复解析
                    if (chunk.startsWith(STATUS_PREFIX)) {
                        return Flux.just(chunk);
                    }
                    String delta = sensitiveState != null ? sensitiveState.processChunk(chunk) : chunk;
                    if (sensitiveState != null && sensitiveState.isBlocked()) {
                        // MiMo 直连首次命中敏感词：清空已累积正文写入拦截文案，置标记位短路后续 chunk
                        fullReply.setLength(0);
                        fullReply.append(SensitiveWordFilter.AI_BLOCK_MESSAGE);
                        ctx.setSensitiveAiBlocked(true);
                        return Flux.just(STATUS_PREFIX + toolEventGenerator.sensitiveBlockEvent("ai_output", SensitiveWordFilter.AI_BLOCK_MESSAGE));
                    }
                    if (delta.isEmpty()) {
                        return Flux.empty();
                    }
                    fullReply.append(delta);
                    return Flux.just(delta);
                })
                .doOnComplete(() -> {
                    long elapsed = System.currentTimeMillis() - llmCallStart;
                    log.info("[Chat][MiMo] 直连完成: depth={}, elapsed={}ms, length={}",
                            depth, elapsed, fullReply.length());
                    if (fullReply.length() == 0) {
                        log.warn("[Chat][MiMo] 直连返回空内容: modelId={}, webSearch={}",
                                configMap.get("modelId"), configMap.get(ConfigKeys.Agent.ENABLE_WEB_SEARCH));
                    }
                })
                .doOnError(e -> log.error("[Chat][MiMo] 直连失败: {}", e.getMessage()));
    }

    private int resolveMaxExecutionSteps(Map<String, Object> configMap) {
        if (configMap == null) return 20;
        Object val = configMap.get(ConfigKeys.Agent.MAX_EXECUTION_STEPS);
        if (val instanceof Number n) return Math.max(1, Math.min(200, n.intValue()));
        if (val != null) {
            try { return Math.max(1, Math.min(200, Integer.parseInt(val.toString()))); } catch (Exception ignored) {}
        }
        return 20;
    }

    private int resolveModelRetryTimes(Map<String, Object> configMap) {
        if (configMap == null) return 2;
        Object val = configMap.get(ConfigKeys.Agent.MODEL_RETRY_TIMES);
        if (val instanceof Number n) return Math.max(0, Math.min(10, n.intValue()));
        if (val != null) {
            try { return Math.max(0, Math.min(10, Integer.parseInt(val.toString()))); } catch (Exception ignored) {}
        }
        return 2;
    }

    private boolean isStreamOutputEnabled(Map<String, Object> configMap) {
        if (configMap == null) {
            return true;
        }
        Object val = configMap.get(ConfigKeys.Agent.STREAM_OUTPUT);
        if (val == null) {
            return true;
        }
        if (val instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(val.toString());
    }

    /**
     * 从流式 getText() 提取增量后送入 inline thinking 解析器。
     */
    private InlineThinkingStreamParser.ParseResult feedStreamTextChunk(ChatContext ctx, String currentText) {
        String delta = ctx.consumeStreamTextDelta(currentText);
        if (delta.isEmpty()) {
            return InlineThinkingStreamParser.ParseResult.empty();
        }
        ctx.appendRawLlmStreamText(delta);
        return ctx.computeInlineThinkingStreamDelta();
    }

    /**
     * 将 inline thinking 解析结果转为 SSE：reasoning_content + 正文 chunk，并写入 ctx.reasoningContent / fullReply。
     */
    private Flux<String> fluxFromInlineThinking(ChatContext ctx, Agent agent,
                                                InlineThinkingStreamParser.ParseResult parsed,
                                                Runnable onContentAppended) {
        return fluxFromInlineThinking(ctx, agent, parsed, onContentAppended, true);
    }

    private Flux<String> fluxFromInlineThinking(ChatContext ctx, Agent agent,
                                                InlineThinkingStreamParser.ParseResult parsed,
                                                Runnable onContentAppended,
                                                boolean appendToFullReply) {
        // 已触发敏感拦截：丢弃后续 chunk（reasoning/正文均不再累积、不再重复发 sensitive_block），
        // 只让首条 sensitive_block 事件下发，DONE 由 buildDoneEvent 按最小 metadata 输出
        if (ctx.isSensitiveAiBlocked()) {
            return Flux.empty();
        }
        if (parsed.isEmpty()) {
            return Flux.empty();
        }
        List<String> items = new ArrayList<>(2);
        String reasoningDelta = parsed.reasoningDelta();
        String contentDelta = parsed.contentDelta();
        if (!reasoningDelta.isEmpty()) {
            String reasoning = ctx.appendReasoningContent(reasoningDelta);
            if (!reasoning.isEmpty()) {
                items.add(STATUS_PREFIX + toolEventGenerator.reasoningEvent(reasoning));
            }
        }
        if (!contentDelta.isEmpty()) {
            String delta = ctx.getSensitiveStreamState() != null
                    ? ctx.getSensitiveStreamState().processChunk(contentDelta)
                    : SensitiveWordFilter.filterAiOutput(contentDelta, ctx.getConfigMap(), agent.getId(), ctx.getSessionId()).text();
            if (ctx.getSensitiveStreamState() != null && ctx.getSensitiveStreamState().isBlocked()) {
                // 首次命中：清空已累积正文写入拦截文案，置标记位让后续 chunk 全部短路
                ctx.getFullReply().setLength(0);
                ctx.getFullReply().append(SensitiveWordFilter.AI_BLOCK_MESSAGE);
                ctx.setSensitiveAiBlocked(true);
                return Flux.just(STATUS_PREFIX + toolEventGenerator.sensitiveBlockEvent("ai_output", SensitiveWordFilter.AI_BLOCK_MESSAGE));
            }
            if (!delta.isEmpty()) {
                if (appendToFullReply) {
                    ctx.getFullReply().append(delta);
                }
                if (onContentAppended != null) {
                    onContentAppended.run();
                }
                items.add(delta);
            }
        }
        return items.isEmpty() ? Flux.empty() : Flux.fromIterable(items);
    }

    /** 同步写入 leading 正文（用于工具 offset 计算，避免与后续 Flux 重复 append） */
    private boolean appendInlineThinkingContentDelta(ChatContext ctx, Agent agent,
                                                     InlineThinkingStreamParser.ParseResult parsed) {
        if (parsed == null || parsed.isEmpty() || parsed.contentDelta().isEmpty()) {
            return false;
        }
        String delta = ctx.getSensitiveStreamState() != null
                ? ctx.getSensitiveStreamState().processChunk(parsed.contentDelta())
                : SensitiveWordFilter.filterAiOutput(parsed.contentDelta(), ctx.getConfigMap(), agent.getId(), ctx.getSessionId()).text();
        if (delta.isEmpty()) {
            return false;
        }
        ctx.getFullReply().append(delta);
        return true;
    }

    private float[] embedText(String text) {
        double[] result = textEmbeddingService.embed(text);
        if (result == null) {
            return new float[0];
        }
        float[] floatResult = new float[result.length];
        for (int i = 0; i < result.length; i++) {
            floatResult[i] = (float) result[i];
        }
        return floatResult;
    }

    @Override
    public List<RagReferenceVO> getRagReferences(Long sessionId, Long agentId, String question) {
        Agent agent = initMiddleware.loadAgent(agentId);
        if (agent == null) {
            return List.of();
        }
        List<Map<String, Object>> searchResults = getRagSearchResults(agent.getId(), question);
        return searchResults.stream().map(this::mapToRagReference).toList();
    }

    private List<Map<String, Object>> getRagSearchResults(Long agentId, String question) {
        List<Long> knowledgeIds = agentService.getKnowledgeIds(agentId);
        if (knowledgeIds.isEmpty()) {
            return List.of();
        }
        try {
            float[] queryVector = embedText(question);
            List<Map<String, Object>> allResults = new ArrayList<>();
            List<CompletableFuture<List<Map<String, Object>>>> futures = knowledgeIds.stream()
                    .map(knowledgeId -> CompletableFuture.supplyAsync(() -> {
                        try {
                            Knowledge knowledge = knowledgeService.getById(knowledgeId);
                            int topK = ragParamResolver.resolveTopK(null, null, knowledge != null ? knowledge.getConfig() : null, RagParamResolver.DEFAULT_TOP_K);
                            double threshold = ragParamResolver.resolveThreshold(null, null, knowledge != null ? knowledge.getConfig() : null, RagParamResolver.DEFAULT_THRESHOLD);
                            return embeddingService.searchSimilar(knowledgeId, queryVector, topK, threshold);
                        } catch (Exception e) {
                            log.warn("[Chat] 知识库检索失败: knowledgeId={}, error={}", knowledgeId, e.getMessage());
                            return List.<Map<String, Object>>of();
                        }
                    }, lengBotExecutor))
                    .toList();
            futures.forEach(f -> allResults.addAll(f.join()));
            return allResults;
        } catch (Exception e) {
            log.warn("[Chat] RAG检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 累加流式响应中的 Token 用量（OpenAI 兼容 API 通常在最后一个空 choices chunk 返回 usage）
     */
    /**
     * 按需推送 skill_active 事件：当工具调用属于某个 Skill 时，推送该 Skill 的 metadata。
     * 同一 Skill 只推送一次。
     */
    private Flux<String> emitSkillActiveIfNeeded(ChatContext ctx, String toolName,
                                                  List<Map<String, Object>> toolEventsList, int contentOffset) {
        Map<String, Map<String, Object>> mapping = ctx.getToolNameToSkillDetail();
        if (mapping == null || mapping.isEmpty()) {
            return Flux.empty();
        }
        Map<String, Object> skillDetail = mapping.get(toolName);
        if (skillDetail == null) {
            return Flux.empty();
        }
        String skillName = (String) skillDetail.get("name");
        // 同一 Skill 只推送一次
        boolean alreadyEmitted = toolEventsList.stream()
                .filter(e -> "skill_active".equals(e.get("type")))
                .flatMap(e -> {
                    Object skills = e.get("skills");
                    if (skills instanceof List<?> list) {
                        return list.stream();
                    }
                    return java.util.stream.Stream.empty();
                })
                .anyMatch(s -> {
                    if (s instanceof Map<?, ?> m) {
                        return skillName.equals(m.get("name"));
                    }
                    return false;
                });
        if (alreadyEmitted) {
            return Flux.empty();
        }
        List<Map<String, Object>> singleSkill = List.of(skillDetail);
        Map<String, Object> evt = new HashMap<>();
        evt.put("type", "skill_active");
        evt.put("skills", singleSkill);
        evt.put("contentOffset", contentOffset);
        toolEventsList.add(evt);
        try {
            return Flux.just(STATUS_PREFIX + objectMapper.writeValueAsString(evt));
        } catch (Exception e) {
            return Flux.empty();
        }
    }

    /**
     * 工具调用前记录正文前缀锚点，入库时据此重新对齐 contentOffset。
     */
    private void putContentPrefixAnchor(ChatContext ctx, Map<String, Object> evt, int contentOffset) {
        if (ctx == null || evt == null || contentOffset <= 0) {
            return;
        }
        String reply = ctx.getFullReply().toString();
        int splitAt = ToolEventCompactUtil.resolveToolBlockSplitOffset(reply, null, contentOffset);
        evt.put("contentOffset", splitAt);
        if (splitAt > 0) {
            evt.put("contentPrefixAnchor", reply.substring(0, splitAt));
        }
    }

    /** 按句末标点对齐工具块切分点 */
    private int resolveToolBlockOffset(ChatContext ctx) {
        String reply = ctx.getFullReply().toString();
        return ToolEventCompactUtil.resolveToolBlockSplitOffset(reply, null, reply.length());
    }

    /**
     * 非流式 / 阻塞路径：同一轮 assistant 消息若携带正文，须先写入 fullReply 再计算 tool offset。
     */
    private void appendAssistantLeadingTextBeforeToolCall(ChatContext ctx, Agent agent, String assistantText) {
        if (ctx == null || assistantText == null || assistantText.isEmpty()) {
            return;
        }
        String text = assistantText;
        ctx.appendTraceCompleteReply(text);
        if (ctx.getReasoningContent().length() == 0) {
            InlineThinkingStreamParser.ParseResult parsed = InlineThinkingStreamParser.parseComplete(text);
            if (!parsed.reasoningDelta().isEmpty()) {
                ctx.appendReasoningContent(parsed.reasoningDelta());
            }
            text = parsed.contentDelta();
        } else if (InlineThinkingStreamParser.containsThinkingTags(text)) {
            text = InlineThinkingStreamParser.stripTags(text);
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        Map<String, Object> configMap = ctx.getConfigMap();
        Long agentId = agent != null ? agent.getId() : null;
        String filtered = SensitiveWordFilter.filterAiOutput(
                text, configMap, agentId, ctx.getSessionId()).text();
        if (!filtered.isEmpty()) {
            ctx.getFullReply().append(filtered);
        }
    }

    /** SSE/落库展示用：写文件大参数改为短摘要，避免前端与 metadata 膨胀 */
    private String compactArgsForEvent(String toolName, String args) {
        return toolArgsSanitizer.compactForHistory(toolName, args);
    }

    private long appendToolCallStart(ChatContext ctx, List<Map<String, Object>> toolEventsList,
                                     List<Flux<String>> statusFluxes,
                                     String toolName, String args, int contentOffset) {
        // 按需推送 skill_active（工具属于某个 Skill 时）
        Flux<String> skillFlux = emitSkillActiveIfNeeded(ctx, toolName, toolEventsList, contentOffset);
        if (skillFlux != null) {
            statusFluxes.add(skillFlux);
        }

        if (DelegateSubAgentTool.TOOL_NAME.equals(toolName)) {
            int delegationIndex = ctx != null ? ctx.assignSubAgentDelegationIndex() : 0;
            if (ctx != null) {
                // 批次事件由 SubAgentTaskService 统一发布；这里只提供本轮插入位置和委派序号。
                // 与普通 tool_call 一致：记录句末对齐后的切分点与正文前缀锚点，避免前端按滞后 offset 截断正文。
                String reply = ctx.getFullReply().toString();
                int splitAt = ToolEventCompactUtil.resolveToolBlockSplitOffset(reply, null, contentOffset);
                ctx.setSubAgentContentOffset(splitAt);
                ctx.setSubAgentContentPrefixAnchor(splitAt > 0 ? reply.substring(0, splitAt) : null);
                ctx.setSubAgentDelegationIndex(delegationIndex);
            }
            // 委派类工具不入 tool_calls 表，返回 0 表示无 toolCallId
            return 0L;
        }
        // 预生成 toolCallId：tool_call/tool_result 事件 + tool_calls 表主键共用同一 id
        long toolCallId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        String dn = getToolDisplayName(ctx, toolName);
        String icon = getToolIcon(ctx, toolName);
        Map<String, Object> callEvt = new java.util.LinkedHashMap<>();
        callEvt.put("type", "tool_call");
        callEvt.put("toolName", toolName);
        if (dn != null) callEvt.put("displayName", dn);
        if (icon != null) callEvt.put("icon", icon);
        callEvt.put("args", compactArgsForEvent(toolName, args));
        callEvt.put("contentOffset", contentOffset);
        callEvt.put("toolCallId", String.valueOf(toolCallId));
        putContentPrefixAnchor(ctx, callEvt, contentOffset);
        int normalizedOffset = callEvt.get("contentOffset") instanceof Number n ? n.intValue() : contentOffset;
        toolEventsList.add(callEvt);
        String callJson = toolEventGenerator.toolCallEvent(toolName, dn, icon, compactArgsForEvent(toolName, args), normalizedOffset, toolCallId);
        if (ctx != null && ctx.getRealtimeStatusEmitter() != null) {
            ctx.emitRealtimeStatus(callJson);
        } else if (statusFluxes != null) {
            statusFluxes.add(Flux.just(STATUS_PREFIX + callJson));
        }
        // 文件写入类工具立即推「正在生成 xxx...」起始状态，替代裸 spinner 让用户感知到 AI 正在写哪个文件
        emitFileWritingStatus(ctx, statusFluxes, toolName, args);
        return toolCallId;
    }

    /**
     * 从 SpringAI 流式 chunk 中提取 tool_call arguments 增量，节流推送文件写入进度
     * <p>若适配器在中间 chunk 不暴露 args delta（仅在最终 chunk 给完整 args），
     * 本方法无中间态可推，退化为 B-3「正在生成 xxx...」兜底。</p>
     *
     * @param response 模型流式响应块
     * @param ctx      对话上下文
     */
    private void handleToolCallArgsDelta(ChatResponse response, ChatContext ctx) {
        if (response == null || ctx == null || ctx.getToolArgsAccumulators() == null) {
            return;
        }
        try {
            Msg assistantMsg = Msg.builderForRole(MsgRole.ASSISTANT).content(response.getContent()).build();
            if (assistantMsg == null) {
                return;
            }
            List<ToolUseBlock> toolCalls = Msgs.extractToolUses(response);
            if (toolCalls == null || toolCalls.isEmpty()) {
                return;
            }
            for (ToolUseBlock tc : toolCalls) {
                String key = (tc.getId() != null && !tc.getId().isBlank())
                        ? tc.getId()
                        : ("name:" + (tc.getName() != null ? tc.getName() : "unknown"));
                ToolCallAccumulator acc = ctx.getToolArgsAccumulators().computeIfAbsent(key, k -> {
                    ToolCallAccumulator created = new ToolCallAccumulator();
                    created.setToolCallKey(k);
                    created.setToolName(tc.getName());
                    return created;
                });
                if (tc.getName() != null && !tc.getName().isBlank()) {
                    acc.setToolName(tc.getName());
                }
                // 仅文件写入类工具推进度，避免其它工具 args 噪音
                if (!ToolCallAccumulator.isFileWritingTool(acc.getToolName())) {
                    continue;
                }
                acc.acceptArgsFragment(toolInputToString(tc.getInput()));
                acc.tryExtractPath();
                if (!acc.shouldPush()) {
                    continue;
                }
                String basename = acc.basename();
                if (basename == null) {
                    continue;
                }
                String msg = "正在生成 " + basename + " · 已输出 " + acc.getArgsLen() + " 字";
                ctx.emitRealtimeStatus(toolEventGenerator.toolStatusEvent(msg, 0));
                acc.markPushed();
            }
        } catch (Exception e) {
            log.debug("[Chat] tool_call args delta 处理跳过: {}", e.getMessage());
        }
    }

    /**
     * 对 sandbox_write_file / sandbox_append_file 推送「正在生成 {filename}...」起始状态
     * <p>替代原先工具执行期间前端只能看到裸 spinner 的体验，让用户立刻知道 AI 正在写哪个文件</p>
     * <p>仅在工具属于文件写入类时推送，其他工具直接跳过</p>
     *
     * @param ctx 对话上下文（提供实时推送通道）
     * @param statusFluxes 非流式模式下的批量状态收集容器
     * @param toolName 工具名称
     * @param args 工具入参 JSON（含 path 字段）
     */
    private void emitFileWritingStatus(ChatContext ctx, List<Flux<String>> statusFluxes,
                                       String toolName, String args) {
        // 仅文件写入类工具推起始状态，其他工具跳过避免噪音
        if (!"sandbox_write_file".equals(toolName) && !"sandbox_append_file".equals(toolName)) {
            return;
        }
        String basename = extractFileBasename(args);
        if (basename == null) {
            return;
        }
        String msg = "正在生成 " + basename + "...";
        String statusJson = toolEventGenerator.toolStatusEvent(msg, 0);
        if (ctx != null && ctx.getRealtimeStatusEmitter() != null) {
            ctx.emitRealtimeStatus(statusJson);
        } else if (statusFluxes != null) {
            statusFluxes.add(Flux.just(STATUS_PREFIX + statusJson));
        }
    }

    /**
     * 从工具入参 JSON 中提取 path 字段的 basename（仅文件名部分）
     *
     * @param args 工具入参 JSON
     * @return basename；args 非法或无 path 字段时返回 null
     */
    private String extractFileBasename(String args) {
        if (args == null || args.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(args);
            JsonNode pathNode = node.get("path");
            if (pathNode == null || pathNode.isNull()) {
                return null;
            }
            String path = pathNode.asText("");
            if (path.isEmpty()) {
                return null;
            }
            // 取最后一段作为文件名（path 可能是 outputs/reports/report.md）
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        } catch (Exception e) {
            return null;
        }
    }

    private void appendToolCallResult(ChatContext ctx, List<Map<String, Object>> toolEventsList, List<Flux<String>> statusFluxes,
                                    String toolName, String args, String result, int contentOffset, long toolCallId) {
        String truncated = toolEventGenerator.truncateForSse(result);
        if (DelegateSubAgentTool.TOOL_NAME.equals(toolName)
                || DelegateSubAgentTool.RESULT_TOOL_NAME.equals(toolName)
                || DelegateSubAgentTool.CANCEL_TOOL_NAME.equals(toolName)) {
            // 委派、查询、取消均回填同一个批次面板，禁止落入普通 ToolCallsGroup。
            Map<String, Object> update = parseSubAgentToolResult(truncated);
            update.put("type", "subagent_batch_update");
            update.put("contentOffset", contentOffset);
            if (ctx != null && ctx.getSubAgentDelegationIndex() != null) {
                update.put("delegationIndex", ctx.getSubAgentDelegationIndex());
            }
            toolEventsList.add(update);
            try {
                String updateJson = objectMapper.writeValueAsString(update);
                if (ctx != null && ctx.getRealtimeStatusEmitter() != null) ctx.emitRealtimeStatus(updateJson);
                else if (statusFluxes != null) statusFluxes.add(Flux.just(STATUS_PREFIX + updateJson));
            } catch (Exception ignored) {
                // 前端展示事件失败不影响工具结果回填。
            }
            if (ctx != null) {
                ctx.setSubAgentContentOffset(null);
            }
            return;
        }
        String dn = getToolDisplayName(ctx, toolName);
        String icon = getToolIcon(ctx, toolName);
        Map<String, Object> resultEvt = new java.util.LinkedHashMap<>();
        resultEvt.put("type", "tool_result");
        resultEvt.put("toolName", toolName);
        if (dn != null) resultEvt.put("displayName", dn);
        if (icon != null) resultEvt.put("icon", icon);
        resultEvt.put("result", truncated);
        resultEvt.put("contentOffset", contentOffset);
        if (toolCallId > 0) {
            resultEvt.put("toolCallId", String.valueOf(toolCallId));
        }
        toolEventsList.add(resultEvt);
        String resultJson = toolEventGenerator.toolResultEvent(toolName, dn, icon, truncated, contentOffset, toolCallId);
        if (ctx != null && ctx.getRealtimeStatusEmitter() != null) {
            ctx.emitRealtimeStatus(resultJson);
        } else if (statusFluxes != null) {
            statusFluxes.add(Flux.just(STATUS_PREFIX + resultJson));
        }
        // write_todos 落库后额外推流 todos_updated：前端状态栏据此实时刷新，无需等 5s 轮询
        if ("write_todos".equals(toolName)) {
            emitTodosUpdated(ctx, statusFluxes, truncated, contentOffset);
        }
    }

    /**
     * 解析 write_todos 工具结果，向 SSE 推送 todos_updated 事件。
     * <p>不进 toolEventsList（避免在消息气泡里二次展示）；仅作为运行时事件给状态栏消费。</p>
     */
    private void emitTodosUpdated(ChatContext ctx, List<Flux<String>> statusFluxes,
                                   String toolResult, int contentOffset) {
        try {
            JsonNode resultNode = objectMapper.readTree(toolResult);
            if (!resultNode.path("success").asBoolean(false)) {
                return;
            }
            JsonNode todosNode = resultNode.path("todos");
            if (!todosNode.isArray()) {
                return;
            }
            Map<String, Object> todoEvt = new LinkedHashMap<>();
            todoEvt.put("type", "todos_updated");
            todoEvt.put("todos", objectMapper.convertValue(todosNode, List.class));
            todoEvt.put("contentOffset", contentOffset);
            String json = objectMapper.writeValueAsString(todoEvt);
            if (ctx != null && ctx.getRealtimeStatusEmitter() != null) {
                ctx.emitRealtimeStatus(json);
            } else if (statusFluxes != null) {
                statusFluxes.add(Flux.just(STATUS_PREFIX + json));
            }
        } catch (Exception ignored) {
            // todos_updated 推流失败不影响主流程。
        }
    }

    private String getToolDisplayName(ChatContext ctx, String toolName) {
        if (ctx == null || ctx.getToolDisplayNameMap() == null) return null;
        return ctx.getToolDisplayNameMap().get(toolName);
    }

    private String getToolIcon(ChatContext ctx, String toolName) {
        if (ctx == null || ctx.getToolIconMap() == null) return null;
        return ctx.getToolIconMap().get(toolName);
    }

    /**
     * 将 RAG 检索单行结果映射为 RagReferenceVO（QA_PAIR vs CHUNK 分支）
     */
    private RagReferenceVO mapToRagReference(Map<String, Object> row) {
        RagReferenceVO vo = new RagReferenceVO();
        String resultType = (String) row.get("result_type");
        if (RagResultType.QA_PAIR.equals(resultType)) {
            vo.setSourceType(RagResultType.QA_PAIR);
            vo.setDocumentName("问答对");
            vo.setQaPairId(parseLongObj(row.get("id")));
            String q = (String) row.get("question");
            String a = (String) row.get("answer");
            vo.setContentPreview("问题：" + q + "\n答案：" + a);
        } else {
            vo.setSourceType(RagResultType.CHUNK);
            vo.setDocumentName((String) row.get("document_name"));
            String content = (String) row.get("content");
            vo.setContentPreview(content != null && content.length() > 200
                    ? content.substring(0, 200) + "..." : content);
        }
        vo.setScore(row.get("score") != null ? ((Number) row.get("score")).doubleValue() : null);
        vo.setKnowledgeId(parseLongObj(row.get("knowledge_id")));
        vo.setDocumentId(parseLongObj(row.get("document_id")));
        vo.setChunkId(parseLongObj(row.get("chunk_id")));
        return vo;
    }

    /**
     * 追加并推送单条 SubAgent 流式中间事件
     */
    private void appendSubAgentStreamEvent(ChatContext ctx, List<Map<String, Object>> toolEventsList,
                                           List<Flux<String>> statusFluxes,
                                           ChatContext.SubAgentEvent se, int contentOffset) {
        Integer delegationIndex = ctx != null ? ctx.getSubAgentDelegationIndex() : null;
        String json;
        Map<String, Object> evt = new HashMap<>();
        switch (se.type()) {
            case "token" -> {
                evt.put("type", "subagent_token");
                evt.put("subagentName", se.subagentName());
                evt.put("content", se.content());
                evt.put("contentOffset", contentOffset);
                if (delegationIndex != null) evt.put("delegationIndex", delegationIndex);
                json = toolEventGenerator.enrichSubagentJson(
                        toolEventGenerator.subagentTokenEvent(se.subagentName(), se.content(), contentOffset),
                        delegationIndex);
            }
            case "tool_call" -> {
                String toolName = se.content();
                String subIcon = resolveSubAgentIcon(se.subagentName());
                evt.put("type", "subagent_tool_call");
                evt.put("subagentName", se.subagentName());
                evt.put("displayName", resolveSubAgentDisplayName(se.subagentName()));
                if (subIcon != null) evt.put("icon", subIcon);
                evt.put("toolName", toolName);
                evt.put("args", "{}");
                evt.put("contentOffset", contentOffset);
                if (delegationIndex != null) evt.put("delegationIndex", delegationIndex);
                json = toolEventGenerator.enrichSubagentJson(
                        toolEventGenerator.subagentToolCallEvent(
                                se.subagentName(), resolveSubAgentDisplayName(se.subagentName()),
                                toolName, toolName, "{}", contentOffset),
                        delegationIndex);
            }
            case "tool_result" -> {
                String subIcon = resolveSubAgentIcon(se.subagentName());
                evt.put("type", "subagent_tool_result");
                evt.put("subagentName", se.subagentName());
                evt.put("displayName", resolveSubAgentDisplayName(se.subagentName()));
                if (subIcon != null) evt.put("icon", subIcon);
                evt.put("toolName", "");
                evt.put("result", se.content());
                evt.put("contentOffset", contentOffset);
                if (delegationIndex != null) evt.put("delegationIndex", delegationIndex);
                json = toolEventGenerator.enrichSubagentJson(
                        toolEventGenerator.subagentToolResultEvent(
                                se.subagentName(), resolveSubAgentDisplayName(se.subagentName()),
                                "", "", se.content(), contentOffset),
                        delegationIndex);
            }
            default -> {
                return;
            }
        }
        toolEventsList.add(evt);
        if (ctx != null && ctx.getRealtimeStatusEmitter() != null) {
            ctx.emitRealtimeStatus(json);
        } else if (statusFluxes != null) {
            statusFluxes.add(Flux.just(STATUS_PREFIX + json));
        }
    }

    private String resolveSubAgentDisplayName(String subagentName) {
        if (subagentName == null || subagentName.isBlank()) {
            return subagentName;
        }
        com.lengbot.entity.SubAgent subAgent = subAgentService.getByName(subagentName);
        if (subAgent != null && subAgent.getDisplayName() != null && !subAgent.getDisplayName().isBlank()) {
            return subAgent.getDisplayName();
        }
        return subagentName;
    }

    private String resolveSubAgentIcon(String subagentName) {
        if (subagentName == null || subagentName.isBlank()) {
            return null;
        }
        com.lengbot.entity.SubAgent subAgent = subAgentService.getByName(subagentName);
        if (subAgent != null && subAgent.getIcon() != null && !subAgent.getIcon().isBlank()) {
            return subAgent.getIcon();
        }
        return null;
    }

    private Map<String, String> parseSubagentArgs(String args) {
        Map<String, String> out = new HashMap<>();
        out.put("subagentName", "");
        out.put("displayName", "");
        out.put("task", "");
        if (args == null || args.isBlank()) {
            return out;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(args, Map.class);
            Object nameObj = map.get("subagent_name");
            if (nameObj == null) {
                nameObj = map.get("subagentName");
            }
            String name = nameObj != null ? nameObj.toString() : "";
            out.put("subagentName", name);
            out.put("displayName", name);
            Object taskObj = map.get("task");
            if (taskObj != null) {
                out.put("task", taskObj.toString());
            }
        } catch (Exception e) {
            log.warn("[Chat] 解析 SubAgent 参数失败: {}", e.getMessage());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSubAgentToolResult(String result) {
        try {
            return new LinkedHashMap<>(objectMapper.readValue(result, Map.class));
        } catch (Exception ignored) {
            return new LinkedHashMap<>(Map.of("status", "failed", "error", result));
        }
    }

    /**
     * 为 SubAgent 工具调用补充 batch/task 元数据，供可观测调用树消费。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildToolTraceAttributes(String toolName, String args, String result) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("toolName", toolName);
        attributes.put("args", args);
        attributes.put("resultLength", result != null ? result.length() : 0);
        if (!isSubAgentTool(toolName) || result == null || result.isBlank()) {
            return attributes;
        }
        try {
            Map<String, Object> output = objectMapper.readValue(result, Map.class);
            Object batchId = output.get("batch_id");
            if (batchId != null) attributes.put("batchId", batchId.toString());
            Object taskId = output.get("task_id");
            if (taskId != null) attributes.put("taskId", taskId.toString());
            if (output.get("mode") != null) attributes.put("subagentMode", output.get("mode"));
            if (output.get("status") != null) attributes.put("subagentStatus", output.get("status"));
            if (output.get("results") instanceof List<?> results) {
                attributes.put("subagentTaskCount", results.size());
                attributes.put("subagentTaskIds", results.stream()
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .map(item -> item.get("task_id"))
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .toList());
            }
        } catch (Exception ignored) {
            // 工具结果非 JSON 时维持通用工具 span。
        }
        return attributes;
    }

    /**
     * 在通用 tool_execute span 下补充 SubAgent 批次和任务子 span，形成可观测调用树。
     */
    @SuppressWarnings("unchecked")
    private void appendSubAgentTraceSpans(List<LlmTraceSpanDTO> spans, String toolSpanId,
                                          String toolName, String result, long startTime) {
        if (!isSubAgentTool(toolName) || result == null || result.isBlank()) {
            return;
        }
        try {
            Map<String, Object> output = objectMapper.readValue(result, Map.class);
            String batchId = output.get("batch_id") != null ? output.get("batch_id").toString() : null;
            if (batchId == null || batchId.isBlank()) {
                return;
            }
            String batchSpanId = toolSpanId + ":subagent_batch";
            Object status = output.get("status");
            Map<String, Object> batchAttributes = new LinkedHashMap<>();
            batchAttributes.put("batchId", batchId);
            batchAttributes.put("mode", output.get("mode"));
            batchAttributes.put("aggregation", output.get("aggregation"));
            synchronized (spans) {
                spans.add(LlmTraceSpanDTO.of(batchSpanId, toolSpanId, "subagent_batch", startTime, 0L,
                        "failed".equals(status) ? "ERROR" : "OK", batchAttributes));
                if (output.get("results") instanceof List<?> results) {
                    int index = 0;
                    for (Object raw : results) {
                        if (!(raw instanceof Map<?, ?> task)) continue;
                        String taskId = task.get("task_id") != null ? task.get("task_id").toString() : String.valueOf(index);
                        Map<String, Object> taskAttributes = new LinkedHashMap<>();
                        taskAttributes.put("batchId", batchId);
                        taskAttributes.put("taskId", taskId);
                        taskAttributes.put("subagentName", task.get("subagent_name"));
                        taskAttributes.put("status", task.get("status"));
                        taskAttributes.put("replyPreview", task.get("reply"));
                        taskAttributes.put("error", task.get("error"));
                        spans.add(LlmTraceSpanDTO.of(batchSpanId + ":task:" + index, batchSpanId,
                                "subagent_task", startTime, 0L,
                                "failed".equals(task.get("status")) ? "ERROR" : "OK", taskAttributes));
                        index++;
                    }
                }
            }
        } catch (Exception ignored) {
            // Trace 增强失败不能影响对话工具链。
        }
    }

    private boolean isSubAgentTool(String toolName) {
        return DelegateSubAgentTool.TOOL_NAME.equals(toolName)
                || DelegateSubAgentTool.RESULT_TOOL_NAME.equals(toolName)
                || DelegateSubAgentTool.CANCEL_TOOL_NAME.equals(toolName);
    }

    @SuppressWarnings("unchecked")
    private List<String> parseSubagentNames(String args) {
        List<String> names = new ArrayList<>();
        if (args == null || args.isBlank()) {
            return names;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(args, Map.class);
            Object tasksObj = map.get("tasks");
            if (tasksObj instanceof List<?> tasks) {
                for (Object item : tasks) {
                    if (item instanceof Map<?, ?> raw) {
                        Object nameObj = ((Map<String, Object>) raw).get("subagent_name");
                        if (nameObj == null) {
                            nameObj = ((Map<String, Object>) raw).get("subagentName");
                        }
                        if (nameObj != null && !nameObj.toString().isBlank()) {
                            names.add(nameObj.toString());
                        }
                    }
                }
            }
            if (names.isEmpty()) {
                Map<String, String> parsed = parseSubagentArgs(args);
                String subName = parsed.get("subagentName");
                if (subName != null && !subName.isBlank()) {
                    names.add(subName);
                }
            }
        } catch (Exception e) {
            log.warn("[Chat] 解析 SubAgent 名称失败: {}", e.getMessage());
        }
        return names;
    }

    /**
     * 分类异常信息为用户友好的错误提示
     */
    private String classifyErrorMessage(Throwable e) {
        return com.lengbot.util.ModelErrorClassifier.classifyMessage(e);
    }

    /**
     * 分类异常为错误码
     */
    private String classifyErrorCode(Throwable e) {
        return com.lengbot.util.ModelErrorClassifier.classifyCode(e);
    }

    private void accumulateStreamUsage(ChatResponse response, int[] inputTokenHolder, int[] outputTokenHolder) {
        if (response == null) {
            return;
        }
        ChatUsage usage = response.getUsage();
        if (usage == null) {
            return;
        }
        inputTokenHolder[0] += usage.getInputTokens();
        outputTokenHolder[0] += usage.getOutputTokens();
    }

    /** 安全地将 Object 转为 Long（兼容 Number 和 String 类型） */
    private static Long parseLongObj(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException e) { return null; }
    }

    /**
     * 判断异常是否为工具参数 JSON 解析失败（多因模型输出被 maxTokens 截断导致 JSON 未闭合）。
     * 遍历 cause 链，命中 Jackson 的 JSON 解析异常即认定。
     *
     * @param e 工具执行捕获到的异常
     * @return true 表示为参数解析失败，应返回可读提示而非底层报错
     */
    private static boolean isToolArgsParseError(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
