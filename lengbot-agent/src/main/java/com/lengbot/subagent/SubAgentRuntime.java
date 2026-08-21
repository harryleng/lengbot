package com.lengbot.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.constant.ToolResultPrefixes;
import com.lengbot.entity.SubAgent;
import com.lengbot.entity.SubAgentRun;
import com.lengbot.entity.Tool;
import com.lengbot.mapper.SubAgentRunMapper;
import com.lengbot.model.ModelFactory;
import com.lengbot.model.DashScopeModelSupport;
import com.lengbot.model.ProviderResolver;
import com.lengbot.entity.ModelProvider;
import com.lengbot.enums.ModelProviderType;
import com.lengbot.service.ModelProviderService;
import com.lengbot.service.ToolService;
import com.lengbot.service.chat.ChatContext;
import com.lengbot.service.chat.ToolEventGenerator;
import com.lengbot.agent.harness.HarnessAgentFactory;
import com.lengbot.subagent.spi.SubAgentDefinition;
import com.lengbot.subagent.spi.SubAgentExecutor;
import com.lengbot.subagent.service.SubAgentTaskEventService;
import com.lengbot.util.ChatMessageContextUtil;
import com.lengbot.util.TextNormalizeUtil;
import com.lengbot.util.ToolArgsSanitizer;
import com.lengbot.util.Msgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SubAgent 执行器（流式工具循环）
 * <p>对标 Yuxi 的 task 工具内部 invoke：构造独立的 system_prompt + 子任务，
 * 解析 SubAgent.tools（按 name 查表）形成自己的工具集，
 * 走一轮流式工具调用循环，最终返回 assistant 文本给主 Agent。</p>
 *
 * @author lw
 * @since 2026-05-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubAgentRuntime implements SubAgentExecutor {

    private final ModelProviderService modelProviderService;
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_READ_TIMEOUT_SECONDS = 60;
    /** 流式输出期间两个 chunk 之间的最大间隔（秒），超过则视为"响应停滞" */
    private static final int DEFAULT_TOKEN_INTERVAL_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MODEL_RETRY_TIMES = 1;
    private static final int MAX_LOOP_DEPTH = 6;

    private final ModelFactory modelFactory;
    private final ToolService toolService;
    private final ProviderResolver providerResolver;
    private final ObjectMapper objectMapper;
    private final SubAgentRunMapper subAgentRunMapper;
    private final SubAgentThreadManager threadManager;
    private final ToolEventGenerator toolEventGenerator;
    private final SubAgentTaskEventService taskEventService;
    private final SubAgentPermissionPolicy permissionPolicy;
    private final ToolArgsSanitizer toolArgsSanitizer;
    private final HarnessAgentFactory harnessAgentFactory;

    /** 子 agent 是否走 HarnessAgent 内核（Phase 2）；默认 legacy 保留手搓 model.stream 循环。 */
    @org.springframework.beans.factory.annotation.Value("${lengbot.chat.engine:legacy}")
    private String chatEngineMode;

    /**
     * 子代理执行结果
     *
     * @param reply     最终回复文本
     * @param threadId  子代理线程 ID
     * @param continued 是否为续跑（true=加载了历史消息）
     */
    public record SubAgentResult(String reply, String threadId, boolean continued) {}

    @Override
    public ExecutionResult execute(SubAgentDefinition definition, String task, String taskId,
                                   String threadId, String parentThreadId, ChatContext chatContext) {
        SubAgentResult result = run(definition.source(), task, taskId, threadId, parentThreadId, chatContext);
        return new ExecutionResult(result.reply(), result.threadId(), result.continued());
    }

    /**
     * 同步执行一个 SubAgent，返回最终回答文本。
     * <p>模型解析：SubAgent 未配置独立 Provider 时，继承主 Agent 的 providerId + configMap（含版本快照中的 modelId/参数）。</p>
     *
     * @param subAgent         要委派的子智能体
     * @param taskDescription  主 Agent 给的任务描述
     * @param requestId        请求 ID（透传到工具上下文，用于幂等检查）
     * @param threadId         子代理线程 ID（null 表示新建，非 null 表示续跑）
     * @param parentThreadId   父 Agent 线程 ID（用于生成确定性 threadId）
     * @param chatContext      对话上下文（继承主 Agent 模型配置 + 推送流式事件，可为 null）
     */
    public SubAgentResult run(SubAgent subAgent, String taskDescription,
                              String requestId, String threadId, String parentThreadId,
                              ChatContext chatContext) {
        if (subAgent == null) {
            return new SubAgentResult("SubAgent 不存在", null, false);
        }

        // 1. 幂等性检查：同一 requestId 已完成则直接返回；未完成则复用已有任务记录
        SubAgentRun run = null;
        if (requestId != null && !requestId.isBlank()) {
            SubAgentRun existing = subAgentRunMapper.selectByRequestId(requestId);
            if (existing != null && isTerminal(existing.getStatus())) {
                log.info("[SubAgent] 幂等命中: requestId=[{}], status=[{}]", requestId, existing.getStatus());
                return new SubAgentResult(
                        existing.getReply() != null ? existing.getReply() : "",
                        existing.getThreadId(),
                        true);
            }
            if (existing != null) {
                if (isCancelRequested(existing)) {
                    markCancelled(existing, "SubAgent task cancelled before start");
                    return new SubAgentResult("SubAgent 任务已取消", existing.getThreadId(), false);
                }
                run = existing;
                if (threadId == null || threadId.isBlank()) {
                    threadId = existing.getThreadId();
                }
            }
        }

        // 2. 确定 threadId
        boolean continued = false;
        if (threadId == null || threadId.isBlank()) {
            threadId = parentThreadId != null
                    ? SubAgentThreadManager.makeChildThreadId(parentThreadId, subAgent.getName(), requestId)
                    : "subagent_" + System.currentTimeMillis();
        }

        long start = System.currentTimeMillis();
        int connectTimeoutSeconds = resolveConnectTimeoutSeconds(subAgent);
        int readTimeoutSeconds = resolveReadTimeoutSeconds(subAgent, chatContext);
        int modelRetryTimes = resolveModelRetryTimes(subAgent);
        log.info("[SubAgent] 委派开始: name={}, threadId={}, taskLen={}, connect={}s, tokenInterval={}s, retry={}",
                subAgent.getName(), threadId, taskDescription != null ? taskDescription.length() : 0,
                connectTimeoutSeconds, readTimeoutSeconds, modelRetryTimes);

        // 3. 创建或复用运行记录。后台任务会先写入 pending 记录，运行时只更新状态。
        if (run == null) {
            run = new SubAgentRun();
        }
        run.setThreadId(threadId);
        run.setParentThreadId(parentThreadId != null ? parentThreadId : "");
        run.setSubagentName(subAgent.getName());
        run.setTask(taskDescription);
        run.setStatus("running");
        run.setRequestId(requestId != null ? requestId : threadId);
        run.setStartTime(LocalDateTime.now());
        run.setToolCallCount(0);
        if (run.getId() == null) {
            run.setCancelRequested(0);
            subAgentRunMapper.insert(run);
        } else {
            subAgentRunMapper.updateById(run);
        }

        try {
            // 4. 解析子 Agent 的工具集合（按 ID 查 tool 表）
            List<String> toolIdStrings = parseToolIds(subAgent.getToolIds());
            List<Long> toolIds = toolIdStrings.stream().map(Long::parseLong).toList();
            List<Tool> boundTools = toolIds.isEmpty() ? List.of() : toolService.listByIds(toolIds);
            List<Long> executableToolIds = permissionPolicy.filterExecutableToolIds(subAgent, boundTools);
            List<ToolBase> toolCallbacks = executableToolIds.isEmpty()
                    ? List.of()
                    : toolService.resolveToolCallbacksByIds(executableToolIds);
            Map<String, ToolBase> toolMap = new HashMap<>();
            Map<String, String> toolDisplayNameMap = new HashMap<>();
            for (ToolBase cb : toolCallbacks) {
                toolMap.put(cb.getName(), cb);
            }
            if (!executableToolIds.isEmpty()) {
                for (Tool tool : boundTools) {
                    if (!executableToolIds.contains(tool.getId())) {
                        continue;
                    }
                    if (tool != null && tool.getName() != null) {
                        toolDisplayNameMap.put(tool.getName(),
                                tool.getDisplayName() != null && !tool.getDisplayName().isBlank()
                                        ? tool.getDisplayName() : tool.getName());
                    }
                }
            }

            // 5. 准备模型：独立配置优先，否则继承主 Agent（含版本快照 configMap）
            ResolvedModel resolved = resolveModel(subAgent, chatContext);
            Model model = modelFactory.getModel(resolved.providerId());
            log.info("[SubAgent] 模型: name={}, providerId={}, modelId={}, inherit={}",
                    subAgent.getName(), resolved.providerId(),
                    resolved.configMap().get("modelId"),
                    subAgent.getModelId() == null);

            // 6. 构造消息：续跑加载历史，否则新建
            List<Msg> messages;
            if (threadManager.threadExists(threadId)) {
                messages = new ArrayList<>(threadManager.loadMessages(threadId));
                if (!messages.isEmpty() && messages.get(0).getRole() == MsgRole.SYSTEM) {
                    messages.set(0, Msgs.system(subAgent.getSystemPrompt() != null ? subAgent.getSystemPrompt() : ""));
                }
                messages.add(Msgs.user(taskDescription != null ? taskDescription : ""));
                continued = true;
            } else {
                messages = new ArrayList<>();
                messages.add(Msgs.system(subAgent.getSystemPrompt() != null ? subAgent.getSystemPrompt() : ""));
                messages.add(Msgs.user(taskDescription != null ? taskDescription : ""));
            }

            // 7. 构造 ChatOptions（继承主 Agent 的 modelId/temperature 等 + 注入子工具集）
            GenerateOptions options = buildSubAgentChatOptions(
                    resolved.providerId(), resolved.configMap(), toolCallbacks, subAgent, requestId);

            // 8. 流式工具循环：直至模型返回不含 tool_call 的纯文本，或达到深度上限
            // 超时语义：首字超时（connectTimeoutSeconds）+ token 间隔超时（resolveTokenIntervalTimeoutSeconds），
            // 流式输出期间不做总时长判定——长输出不会再被误判为"响应超时"
            String reply = "";
            // 共享 replyBuilder 引用提到循环外：循环达到 MAX_LOOP_DEPTH 退出时也能取最后一轮的累积文本，
            // 避免回复为空导致"未返回有效内容"误报（模型最后一轮既输出文本又调工具的场景）
            StringBuilder replyBuilder = new StringBuilder();
            // 最后一轮 AssistantMessage 引用：兜底取 getText()，进一步降低空回复概率
            Msg lastAssistant = null;
            int toolCallCount = 0;
            if ("harness".equalsIgnoreCase(chatEngineMode)) {
                // Phase 2：harness 内核用 HarnessAgent.streamEvents 替代手搓 model.stream 工具循环
                reply = runViaHarness(model, subAgent, chatContext, messages, toolCallbacks,
                        toolDisplayNameMap, replyBuilder, modelRetryTimes,
                        connectTimeoutSeconds, readTimeoutSeconds, requestId, run);
            } else {
            for (int depth = 0; depth < MAX_LOOP_DEPTH; depth++) {
                if (chatContext != null && chatContext.isAborted()) {
                    markCancelled(run, "SubAgent execution cancelled by client");
                    return new SubAgentResult("", threadId, continued);
                }
                if (isCancelRequested(run)) {
                    markCancelled(run, "SubAgent task cancelled");
                    emitSubAgentError(chatContext, subAgent, "SubAgent 任务已取消", "CANCELLED");
                    return new SubAgentResult("", threadId, continued);
                }
                replyBuilder.setLength(0);
                Msg assistant;
                try {
                    prepareMessagesForLlm(messages);
                    assistant = streamLlmWithRetry(
                            model, options, new ArrayList<>(messages),
                            subAgent, chatContext, modelRetryTimes, replyBuilder, depth,
                            connectTimeoutSeconds, readTimeoutSeconds);
                } catch (Exception e) {
                    String errorMsg = classifyErrorMessage(e);
                    log.error("[SubAgent] 模型调用失败: name={}, depth={}, error={}",
                            subAgent.getName(), depth, e.getMessage(), e);
                    emitSubAgentError(chatContext, subAgent, errorMsg, classifyErrorCode(e));
                    markFailed(run, errorMsg, start);
                    return new SubAgentResult(errorMsg, threadId, false);
                }

                if (assistant == null) {
                    break;
                }
                lastAssistant = assistant;
                if (!assistant.hasContentBlocks(ToolUseBlock.class)) {
                    reply = replyBuilder.length() > 0 ? replyBuilder.toString()
                            : assistant.getTextContent();
                    break;
                }
                // 工具调用循环过程中也可能已累积部分正文（部分模型在调工具前先输出文本），
                // 这里在继续下一轮前先记一份，作为循环达到上限时的兜底回复
                if (reply.isBlank() && replyBuilder.length() > 0) {
                    reply = replyBuilder.toString();
                }

                // 8.2 模型要求调用工具：逐个执行后回填
                messages.add(assistant);
                List<ToolResultBlock> toolResponses = new ArrayList<>();
                for (ToolUseBlock tc : assistant.getContentBlocks(ToolUseBlock.class)) {
                    emitSubAgentToolCall(chatContext, subAgent, tc, toolDisplayNameMap);

                    String result;
                    ToolBase cb = toolMap.get(tc.getName());
                    if (cb == null) {
                        result = ToolResultPrefixes.failureJson(ToolResultPrefixes.NOT_FOUND + ": " + tc.getName());
                    } else {
                        try {
                            String rawArgs = serializeToolInput(tc.getInput());
                            String callArgs = rawArgs;
                            String repaired = toolArgsSanitizer.tryRepairTruncatedWriteArgs(tc.getName(), rawArgs);
                            if (repaired != null) {
                                callArgs = repaired.replaceAll(",\\s*\"_repairedFromTruncation\"\\s*:\\s*true", "")
                                        .replaceAll("\"_repairedFromTruncation\"\\s*:\\s*true\\s*,?", "");
                            } else {
                                callArgs = toolArgsSanitizer.forChatCall(rawArgs);
                            }
                            result = executeTool(cb, callArgs, chatContext, requestId);
                        } catch (Exception e) {
                            log.warn("[SubAgent] 工具执行异常: subAgent={}, tool={}, error={}",
                                    subAgent.getName(), tc.getName(), e.getMessage());
                            result = ToolResultPrefixes.failureJson(ToolResultPrefixes.FAILURE + ": " + e.getMessage());
                        }
                    }

                    emitSubAgentToolResult(chatContext, subAgent, tc, result, toolDisplayNameMap);

                    result = ChatMessageContextUtil.capToolResult(result, ChatMessageContextUtil.MAX_SINGLE_TOOL_RESULT_CHARS);
                    toolResponses.add(ToolResultBlock.builder().id(tc.getId()).name(tc.getName())
                            .output(TextBlock.builder().text(result).build()).build());
                    toolCallCount++;
                }
                messages.add(Msg.builderForRole(MsgRole.TOOL).content(new ArrayList<ContentBlock>(toolResponses)).build());
            }
            } // end else: legacy 手搓 model.stream 工具循环

            // 9. 保存消息历史（续跑用）
            threadManager.saveMessages(threadId, messages);

            // 10. 三层 fallback 取最终回复：
            //   ① 正常退出时 reply（!hasToolCalls 时赋值）
            //   ② reply 兜底：循环中累积的 replyBuilder 文本（模型边输出边调工具）
            //   ③ lastAssistant.getText()：纯流式无工具调用但 replyBuilder 漏抓的情况
            //   ④ 仍为空时给出可读提示，明确语义是"工具用尽未总结"而非"无产出"
            if (reply.isBlank() && replyBuilder.length() > 0) {
                reply = replyBuilder.toString();
            }
            if (reply.isBlank() && lastAssistant != null) {
                reply = lastAssistant.getTextContent();
            }
            String finalReply = reply.isBlank()
                    ? "（SubAgent " + subAgent.getName() + " 已完成 " + toolCallCount
                            + " 次工具调用但未输出最终文本结果，请基于工具结果继续追问或重试）"
                    : TextNormalizeUtil.sanitizeForAiMessage(reply, 0);
            long cost = System.currentTimeMillis() - start;
            run.setReply(finalReply);
            run.setStatus("completed");
            run.setToolCallCount(toolCallCount);
            run.setEndTime(LocalDateTime.now());
            subAgentRunMapper.updateById(run);
            log.info("[SubAgent] 委派完成: name={}, 耗时={}ms, replyLen={}", subAgent.getName(), cost, reply.length());
            return new SubAgentResult(finalReply, threadId, continued);

        } catch (Exception e) {
            String errorMsg = "SubAgent 执行失败: " + e.getMessage();
            emitSubAgentError(chatContext, subAgent, errorMsg, "UNKNOWN");
            markFailed(run, errorMsg, start);
            return new SubAgentResult(errorMsg, threadId, false);
        }
    }

    /**
     * LLM 调用前规范化并裁剪消息，避免空 content 或工具结果撑爆 DashScope 输入上限
     */
    private void prepareMessagesForLlm(List<Msg> messages) {
        ChatMessageContextUtil.normalizeMessagesForLlm(messages);
        ChatMessageContextUtil.trimToolCallContext(
                messages,
                ChatMessageContextUtil.DASHSCOPE_SAFE_INPUT_CHARS,
                ChatMessageContextUtil.DEFAULT_TOOL_ROUNDS_TO_KEEP);
    }

    /** 解析后的模型配置：Provider ID + 模型参数字典 */
    private record ResolvedModel(Long providerId, Map<String, Object> configMap) {}

    /**
     * 模型解析：SubAgent 独立 Provider 优先；否则继承主 Agent 的 providerId + configMap（含版本快照）
     */
    private ResolvedModel resolveModel(SubAgent subAgent, ChatContext chatContext) {
        if (subAgent.getModelId() != null) {
            Map<String, Object> cfg = new HashMap<>();
            if (subAgent.getLlmModel() != null && !subAgent.getLlmModel().isBlank()) {
                cfg.put("modelId", subAgent.getLlmModel());
            }
            return new ResolvedModel(subAgent.getModelId(), cfg);
        }
        if (chatContext != null && chatContext.getProviderId() != null) {
            Map<String, Object> cfg = chatContext.getConfigMap() != null
                    ? new HashMap<>(chatContext.getConfigMap()) : new HashMap<>();
            return new ResolvedModel(chatContext.getProviderId(), cfg);
        }
        return new ResolvedModel(providerResolver.resolve(), Map.of());
    }

    /**
     * 构建 SubAgent ChatOptions：继承 modelId/temperature 等，并注入子工具集
     */
    private GenerateOptions buildSubAgentChatOptions(Long providerId,
                                                             Map<String, Object> configMap,
                                                             List<ToolBase> toolCallbacks,
                                                             SubAgent subAgent, String requestId) {
        String modelId = configMap != null && configMap.get("modelId") != null
                ? configMap.get("modelId").toString() : null;

        ModelProvider provider = providerId != null ? modelProviderService.getById(providerId) : null;
        if (provider != null && provider.getType() == ModelProviderType.DASHSCOPE
                && !DashScopeModelSupport.isCompatibleMode(provider.getBaseUrl())) {
            return DashScopeModelSupport.buildNativeChatOptions(modelId, configMap);
        }

        GenerateOptions.Builder builder = GenerateOptions.builder();
        if (modelId != null) {
            builder.modelName(modelId);
        }
        if (configMap != null) {
            if (configMap.containsKey("temperature")) {
                Object v = configMap.get("temperature");
                builder.temperature(v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString()));
            }
            if (configMap.containsKey("topP")) {
                Object v = configMap.get("topP");
                builder.topP(v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString()));
            }
            if (configMap.containsKey("maxTokens")) {
                Object v = configMap.get("maxTokens");
                builder.maxTokens(v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString()));
            }
        }
        GenerateOptions options = builder.build();
        if (provider != null) {
            options = modelFactory.adaptGenerateOptions(provider, configMap, options);
        }
        return options;
    }

    /**
     * 带重试的流式 LLM 调用（对齐主 Agent streamModelWithRetry 策略）
     */
    private Msg streamLlmWithRetry(Model model, GenerateOptions options, List<Msg> messages, SubAgent subAgent,
                                                 ChatContext chatContext, int retryTimes,
                                                 StringBuilder replyBuilder, int depth,
                                                 int connectTimeoutSeconds, int readTimeoutSeconds) throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt <= retryTimes; attempt++) {
            try {
                return streamLlmOnce(model, options, messages, subAgent, chatContext, replyBuilder,
                        connectTimeoutSeconds * 1000L, connectTimeoutSeconds, readTimeoutSeconds);
            } catch (Exception e) {
                lastError = e;
                if (attempt < retryTimes) {
                    int retryNo = attempt + 1;
                    long delayMs = (long) Math.pow(2, attempt) * 1000;
                    String reason = classifyFailureReason(e);
                    log.warn("[SubAgent] 模型调用失败，第{}次重试，等待{}ms: name={}, depth={}, reason={}, error={}",
                            retryNo, delayMs, subAgent.getName(), depth, reason, e.getMessage());
                    emitSubAgentErrorRetry(chatContext, subAgent,
                            buildRetryMessage(subAgent, reason, retryNo, retryTimes),
                            reasonToCode(reason), retryNo, retryTimes);
                    Thread.sleep(delayMs);
                }
            }
        }
        throw lastError != null ? lastError : new RuntimeException("SubAgent 模型调用失败");
    }

    /** 单次流式 LLM 调用：首字超时（connectTimeoutSeconds）+ token 间隔超时（tokenIntervalTimeoutSeconds） */
    private Msg streamLlmOnce(Model model, GenerateOptions options, List<Msg> messages, SubAgent subAgent,
                                          ChatContext chatContext, StringBuilder replyBuilder, long remainingMs,
                                          int connectTimeoutSeconds, int readTimeoutSeconds) {
        List<Msg> lastAssistant = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean firstReceived = new java.util.concurrent.atomic.AtomicBoolean(false);
        StringBuilder streamSnapshot = new StringBuilder();
        int tokenIntervalSec = resolveTokenIntervalTimeoutSeconds(subAgent);

        java.util.function.Consumer<ChatResponse> processChunk = response -> {
            firstReceived.set(true);
            List<ContentBlock> output = response.getContent();
            if (output != null) {
                Msg msg = Msg.builderForRole(MsgRole.ASSISTANT).content(output).build();
                if (lastAssistant.isEmpty()) {
                    lastAssistant.add(msg);
                } else {
                    lastAssistant.set(0, msg);
                }
                String text = msg.getTextContent();
                if (text != null && !text.isEmpty()) {
                    String delta = consumeStreamTextDelta(streamSnapshot, text);
                    if (!delta.isEmpty()) {
                        replyBuilder.append(delta);
                        pushTokenEvent(chatContext, subAgent, delta);
                    }
                }
            }
        };

        Flux<ChatResponse> flux = model.stream(messages, List.of(), options);
        if (chatContext != null) {
            flux = flux.takeUntilOther(Mono.delay(Duration.ofMillis(200))
                    .repeat()
                    .filter(tick -> chatContext.isAborted())
                    .next());
        }
        Flux<ChatResponse> cached = flux.cache();

        long connectWaitMs = Math.min(remainingMs, connectTimeoutSeconds * 1000L);
        try {
            cached.take(1)
                    .doOnNext(processChunk)
                    .blockFirst(Duration.ofMillis(Math.max(1, connectWaitMs)));
        } catch (Exception e) {
            if (!firstReceived.get()) {
                throw new RuntimeException(connectTimeoutMessage(connectTimeoutSeconds));
            }
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
        if (!firstReceived.get()) {
            throw new RuntimeException(connectTimeoutMessage(connectTimeoutSeconds));
        }

        // 流式阶段：用 Flux.timeout 监督两个 chunk 之间最大间隔，超过则视为"响应停滞"
        // 不再累加 streamingPausedMs 也不做总时长判定——流式输出多久都不算超时，只在停滞时超时
        try {
            cached.skip(1)
                    .doOnNext(processChunk)
                    .doOnComplete(() -> completed.set(true))
                    .blockLast(Duration.ofSeconds(tokenIntervalSec));
        } catch (Exception e) {
            // 超时异常（TimeoutException 或包异常）单独识别，给出"响应停滞"文案
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("timeout") || msg.contains("Timeout") || e instanceof java.util.concurrent.TimeoutException) {
                throw new RuntimeException(stalledTimeoutMessage(tokenIntervalSec));
            }
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }

        if (chatContext != null && chatContext.isAborted()) {
            throw new RuntimeException("SubAgent execution cancelled by client");
        }
        if (!completed.get() && lastAssistant.isEmpty()) {
            throw new RuntimeException(readTimeoutMessage(readTimeoutSeconds));
        }
        return lastAssistant.isEmpty() ? null : lastAssistant.get(0);
    }

    /** 取 token 间隔超时阈值：SubAgent 可通过 readTimeoutSeconds 配置覆盖（最小 10s），否则用默认值 */
    private int resolveTokenIntervalTimeoutSeconds(SubAgent subAgent) {
        if (subAgent != null && subAgent.getReadTimeoutSeconds() != null) {
            return Math.max(10, Math.min(300, subAgent.getReadTimeoutSeconds()));
        }
        return DEFAULT_TOKEN_INTERVAL_TIMEOUT_SECONDS;
    }

    private int resolveConnectTimeoutSeconds(SubAgent subAgent) {
        if (subAgent != null && subAgent.getConnectTimeoutSeconds() != null) {
            return Math.max(1, Math.min(60, subAgent.getConnectTimeoutSeconds()));
        }
        return DEFAULT_CONNECT_TIMEOUT_SECONDS;
    }

    private int resolveReadTimeoutSeconds(SubAgent subAgent, ChatContext chatContext) {
        int configured = DEFAULT_READ_TIMEOUT_SECONDS;
        if (subAgent != null && subAgent.getReadTimeoutSeconds() != null) {
            configured = Math.max(10, Math.min(300, subAgent.getReadTimeoutSeconds()));
        }
        return configured;
    }

    private int resolveModelRetryTimes(SubAgent subAgent) {
        if (subAgent == null || subAgent.getModelRetryTimes() == null) {
            return DEFAULT_MODEL_RETRY_TIMES;
        }
        return Math.max(0, Math.min(10, subAgent.getModelRetryTimes()));
    }

    private String connectTimeoutMessage(int connectTimeoutSeconds) {
        return "SubAgent 连接超时（" + connectTimeoutSeconds + "秒），请检查网络或模型服务";
    }

    private String readTimeoutMessage(int readTimeoutSeconds) {
        return "SubAgent 响应超时（" + readTimeoutSeconds + "秒），请稍后重试";
    }

    /** 流式期间两个 chunk 间隔超时的提示文案：明确"停滞"语义而非"总时长" */
    private String stalledTimeoutMessage(int stalledSeconds) {
        return "SubAgent 响应停滞（" + stalledSeconds + "秒无新内容），请稍后重试";
    }

    private String resolveSubAgentDisplayName(SubAgent subAgent) {
        if (subAgent == null) {
            return "";
        }
        return subAgent.getDisplayName() != null && !subAgent.getDisplayName().isBlank()
                ? subAgent.getDisplayName() : subAgent.getName();
    }

    private String classifyFailureReason(Throwable e) {
        if (e == null) {
            return "execution_error";
        }
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("连接超时") || msg.contains("connect timed out") || msg.contains("connection timed out")
                || msg.contains("connect timeout") || msg.contains("连接失败")) {
            return "connect_timeout";
        }
        if (msg.contains("响应超时") || msg.contains("timeout") || msg.contains("timed out")) {
            return "read_timeout";
        }
        return "execution_error";
    }

    private String reasonToCode(String reason) {
        return switch (reason) {
            case "connect_timeout" -> "CONNECT_TIMEOUT";
            case "read_timeout" -> "READ_TIMEOUT";
            default -> "LLM_ERROR";
        };
    }

    private String reasonLabel(String reason) {
        return switch (reason) {
            case "connect_timeout" -> "连接超时";
            case "read_timeout" -> "响应超时";
            default -> "执行失败";
        };
    }

    private String buildRetryMessage(SubAgent subAgent, String reason, int attempt, int maxRetries) {
        return resolveSubAgentDisplayName(subAgent) + "：" + reasonLabel(reason)
                + "，正在重试 " + attempt + "/" + maxRetries;
    }

    /**
     * 从流式 getText() 提取增量（兼容累积全文与纯增量两种 provider 行为）
     */
    private String consumeStreamTextDelta(StringBuilder snapshot, String currentText) {
        if (currentText == null || currentText.isEmpty()) {
            return "";
        }
        String consumed = snapshot.toString();
        if (!consumed.isEmpty()
                && currentText.startsWith(consumed)
                && currentText.length() > consumed.length()) {
            String delta = currentText.substring(consumed.length());
            snapshot.setLength(0);
            snapshot.append(currentText);
            return delta;
        }
        if (currentText.contentEquals(consumed)) {
            return "";
        }
        if (!consumed.isEmpty()
                && currentText.length() <= consumed.length()
                && consumed.startsWith(currentText)) {
            return "";
        }
        if (!consumed.isEmpty()
                && currentText.length() <= consumed.length()
                && consumed.endsWith(currentText)) {
            return "";
        }
        snapshot.append(currentText);
        return currentText;
    }

    private void emitSubAgentError(ChatContext chatContext, SubAgent subAgent, String message, String code) {
        if (chatContext == null) return;
        int offset = chatContext.getSubAgentContentOffset() != null ? chatContext.getSubAgentContentOffset() : 0;
        Integer delegationIndex = chatContext.getSubAgentDelegationIndex();
        String displayName = subAgent.getDisplayName() != null ? subAgent.getDisplayName() : subAgent.getName();
        String json = toolEventGenerator.enrichSubagentJson(
                toolEventGenerator.subagentErrorEvent(subAgent.getName(), displayName, message, code, offset),
                delegationIndex, chatContext.getSubAgentBatchId(), chatContext.getSubAgentTaskId(),
                chatContext.getSubAgentTaskIndex());
        Map<String, Object> evt = new HashMap<>();
        evt.put("type", "subagent_error");
        evt.put("subagentName", subAgent.getName());
        evt.put("displayName", displayName);
        evt.put("message", message);
        evt.put("code", code);
        evt.put("contentOffset", offset);
        if (delegationIndex != null) evt.put("delegationIndex", delegationIndex);
        emitSubAgentStreamEvent(chatContext, evt, json);
    }

    private void emitSubAgentErrorRetry(ChatContext chatContext, SubAgent subAgent, String message,
                                        String code, int attempt, int maxRetries) {
        if (chatContext == null) return;
        int offset = chatContext.getSubAgentContentOffset() != null ? chatContext.getSubAgentContentOffset() : 0;
        Integer delegationIndex = chatContext.getSubAgentDelegationIndex();
        String displayName = subAgent.getDisplayName() != null ? subAgent.getDisplayName() : subAgent.getName();
        String json = toolEventGenerator.enrichSubagentJson(
                toolEventGenerator.subagentErrorRetryEvent(
                        subAgent.getName(), displayName, message, code, attempt, maxRetries, offset),
                delegationIndex, chatContext.getSubAgentBatchId(), chatContext.getSubAgentTaskId(),
                chatContext.getSubAgentTaskIndex());
        Map<String, Object> evt = new HashMap<>();
        evt.put("type", "subagent_error_retry");
        evt.put("subagentName", subAgent.getName());
        evt.put("displayName", displayName);
        evt.put("message", message);
        evt.put("code", code);
        evt.put("attempt", attempt);
        evt.put("maxRetries", maxRetries);
        evt.put("contentOffset", offset);
        if (delegationIndex != null) evt.put("delegationIndex", delegationIndex);
        emitSubAgentStreamEvent(chatContext, evt, json);
    }

    private String classifyErrorMessage(Throwable e) {
        if (e == null) return "SubAgent 执行失败：未知错误";
        String msg = e.getMessage();
        if (msg == null) return "SubAgent 执行失败：" + e.getClass().getSimpleName();
        String reason = classifyFailureReason(e);
        if ("connect_timeout".equals(reason)) {
            return msg.contains("SubAgent") ? msg : connectTimeoutMessage(resolveConnectTimeoutSeconds(null));
        }
        if ("read_timeout".equals(reason)) {
            return msg.contains("SubAgent") ? msg : readTimeoutMessage(resolveReadTimeoutSeconds(null, null));
        }
        if (msg.contains("429") || msg.contains("rate") || msg.contains("Rate")) {
            return "SubAgent 请求被限流，请稍后重试";
        }
        if (msg.contains("401") || msg.contains("403")) {
            return "SubAgent 模型认证失败，请检查 API Key 配置";
        }
        if (msg.contains("input length") || (msg.contains("InvalidParameter") && msg.contains("202745"))) {
            return "SubAgent 上下文过长，请缩小任务范围或减少工具返回数据";
        }
        return "SubAgent 执行失败：" + (msg.length() > 200 ? msg.substring(0, 200) + "..." : msg);
    }

    private String classifyErrorCode(Throwable e) {
        if (e == null) return "UNKNOWN";
        String code = reasonToCode(classifyFailureReason(e));
        if (!"LLM_ERROR".equals(code)) {
            return code;
        }
        String msg = e.getMessage();
        if (msg == null) return "UNKNOWN";
        if (msg.contains("429") || msg.contains("rate") || msg.contains("Rate")) return "RATE_LIMITED";
        if (msg.contains("401") || msg.contains("403")) return "AUTH_ERROR";
        if (msg.contains("token") && (msg.contains("limit") || msg.contains("exceed"))) return "TOKEN_LIMIT";
        return "LLM_ERROR";
    }

    private void pushTokenEvent(ChatContext chatContext, SubAgent subAgent, String delta) {
        if (chatContext == null || delta == null) {
            return;
        }
        int offset = chatContext.getSubAgentContentOffset() != null ? chatContext.getSubAgentContentOffset() : 0;
        Integer delegationIndex = chatContext.getSubAgentDelegationIndex();
        String json = toolEventGenerator.enrichSubagentJson(
                toolEventGenerator.subagentTokenEvent(subAgent.getName(), delta, offset), delegationIndex,
                chatContext.getSubAgentBatchId(), chatContext.getSubAgentTaskId(), chatContext.getSubAgentTaskIndex());
        Map<String, Object> evt = new HashMap<>();
        evt.put("type", "subagent_token");
        evt.put("subagentName", subAgent.getName());
        evt.put("displayName", resolveSubAgentDisplayName(subAgent));
        evt.put("content", delta);
        evt.put("contentOffset", offset);
        if (delegationIndex != null) evt.put("delegationIndex", delegationIndex);
        emitSubAgentStreamEvent(chatContext, evt, json);
    }

    private void emitSubAgentToolCall(ChatContext chatContext, SubAgent subAgent, ToolUseBlock tc,
                                      Map<String, String> toolDisplayNameMap) {
        if (chatContext == null || tc == null) {
            return;
        }
        int offset = chatContext.getSubAgentContentOffset() != null ? chatContext.getSubAgentContentOffset() : 0;
        Integer delegationIndex = chatContext.getSubAgentDelegationIndex();
        String toolName = tc.getName() != null ? tc.getName() : "";
        String toolDisplayName = toolDisplayNameMap.getOrDefault(toolName, toolName);
        String args = serializeToolInput(tc.getInput());
        String subDisplayName = resolveSubAgentDisplayName(subAgent);
        String json = toolEventGenerator.enrichSubagentJson(
                toolEventGenerator.subagentToolCallEvent(
                        subAgent.getName(), subDisplayName, toolName, toolDisplayName, args, offset),
                delegationIndex, chatContext.getSubAgentBatchId(), chatContext.getSubAgentTaskId(),
                chatContext.getSubAgentTaskIndex());
        Map<String, Object> evt = new java.util.LinkedHashMap<>();
        evt.put("type", "subagent_tool_call");
        evt.put("subagentName", subAgent.getName());
        evt.put("displayName", subDisplayName);
        evt.put("toolName", toolName);
        evt.put("toolDisplayName", toolDisplayName);
        evt.put("args", args);
        evt.put("contentOffset", offset);
        if (delegationIndex != null) evt.put("delegationIndex", delegationIndex);
        emitSubAgentStreamEvent(chatContext, evt, json);
    }

    private void emitSubAgentToolResult(ChatContext chatContext, SubAgent subAgent, ToolUseBlock tc,
                                        String result, Map<String, String> toolDisplayNameMap) {
        if (chatContext == null || tc == null) {
            return;
        }
        int offset = chatContext.getSubAgentContentOffset() != null ? chatContext.getSubAgentContentOffset() : 0;
        Integer delegationIndex = chatContext.getSubAgentDelegationIndex();
        String toolName = tc.getName() != null ? tc.getName() : "";
        String toolDisplayName = toolDisplayNameMap.getOrDefault(toolName, toolName);
        String subDisplayName = resolveSubAgentDisplayName(subAgent);
        String json = toolEventGenerator.enrichSubagentJson(
                toolEventGenerator.subagentToolResultEvent(
                        subAgent.getName(), subDisplayName, toolName, toolDisplayName, result, offset),
                delegationIndex, chatContext.getSubAgentBatchId(), chatContext.getSubAgentTaskId(),
                chatContext.getSubAgentTaskIndex());
        Map<String, Object> evt = new java.util.LinkedHashMap<>();
        evt.put("type", "subagent_tool_result");
        evt.put("subagentName", subAgent.getName());
        evt.put("displayName", subDisplayName);
        evt.put("toolName", toolName);
        evt.put("toolDisplayName", toolDisplayName);
        evt.put("result", toolEventGenerator.truncateForSse(result));
        evt.put("contentOffset", offset);
        if (delegationIndex != null) evt.put("delegationIndex", delegationIndex);
        emitSubAgentStreamEvent(chatContext, evt, json);
    }

    private void emitSubAgentStreamEvent(ChatContext chatContext, Map<String, Object> evt, String json) {
        evt.put("schema_version", 1);
        if (chatContext.getRequestId() != null && !chatContext.getRequestId().isBlank()) {
            evt.put("parent_request_id", chatContext.getRequestId());
        }
        // SSE 已有任务关联字段；metadata 也必须保留，历史消息才能按任务归属工具步骤。
        if (chatContext.getSubAgentBatchId() != null) {
            evt.put("batch_id", chatContext.getSubAgentBatchId());
        }
        if (chatContext.getSubAgentTaskId() != null) {
            evt.put("task_id", chatContext.getSubAgentTaskId());
        }
        if (chatContext.getSubAgentTaskIndex() != null) {
            evt.put("task_index", chatContext.getSubAgentTaskIndex());
        }
        taskEventService.record(chatContext.getSubAgentTaskId(), chatContext.getSubAgentBatchId(),
                String.valueOf(evt.get("type")), evt);
        if (chatContext.getRealtimeStatusEmitter() != null) {
            if (chatContext.getToolEventsList() != null) {
                synchronized (chatContext.getToolEventsList()) {
                    chatContext.getToolEventsList().add(new java.util.LinkedHashMap<>(evt));
                }
            }
            // 实时 SSE 与持久化事件使用同一契约，避免刷新前后丢失请求归属或版本字段。
            try {
                chatContext.emitRealtimeStatus(objectMapper.writeValueAsString(evt));
            } catch (Exception ignored) {
                chatContext.emitRealtimeStatus(json);
            }
        } else {
            Object payload = evt.get("result") != null ? evt.get("result")
                    : (evt.get("content") != null ? evt.get("content") : evt.get("args"));
            chatContext.pushSubAgentEvent(new ChatContext.SubAgentEvent(
                    evt.get("type").toString().replace("subagent_", ""),
                    (String) evt.get("subagentName"),
                    payload != null ? payload.toString() : "",
                    (Integer) evt.getOrDefault("contentOffset", 0)));
        }
    }

    private void markFailed(SubAgentRun run, String errorMessage, long start) {
        run.setStatus("failed");
        run.setErrorMessage(errorMessage);
        run.setEndTime(LocalDateTime.now());
        subAgentRunMapper.updateById(run);
        log.error("[SubAgent] 委派失败: name={}, 耗时={}ms, error={}",
                run.getSubagentName(), System.currentTimeMillis() - start, errorMessage);
    }

    private void markCancelled(SubAgentRun run, String message) {
        run.setStatus("cancelled");
        run.setErrorMessage(message);
        run.setCancelRequested(1);
        run.setEndTime(LocalDateTime.now());
        subAgentRunMapper.updateById(run);
        log.info("[SubAgent] 委派取消: name={}, requestId={}", run.getSubagentName(), run.getRequestId());
    }

    private boolean isCancelRequested(SubAgentRun run) {
        if (run == null || run.getRequestId() == null) {
            return false;
        }
        SubAgentRun latest = subAgentRunMapper.selectByRequestId(run.getRequestId());
        return latest != null && (Integer.valueOf(1).equals(latest.getCancelRequested())
                || "cancelled".equals(latest.getStatus()));
    }

    private boolean isTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    /** 解析 SubAgent.toolIds JSON 数组 */
    private List<String> parseToolIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[SubAgent] 解析 toolIds JSON 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** AgentScope 工具入参为 Map<String,Object>，序列化为 JSON 字符串 */
    private String serializeToolInput(Map<String, Object> input) {
        if (input == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception ignored) {
            return String.valueOf(input);
        }
    }

    /** 构造子代理工具调用的 RuntimeContext（AgentScope 2.0.1 下需显式注入，否则上下文为空） */
    private RuntimeContext buildSubAgentRuntimeContext(ChatContext ctx, String reqId, String callArgsJson) {
        RuntimeContext.Builder b = RuntimeContext.builder();
        if (ctx != null) {
            if (ctx.getSessionId() != null) {
                b.sessionId(String.valueOf(ctx.getSessionId()));
            }
            if (ctx.getUserId() != null) {
                b.userId(String.valueOf(ctx.getUserId()));
            }
            b.put("sessionId", ctx.getSessionId() != null ? ctx.getSessionId().toString() : "default")
             .put("requestId", reqId)
             .put("userId", ctx.getUserId())
             .put("chatContext", ctx)
             .put("currentTodos", ctx.getCurrentTodosSnapshot())
             .put("toolInput", callArgsJson);
        } else {
            b.put("requestId", reqId).put("toolInput", callArgsJson);
        }
        return b.build();
    }

    /** 通过 AgentScope ToolBase 执行工具并提取纯文本结果 */
    private String executeTool(ToolBase cb, String callArgsJson, ChatContext chatContext, String requestId) {
        Map<String, Object> args = new HashMap<>();
        try {
            args.putAll(objectMapper.readValue(callArgsJson,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)));
        } catch (Exception e) {
            log.warn("[SubAgent] 工具参数非标准 JSON，按原文传递: {}", callArgsJson);
        }
        ToolCallParam param = ToolCallParam.builder()
                .input(args)
                .runtimeContext(buildSubAgentRuntimeContext(chatContext, requestId, callArgsJson))
                .build();
        ToolResultBlock block = cb.callAsync(param).block();
        if (block == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : block.getOutput()) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    /**
     * Phase 2 harness 内核：用 {@code HarnessAgent.streamEvents} 替代手搓 {@code model.stream} 工具循环。
     *
     * <p>事件经 {@link #pushTokenEvent}（subagent_token）+ 装饰器（subagent_tool_call/tool_result）实时推 SSE
     *（共享 eventSink，与 legacy 同链路）。stall-timeout 用 {@code .timeout(readTimeoutSeconds)}（per-element，
     * 覆盖首元素），用 gotFirst 分类 CONNECT/READ_TIMEOUT。retry/abort 复用现有 emitSubAgentErrorRetry/Error。</p>
     *
     * <p>stall-timeout 用 {@code .timeout(Mono.delay(connectSec), e -> Mono.delay(stallSec), Flux.error)}：
     * 首元素 connect 超时（connectTimeoutSeconds）+ 后续 stall 超时（readTimeoutSeconds），均触发 error -> retry/emitSubAgentError。</p>
     *
     * <p><b>已知回归</b>：续跑历史只存 [system,user,assistant-reply]，工具轮次不可得
     * （HarnessAgent 内部循环不暴露中间消息）；retry 粒度按整轮 turn（legacy 按单次 LLM 调用）。</p>
     */
    private String runViaHarness(Model model, SubAgent subAgent, ChatContext chatContext, List<Msg> messages,
                                 List<ToolBase> toolCallbacks, Map<String, String> toolDisplayNameMap,
                                 StringBuilder replyBuilder, int modelRetryTimes,
                                 int connectTimeoutSeconds, int readTimeoutSeconds,
                                 String requestId, SubAgentRun run) {
        // 拆 sysPrompt + conversation（HarnessAgent hook 不允许 inputMessages 含 SYSTEM）
        String sysPrompt = null;
        List<Msg> conversationMsgs = new ArrayList<>();
        for (Msg m : messages) {
            if (m != null && m.getRole() == MsgRole.SYSTEM) {
                String t = m.getTextContent();
                if (t != null && !t.isBlank()) {
                    sysPrompt = sysPrompt == null ? t : sysPrompt + "\n\n" + t;
                }
            } else if (m != null) {
                conversationMsgs.add(m);
            }
        }
        // 工具装饰器：发 subagent_tool_call/tool_result + 复用 executeTool
        List<ToolBase> wrappedTools = new ArrayList<>();
        for (ToolBase t : toolCallbacks) {
            if (t != null) {
                wrappedTools.add(new SubAgentHarnessToolCallback(
                        t, chatContext, subAgent, toolDisplayNameMap, requestId));
            }
        }
        // streamEvents 用最小 RuntimeContext（会话隔离）；工具的 ctx 由 executeTool 自建
        RuntimeContext.Builder rcb = RuntimeContext.builder()
                .sessionId(chatContext != null && chatContext.getSessionId() != null
                        ? String.valueOf(chatContext.getSessionId()) : "subagent");
        if (chatContext != null && chatContext.getUserId() != null) {
            rcb.userId(String.valueOf(chatContext.getUserId()));
        }
        RuntimeContext runtimeContext = rcb.build();

        HarnessAgent ha = harnessAgentFactory.build(
                model, sysPrompt, wrappedTools, MAX_LOOP_DEPTH, List.of());
        try {
            for (int attempt = 0; attempt <= modelRetryTimes; attempt++) {
                if ((chatContext != null && chatContext.isAborted()) || isCancelRequested(run)) {
                    return "";
                }
                AtomicBoolean gotFirst = new AtomicBoolean(false);
                Msg[] resultHolder = new Msg[1];
                try {
                    ha.streamEvents(conversationMsgs, runtimeContext)
                            .timeout(Mono.delay(Duration.ofSeconds(connectTimeoutSeconds)),
                                    e -> Mono.delay(Duration.ofSeconds(readTimeoutSeconds)),
                                    Flux.error(new java.util.concurrent.TimeoutException("subagent timeout")))
                            .doOnNext(e -> {
                                if (e instanceof TextBlockDeltaEvent d) {
                                    gotFirst.set(true);
                                    String delta = d.getDelta();
                                    if (delta != null && !delta.isEmpty()) {
                                        replyBuilder.append(delta);
                                        pushTokenEvent(chatContext, subAgent, delta);
                                    }
                                } else if (e instanceof AgentResultEvent r) {
                                    resultHolder[0] = r.getResult();
                                }
                                // TOOL_CALL_*/TOOL_RESULT_* 由装饰器接管；THINKING_BLOCK_DELTA 暂跳过
                            })
                            .blockLast();
                    String reply = replyBuilder.length() > 0 ? replyBuilder.toString()
                            : (resultHolder[0] != null ? resultHolder[0].getTextContent() : "");
                    // 追加 assistant 回复到 messages（续跑历史；工具轮次不可得，略）
                    if (resultHolder[0] != null) {
                        messages.add(resultHolder[0]);
                    } else if (!reply.isBlank()) {
                        messages.add(Msgs.assistant(reply));
                    }
                    return reply;
                } catch (Exception e) {
                    String reason;
                    String code;
                    if (e instanceof java.util.concurrent.TimeoutException) {
                        boolean connect = !gotFirst.get();
                        reason = connect ? "connect_timeout" : "read_timeout";
                        code = connect ? "CONNECT_TIMEOUT" : "READ_TIMEOUT";
                    } else {
                        reason = classifyFailureReason(e);
                        code = reasonToCode(reason);
                    }
                    if (attempt < modelRetryTimes) {
                        int retryNo = attempt + 1;
                        long delayMs = (long) Math.pow(2, attempt) * 1000;
                        log.warn("[SubAgent][Harness] 第{}次重试，等待{}ms: name={}, reason={}, error={}",
                                retryNo, delayMs, subAgent.getName(), reason, e.getMessage());
                        emitSubAgentErrorRetry(chatContext, subAgent,
                                buildRetryMessage(subAgent, reason, retryNo, modelRetryTimes),
                                code, retryNo, modelRetryTimes);
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        replyBuilder.setLength(0);
                    } else {
                        String errorMsg = (e instanceof java.util.concurrent.TimeoutException)
                                ? ("SubAgent " + (gotFirst.get() ? "响应超时" : "连接超时"))
                                : classifyErrorMessage(e);
                        emitSubAgentError(chatContext, subAgent, errorMsg, code);
                        return errorMsg;
                    }
                }
            }
            return "";
        } finally {
            try {
                ha.close();
            } catch (Exception ce) {
                log.warn("[SubAgent][Harness] close agent err={}", ce.getMessage());
            }
        }
    }

    /**
     * 子 agent 工具执行装饰器：包裹原 {@link ToolBase}，在 {@code callAsync} 发 subagent_tool_call/tool_result
     * 事件，工具执行复用外层 {@link #executeTool}（含 args 修复、RuntimeContext 注入）。
     */
    private final class SubAgentHarnessToolCallback extends ToolBase {
        private final ToolBase delegate;
        private final ChatContext chatContext;
        private final SubAgent subAgent;
        private final Map<String, String> toolDisplayNameMap;
        private final String requestId;

        SubAgentHarnessToolCallback(ToolBase delegate, ChatContext chatContext, SubAgent subAgent,
                                    Map<String, String> toolDisplayNameMap, String requestId) {
            super(delegate.getName(), delegate.getDescription(), delegate.getParameters(),
                    delegate.isReadOnly(), false, false, null, false, false);
            this.delegate = delegate;
            this.chatContext = chatContext;
            this.subAgent = subAgent;
            this.toolDisplayNameMap = toolDisplayNameMap;
            this.requestId = requestId;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            ToolUseBlock tc = param.getToolUseBlock();
            Map<String, Object> input = tc != null ? tc.getInput() : param.getInput();
            String rawArgs = serializeToolInput(input);
            // args 修复（对齐 legacy 循环 294-302）
            String callArgs = rawArgs;
            String repaired = toolArgsSanitizer.tryRepairTruncatedWriteArgs(delegate.getName(), rawArgs);
            if (repaired != null) {
                callArgs = repaired.replaceAll(",\\s*\"_repairedFromTruncation\"\\s*:\\s*true", "")
                        .replaceAll("\"_repairedFromTruncation\"\\s*:\\s*true\\s*,?", "");
            } else {
                callArgs = toolArgsSanitizer.forChatCall(rawArgs);
            }
            if (tc != null) {
                emitSubAgentToolCall(chatContext, subAgent, tc, toolDisplayNameMap);
            }
            String result;
            try {
                result = executeTool(delegate, callArgs, chatContext, requestId);
            } catch (Exception e) {
                log.warn("[SubAgent][Harness] 工具执行异常: subAgent={}, tool={}, error={}",
                        subAgent.getName(), delegate.getName(), e.getMessage());
                result = ToolResultPrefixes.failureJson(ToolResultPrefixes.FAILURE + ": " + e.getMessage());
            }
            if (tc != null) {
                emitSubAgentToolResult(chatContext, subAgent, tc, result, toolDisplayNameMap);
            }
            return Mono.just(ToolResultBlock.of(null, delegate.getName(),
                    TextBlock.builder().text(result).build()));
        }
    }
}
