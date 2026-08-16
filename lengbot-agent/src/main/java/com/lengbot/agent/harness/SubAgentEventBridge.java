package com.lengbot.agent.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.entity.SubAgent;
import com.lengbot.entity.SubAgentRun;
import com.lengbot.entity.SubAgentTaskBatch;
import com.lengbot.service.SubAgentService;
import com.lengbot.service.chat.ChatContext;
import com.lengbot.subagent.event.SubAgentEventPublisher;
import com.lengbot.subagent.spi.SubAgentTaskRepository;
import com.lengbot.util.TextNormalizeUtil;
import com.lengbot.util.ToolEventCompactUtil;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Harness 原生子 agent（agent_spawn）事件 → LengBot SubAgent batch/task SSE 协议桥接器。
 *
 * <p>C3 Phase 2：harness 的 {@code agent_spawn} 由模型触发、harness 内部调度（不经过 LengBot
 * {@code HarnessToolCallback} 装饰器），子 agent 事件经 source 归组（方案 E：source 形如
 * {@code parentSession/childId/sub-<uuid>}）。本桥接器在父事件流中拦截：
 * <ul>
 *   <li><b>source 非空子事件</b>：首个事件时匹配父流 agent_spawn 的 pending spawn → 建 DB
 *       batch+task（1 spawn = 1 batch = 1 task，决策 3）→ 发 {@code subagent_batch_start}/
 *       {@code subagent_task_start}；随后 {@code TextBlockDeltaEvent} → {@code subagent_token}，
 *       {@code ToolCall*} → {@code subagent_tool_call}，{@code ToolResult*} →
 *       {@code subagent_tool_result}，{@code AgentResultEvent} → {@code subagent_task_done}。</li>
 *   <li><b>父流 agent_spawn 工具事件</b>：{@code ToolCallDeltaEvent} 累积 args（单块完整 JSON），
 *       {@code ToolCallEndEvent} 注册 pending（确定性 md5 batchId/taskId），
 *       {@code ToolResultEndEvent} → {@code subagent_batch_done} + DB 终态。</li>
 * </ul>
 * 事件统一经 {@link SubAgentEventPublisher#publish} 发射（落库 + toolEventsList + SSE），
 * payload 字段与 {@code SubAgentTaskServiceImpl.publishBatchStart/publishTask/publishBatchDone}
 * 对齐，前端零改动复用现有 subagent_* 协议。</p>
 *
 * <p><b>时序关键</b>：contentOffset/delegationIndex 不在 TOOL_CALL_END 捕获（装饰器不处理
 * agent_spawn，此时 ctx 偏移未设），而在<b>首个子事件</b>时从 ctx.fullReply 现状计算
 * （此刻本回合正文已全部落库，工具块位于正文末尾）。delegationIndex 退化为 spawn 序号（决策 3）。</p>
 *
 * <p><b>线程模型</b>：{@link AgentEventSseBridge#bridge} 以 {@code concatMap} 串行消费事件流，
 * 每个请求一个 {@link Session}（经 {@link #create} 创建），无跨线程共享状态。</p>
 *
 * @author LengBot refactor
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubAgentEventBridge {

    private final SubAgentTaskRepository repository;
    private final SubAgentEventPublisher eventPublisher;
    private final SubAgentService subAgentService;
    private final ObjectMapper objectMapper;

    private static final String AGENT_SPAWN = "agent_spawn";
    private static final String MODE_SYNC = "sync";
    private static final String AGGREGATION_RETURN_ALL = "return_all";
    /** 子工具 args/result 入 SSE 的最大字符数（对齐 legacy 截断语义，避免撑爆事件帧）。 */
    private static final int MAX_EVENT_TEXT = 2000;
    private static final String ERROR_STATUS_MARKER = "\nstatus: error";

    /** 每个请求一个无状态会话入口。 */
    public Session create(ChatContext ctx) {
        return new Session(ctx);
    }

    /** 一次请求（一条用户消息）内的子 agent 事件归组状态。 */
    public final class Session {

        private final ChatContext ctx;
        private final String requestId;
        private final Long parentSessionId;
        /** 待匹配子事件的 agent_spawn（FIFO；同步 spawn 串行不交错）。 */
        private final Deque<PendingSpawn> pendingSpawns = new ArrayDeque<>();
        /** source 末段 subUuid → 活动子 agent。 */
        private final Map<String, ActiveSubAgent> activeByUuid = new HashMap<>();
        /** 父流 agent_spawn 的 toolCallId → subUuid，TOOL_RESULT_END 时反查发 batch_done。 */
        private final Map<String, String> toolCallToUuid = new HashMap<>();
        private int spawnSeq;
        // 父流当前 agent_spawn 工具累积状态（同步单发，不交错）
        private String parentToolCallId;
        private String parentArgs = "";
        private String parentResultText = "";

        Session(ChatContext ctx) {
            this.ctx = ctx;
            this.parentSessionId = ctx != null ? ctx.getSessionId() : null;
            this.requestId = ctx != null && ctx.getRequestId() != null && !ctx.getRequestId().isBlank()
                    ? ctx.getRequestId()
                    : ("harness_" + (parentSessionId != null ? parentSessionId : "anon"));
        }

        /**
         * 桥接器回调入口（{@code AgentEventSseBridge} 对 source 非空 + agent_spawn 工具事件调用）。
         *
         * @return 恒为空列表（SSE 经 {@code SubAgentEventPublisher.publish} 直发）
         */
        public List<String> handle(AgentEvent event) {
            try {
                if (event == null) {
                    return List.of();
                }
                String source = event.getSource();
                if (log.isDebugEnabled() && isToolOrStartEnd(event)) {
                    log.debug("[SubAgentBridge] recv {} source={} toolName={}",
                            event.getClass().getSimpleName(), source, toolNameOf(event));
                }
                if (source != null && !source.isEmpty()) {
                    handleChild(event, source);
                } else {
                    handleParentTool(event);
                }
            } catch (Exception e) {
                log.warn("[SubAgentBridge] 事件处理失败 type={} err={}",
                        event != null ? event.getClass().getSimpleName() : "null", e.getMessage());
            }
            return List.of();
        }

        // ==================== 子事件（source 非空） ====================

        private void handleChild(AgentEvent event, String source) {
            String subUuid = lastSegment(source);
            ActiveSubAgent sub = activeByUuid.get(subUuid);
            if (sub == null) {
                sub = openSubAgent(source, subUuid);
                if (sub == null) {
                    return;
                }
            }
            // 合成 AgentStartEvent 与子自身 AgentStart/End：open 已由首个事件触发，去重
            if (event instanceof AgentStartEvent || event instanceof AgentEndEvent) {
                return;
            }
            if (event instanceof TextBlockDeltaEvent d) {
                sub.token(d.getDelta());
            } else if (event instanceof ThinkingBlockDeltaEvent) {
                // 子 agent 思考流不单独推送（与 legacy 一致，只推正文 token）
            } else if (event instanceof ToolCallStartEvent t) {
                sub.beginToolCall(t.getToolCallId(), t.getToolCallName());
            } else if (event instanceof ToolCallDeltaEvent t) {
                sub.accumulateToolArgs(t.getDelta());
            } else if (event instanceof ToolCallEndEvent t) {
                sub.emitToolCall();
            } else if (event instanceof ToolResultStartEvent) {
                sub.beginToolResult();
            } else if (event instanceof ToolResultTextDeltaEvent t) {
                sub.accumulateToolResult(t.getDelta());
            } else if (event instanceof ToolResultDataDeltaEvent) {
                // 结构化 data 块：Phase 2 忽略
            } else if (event instanceof ToolResultEndEvent t) {
                sub.emitToolResult(t.getState());
            } else if (event instanceof AgentResultEvent r) {
                sub.finish(r.getResult());
            }
        }

        private ActiveSubAgent openSubAgent(String source, String subUuid) {
            PendingSpawn spawn = pendingSpawns.pollFirst();
            if (spawn == null) {
                // 无待注册 spawn（后台 fire-and-forget 或嵌套子 agent）：无法归属，忽略该子流
                log.debug("[SubAgentBridge] 未匹配到 pending spawn, 忽略子流: source={}", source);
                return null;
            }
            toolCallToUuid.put(spawn.toolCallId, subUuid);
            // 首个子事件时正文已全部落 ctx.fullReply：工具块位于正文末尾
            String reply = ctx != null && ctx.getFullReply() != null ? ctx.getFullReply().toString() : "";
            int splitAt = ToolEventCompactUtil.resolveToolBlockSplitOffset(reply, null, reply.length());
            Integer delegationIndex = spawnSeq; // 退化为 spawn 序号（决策 3）
            String prefixAnchor = splitAt > 0 ? reply.substring(0, splitAt) : null;
            ActiveSubAgent sub = new ActiveSubAgent(ctx, spawn, subUuid, splitAt, delegationIndex, prefixAnchor,
                    requestId, parentSessionId);
            activeByUuid.put(subUuid, sub);
            sub.open();
            return sub;
        }

        // ==================== 父流 agent_spawn 工具事件 ====================

        private void handleParentTool(AgentEvent event) {
            if (event instanceof ToolCallStartEvent t) {
                if (AGENT_SPAWN.equals(t.getToolCallName())) {
                    parentToolCallId = t.getToolCallId();
                    parentArgs = "";
                } else {
                    log.debug("[SubAgentBridge] parent tool start: {} (ignored)", t.getToolCallName());
                }
            } else if (event instanceof ToolCallDeltaEvent t) {
                // agent_spawn 的 args 经 __fragment__ delta 到达（raw fragment 名），非 agent_spawn。
                // 配对规则（修复 C3 Phase 2）：处于 agent_spawn 调用窗口（parentToolCallId 已置）且
                // fragment id 与当前 agent_spawn 一致（enriched 回填），空 id 时按窗口累积兜底。
                String deltaId = t.getToolCallId();
                boolean belongsToSpawn = parentToolCallId != null && t.getDelta() != null
                        && (deltaId == null || deltaId.isEmpty() || parentToolCallId.equals(deltaId));
                if (belongsToSpawn) {
                    parentArgs += t.getDelta();
                } else {
                    log.debug("[SubAgentBridge] parent tool delta: name={} id={} len={} (ignored)",
                            t.getToolCallName(), deltaId, t.getDelta() != null ? t.getDelta().length() : -1);
                }
            } else if (event instanceof ToolCallEndEvent t) {
                if (AGENT_SPAWN.equals(t.getToolCallName())) {
                    log.debug("[SubAgentBridge] TOOL_CALL_END agent_spawn: toolCallId={}, argsLen={}",
                            parentToolCallId, parentArgs != null ? parentArgs.length() : -1);
                    registerPending();
                } else {
                    log.debug("[SubAgentBridge] parent tool end: {} (ignored)", t.getToolCallName());
                }
            } else if (event instanceof ToolResultStartEvent t) {
                if (AGENT_SPAWN.equals(t.getToolCallName())) {
                    parentResultText = "";
                }
            } else if (event instanceof ToolResultTextDeltaEvent t) {
                if (AGENT_SPAWN.equals(t.getToolCallName()) && t.getDelta() != null) {
                    parentResultText += t.getDelta();
                }
            } else if (event instanceof ToolResultDataDeltaEvent) {
                // 忽略
            } else if (event instanceof ToolResultEndEvent t) {
                if (AGENT_SPAWN.equals(t.getToolCallName())) {
                    finishSpawn();
                }
            }
        }

        /** TOOL_CALL_END（工具执行前）：解析 args → 确定性 batchId/taskId → 入队 pending。 */
        private void registerPending() {
            if (parentArgs == null || parentArgs.isBlank() || parentToolCallId == null) {
                log.debug("[SubAgentBridge] registerPending skip: toolCallId={}, argsLen={}",
                        parentToolCallId, parentArgs != null ? parentArgs.length() : -1);
                return;
            }
            try {
                JsonNode args = objectMapper.readTree(parentArgs);
                String agentId = args.path("agent_id").asText(null);
                if (agentId == null || agentId.isBlank()) {
                    log.debug("[SubAgentBridge] registerPending no agent_id in args: {}", parentArgs);
                    return;
                }
                String task = args.path("task").asText("");
                spawnSeq++;
                pendingSpawns.addLast(new PendingSpawn(parentToolCallId, agentId, task,
                        batchId(requestId, spawnSeq, agentId, task),
                        taskId(requestId, spawnSeq, agentId, task)));
                log.debug("[SubAgentBridge] registerPending ok: agentId={}, taskLen={}, pending={}",
                        agentId, task.length(), pendingSpawns.size());
            } catch (Exception e) {
                log.warn("[SubAgentBridge] 解析 agent_spawn 参数失败: {}", e.getMessage());
            }
        }

        /** TOOL_RESULT_END：发 batch_done + DB 终态。 */
        private void finishSpawn() {
            if (parentToolCallId == null) {
                return;
            }
            String subUuid = toolCallToUuid.remove(parentToolCallId);
            if (subUuid == null) {
                return;
            }
            ActiveSubAgent sub = activeByUuid.get(subUuid);
            if (sub == null) {
                return;
            }
            boolean failed = parentResultText != null && parentResultText.contains(ERROR_STATUS_MARKER);
            sub.closeBatch(failed, parentResultText);
        }
    }

    /** 父流 agent_spawn 的 pending 注册项。 */
    private static final class PendingSpawn {
        final String toolCallId;
        final String agentId;
        final String task;
        final String batchId;
        final String taskId;

        PendingSpawn(String toolCallId, String agentId, String task, String batchId, String taskId) {
            this.toolCallId = toolCallId;
            this.agentId = agentId;
            this.task = task;
            this.batchId = batchId;
            this.taskId = taskId;
        }
    }

    // ============================ 活动子 agent ============================

    /** 一个已打开（有 source 子事件）的子 agent：持有归组键 + 发事件 + DB 落库。 */
    private final class ActiveSubAgent {

        private final ChatContext ctx;
        private final PendingSpawn spawn;
        @SuppressWarnings("unused")
        private final String subUuid;
        private final int contentOffset;
        private final Integer delegationIndex;
        private final String contentPrefixAnchor;
        private final String batchId;
        private final String taskId;
        private final String requestId;
        private final Long parentSessionId;
        private String displayName;
        private String icon;
        private boolean taskDone;
        private int toolCallCount;
        /** 子 agent 流式正文累积（token 流与子 AgentResult 的最终 reply 兜底，DB 落库用）。 */
        private final StringBuilder replyTokens = new StringBuilder();
        // 子 agent 内部当前工具累积态
        private String curToolName;
        private final StringBuilder curToolArgs = new StringBuilder();
        private final StringBuilder curToolResult = new StringBuilder();

        ActiveSubAgent(ChatContext ctx, PendingSpawn spawn, String subUuid, int contentOffset,
                       Integer delegationIndex, String contentPrefixAnchor,
                       String requestId, Long parentSessionId) {
            this.ctx = ctx;
            this.spawn = spawn;
            this.subUuid = subUuid;
            this.contentOffset = contentOffset;
            this.delegationIndex = delegationIndex;
            this.contentPrefixAnchor = contentPrefixAnchor;
            this.batchId = spawn.batchId;
            this.taskId = spawn.taskId;
            this.requestId = requestId;
            this.parentSessionId = parentSessionId;
        }

        /** 首个子事件：解析展示名 → 建 DB batch+task → 发 batch_start + task_start。 */
        void open() {
            resolveDisplay();
            ensureDbRecords();
            Map<String, Object> bs = base();
            bs.put("mode", MODE_SYNC);
            bs.put("aggregation", AGGREGATION_RETURN_ALL);
            List<Map<String, Object>> tasks = new ArrayList<>();
            Map<String, Object> taskMap = new LinkedHashMap<>();
            taskMap.put("task_id", taskId);
            taskMap.put("subagent_name", spawn.agentId);
            taskMap.put("display_name", displayName);
            if (icon != null && !icon.isEmpty()) {
                taskMap.put("icon", icon);
            }
            taskMap.put("task", taskText());
            taskMap.put("task_index", 0);
            tasks.add(taskMap);
            bs.put("tasks", tasks);
            if (contentPrefixAnchor != null && !contentPrefixAnchor.isEmpty()) {
                bs.put("contentPrefixAnchor", contentPrefixAnchor);
            }
            eventPublisher.publish(ctx, "subagent_batch_start", bs);

            Map<String, Object> ts = base();
            ts.put("task_index", 0);
            ts.put("task", taskText());
            ts.put("status", "running");
            ts.put("status_label", "运行中");
            eventPublisher.publish(ctx, "subagent_task_start", ts);
        }

        private void resolveDisplay() {
            try {
                SubAgent sa = subAgentService.getByName(spawn.agentId);
                if (sa != null) {
                    displayName = sa.getDisplayName() != null && !sa.getDisplayName().isBlank()
                            ? sa.getDisplayName() : spawn.agentId;
                    icon = sa.getIcon();
                } else {
                    displayName = spawn.agentId;
                }
            } catch (Exception e) {
                log.debug("[SubAgentBridge] 解析子 agent 展示名失败: name={}, err={}", spawn.agentId, e.getMessage());
                displayName = spawn.agentId;
            }
        }

        private void ensureDbRecords() {
            if (repository.findBatch(batchId) == null) {
                SubAgentTaskBatch batch = new SubAgentTaskBatch();
                batch.setBatchId(batchId);
                batch.setParentRequestId(requestId);
                batch.setParentThreadId(parentSessionId != null ? String.valueOf(parentSessionId) : "");
                batch.setParentSessionId(parentSessionId);
                batch.setMode(MODE_SYNC);
                batch.setAggregation(AGGREGATION_RETURN_ALL);
                batch.setStatus("running");
                batch.setTotalCount(1);
                batch.setCompletedCount(0);
                batch.setFailedCount(0);
                batch.setCancelledCount(0);
                batch.setCancelRequested(0);
                repository.saveBatch(batch);
            }
            if (repository.findTask(taskId) == null) {
                SubAgentRun run = new SubAgentRun();
                run.setBatchId(batchId);
                run.setParentRequestId(requestId);
                run.setParentThreadId(parentSessionId != null ? String.valueOf(parentSessionId) : "");
                run.setParentSessionId(parentSessionId);
                run.setSubagentName(spawn.agentId);
                run.setTask(taskText());
                run.setStatus("running");
                run.setRequestId(taskId);
                run.setMode(MODE_SYNC);
                run.setCancelRequested(0);
                run.setToolCallCount(0);
                run.setThreadId(subUuid);
                run.setStartTime(LocalDateTime.now());
                repository.saveTask(run);
            }
        }

        // ---------- 子事件映射 ----------

        void token(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            replyTokens.append(delta);
            Map<String, Object> p = base();
            p.put("content", delta);
            eventPublisher.publish(ctx, "subagent_token", p);
        }

        void beginToolCall(String toolCallId, String toolName) {
            curToolName = toolName != null ? toolName : "";
            curToolArgs.setLength(0);
            toolCallCount++;
        }

        void accumulateToolArgs(String delta) {
            if (delta != null) {
                curToolArgs.append(delta);
            }
        }

        /** TOOL_CALL_END（子工具执行前，args 已齐）：发 subagent_tool_call。
         *  保留 curToolName 供紧随其后的 TOOL_RESULT_* 配对（嵌套 agent_spawn 亦然）。 */
        void emitToolCall() {
            if (curToolName == null) {
                return;
            }
            Map<String, Object> p = base();
            p.put("toolName", curToolName);
            p.put("args", truncate(curToolArgs.toString(), MAX_EVENT_TEXT, "{}"));
            eventPublisher.publish(ctx, "subagent_tool_call", p);
            curToolArgs.setLength(0);
        }

        void beginToolResult() {
            curToolResult.setLength(0);
        }

        void accumulateToolResult(String delta) {
            if (delta != null) {
                curToolResult.append(delta);
            }
        }

        /** TOOL_RESULT_END：发 subagent_tool_result（截断）。 */
        void emitToolResult(ToolResultState state) {
            if (curToolName == null && curToolResult.length() == 0) {
                return;
            }
            Map<String, Object> p = base();
            p.put("toolName", curToolName != null ? curToolName : "");
            p.put("result", truncate(curToolResult.toString(), MAX_EVENT_TEXT, ""));
            eventPublisher.publish(ctx, "subagent_tool_result", p);
            curToolName = null;
            curToolResult.setLength(0);
        }

        /** 子 AGENT_RESULT：DB task 完成 + task_done(completed)。
         *  reply 取子结果 Msg 文本；子 AgentResultEvent 可能不带 TextBlock（harness 形态，见诊断日志），
         *  兜底用本流累积的 token 文本（与前端渲染一致）。 */
        void finish(Msg result) {
            if (taskDone) {
                return;
            }
            String fromResult = result != null ? result.getTextContent() : "";
            String reply = (fromResult == null || fromResult.isBlank())
                    ? replyTokens.toString() : fromResult;
            log.debug("[SubAgentBridge] subagent finish: agentId={}, resultTextLen={}, tokensLen={}, replyLen={}",
                    spawn.agentId, fromResult != null ? fromResult.length() : -1,
                    replyTokens.length(), reply.length());
            markTaskCompleted(reply);
        }

        /** 父流 TOOL_RESULT_END：batch 终态 + batch_done（task 若尚未终态则按父结果补终态）。
         *  子 AgentResultEvent 不被 harness 转发（call() 走返回值），reply 优先用流式正文
         *  replyTokens（干净文本），兜底用父流聚合结果 parentResultText。 */
        void closeBatch(boolean failed, String parentResultText) {
            if (!taskDone) {
                if (failed) {
                    markTaskFailed(parentResultText != null && !parentResultText.isBlank()
                            ? parentResultText : "SubAgent 执行失败");
                } else {
                    String tokensText = replyTokens.toString().trim();
                    markTaskCompleted(!tokensText.isBlank() ? tokensText : parentResultText);
                }
            }
            SubAgentTaskBatch batch = repository.findBatch(batchId);
            if (batch != null) {
                int completed = failed ? 0 : 1;
                int failedCount = failed ? 1 : 0;
                batch.setCompletedCount(completed);
                batch.setFailedCount(failedCount);
                batch.setCancelledCount(0);
                batch.setStatus(failed ? "failed" : "completed");
                repository.saveBatch(batch);
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("batch_id", batchId);
            p.put("status", failed ? "failed" : "completed");
            p.put("contentOffset", contentOffset);
            if (delegationIndex != null) {
                p.put("delegationIndex", delegationIndex);
            }
            eventPublisher.publish(ctx, "subagent_batch_done", p);
        }

        // ---------- 终态落库 + 事件 ----------

        private void markTaskCompleted(String reply) {
            if (taskDone) {
                return;
            }
            taskDone = true;
            String safeReply = reply != null ? TextNormalizeUtil.sanitizeForDatabase(reply) : "";
            SubAgentRun run = repository.findTask(taskId);
            if (run != null) {
                run.setStatus("completed");
                run.setReply(safeReply);
                run.setEndTime(LocalDateTime.now());
                run.setToolCallCount(toolCallCount);
                repository.saveTask(run);
            }
            Map<String, Object> done = base();
            done.put("task_index", 0);
            done.put("task", taskText());
            done.put("status", "completed");
            done.put("status_label", "已完成");
            done.put("result", Map.of("reply", safeReply));
            eventPublisher.publish(ctx, "subagent_task_done", done);
        }

        private void markTaskFailed(String errorMessage) {
            if (taskDone) {
                return;
            }
            taskDone = true;
            String safeMsg = errorMessage != null ? TextNormalizeUtil.sanitizeForDatabase(errorMessage) : "SubAgent 执行失败";
            SubAgentRun run = repository.findTask(taskId);
            if (run != null) {
                run.setStatus("failed");
                run.setErrorMessage(safeMsg);
                run.setEndTime(LocalDateTime.now());
                run.setToolCallCount(toolCallCount);
                repository.saveTask(run);
            }
            Map<String, Object> err = base();
            err.put("message", safeMsg);
            err.put("code", "SUBAGENT_ERROR");
            eventPublisher.publish(ctx, "subagent_error", err);

            Map<String, Object> done = base();
            done.put("task_index", 0);
            done.put("task", taskText());
            done.put("status", "failed");
            done.put("status_label", "执行失败");
            done.put("result", Map.of("error", safeMsg));
            eventPublisher.publish(ctx, "subagent_task_done", done);
        }

        // ---------- payload 基础字段（与 SubAgentTaskServiceImpl 协议对齐） ----------

        private Map<String, Object> base() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("batch_id", batchId);
            p.put("task_id", taskId);
            p.put("task_index", 0);
            p.put("subagentName", spawn.agentId);
            p.put("displayName", displayName);
            if (icon != null && !icon.isEmpty()) {
                p.put("icon", icon);
            }
            p.put("contentOffset", contentOffset);
            if (delegationIndex != null) {
                p.put("delegationIndex", delegationIndex);
            }
            return p;
        }

        private String taskText() {
            return spawn.task != null ? spawn.task : "";
        }
    }

    // ============================ 工具方法 ============================

    private static boolean isToolOrStartEnd(AgentEvent event) {
        return event instanceof ToolCallStartEvent || event instanceof ToolCallDeltaEvent
                || event instanceof ToolCallEndEvent || event instanceof ToolResultStartEvent
                || event instanceof ToolResultTextDeltaEvent || event instanceof ToolResultDataDeltaEvent
                || event instanceof ToolResultEndEvent || event instanceof AgentStartEvent
                || event instanceof AgentEndEvent || event instanceof TextBlockStartEvent
                || event instanceof TextBlockEndEvent;
    }

    private static String toolNameOf(AgentEvent event) {
        if (event instanceof ToolCallStartEvent t) return t.getToolCallName();
        if (event instanceof ToolCallDeltaEvent t) return t.getToolCallName();
        if (event instanceof ToolCallEndEvent t) return t.getToolCallName();
        if (event instanceof ToolResultStartEvent t) return t.getToolCallName();
        if (event instanceof ToolResultTextDeltaEvent t) return t.getToolCallName();
        if (event instanceof ToolResultDataDeltaEvent t) return t.getToolCallName();
        if (event instanceof ToolResultEndEvent t) return t.getToolCallName();
        return null;
    }

    private static String lastSegment(String source) {
        int idx = source.lastIndexOf('/');
        return idx >= 0 ? source.substring(idx + 1) : source;
    }

    private static String batchId(String requestId, int seq, String agentId, String task) {
        return "subagent_batch_" + hash(requestId + ":spawn:" + seq + ":" + agentId);
    }

    private static String taskId(String requestId, int seq, String agentId, String task) {
        return "subagent_task_" + hash(requestId + ":spawn:" + seq + ":" + agentId + ":" + task);
    }

    private static String hash(String value) {
        return DigestUtils.md5DigestAsHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String value, int max, String fallback) {
        if (value == null) {
            return fallback;
        }
        return value.length() > max ? value.substring(0, max) + "..." : value;
    }
}
