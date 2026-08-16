package com.lengbot.subagent.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.entity.SubAgentRun;
import com.lengbot.entity.SubAgentTaskBatch;
import com.lengbot.common.BizException;
import com.lengbot.service.SubAgentService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lengbot.service.chat.ChatContext;
import com.lengbot.subagent.SubAgentThreadManager;
import com.lengbot.subagent.event.SubAgentEventPublisher;
import com.lengbot.subagent.service.SubAgentTaskService;
import com.lengbot.subagent.service.SubAgentTaskEventService;
import com.lengbot.subagent.spi.SubAgentDefinition;
import com.lengbot.subagent.spi.SubAgentDefinitionResolver;
import com.lengbot.subagent.spi.SubAgentExecutor;
import com.lengbot.subagent.spi.SubAgentTaskRepository;
import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * SubAgent 批次编排实现：创建、调度、查询、取消均在本服务内完成。
 *
 * @author finch
 * @since 2026-07-10
 */
@Slf4j
@Service
public class SubAgentTaskServiceImpl implements SubAgentTaskService {

    private static final int MAX_TASKS = 5;
    private static final int DEFAULT_CONCURRENCY = 3;

    private final ObjectMapper objectMapper;
    private final SubAgentDefinitionResolver definitionResolver;
    private final SubAgentExecutor executor;
    private final SubAgentTaskRepository repository;
    private final SubAgentEventPublisher eventPublisher;
    private final SubAgentTaskEventService taskEventService;
    private final SubAgentThreadManager threadManager;
    private final SubAgentService subAgentService;
    private final Executor subAgentExecutor;

    /**
     * 显式构造器确保子智能体执行器按 Bean 名称注入；不能依赖 Lombok
     * 将字段上的 {@link Qualifier} 复制到构造器参数。
     *
     * @param objectMapper JSON 序列化器
     * @param definitionResolver 子智能体定义解析器
     * @param executor 子智能体运行执行器
     * @param repository 子智能体任务仓储
     * @param eventPublisher 子智能体事件发布器
     * @param taskEventService 子智能体任务事件服务
     * @param threadManager 子智能体线程管理器
     * @param subAgentService 子智能体服务
     * @param subAgentExecutor 子智能体并行执行线程池
     */
    public SubAgentTaskServiceImpl(ObjectMapper objectMapper,
                                   SubAgentDefinitionResolver definitionResolver,
                                   SubAgentExecutor executor,
                                   SubAgentTaskRepository repository,
                                   SubAgentEventPublisher eventPublisher,
                                   SubAgentTaskEventService taskEventService,
                                   SubAgentThreadManager threadManager,
                                   SubAgentService subAgentService,
                                   @Qualifier("subAgentExecutor") Executor subAgentExecutor) {
        this.objectMapper = objectMapper;
        this.definitionResolver = definitionResolver;
        this.executor = executor;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.taskEventService = taskEventService;
        this.threadManager = threadManager;
        this.subAgentService = subAgentService;
        this.subAgentExecutor = subAgentExecutor;
    }

    @Override
    public String delegate(String toolInput, ToolCallParam toolContext, List<Long> boundSubAgentIds) {
        try {
            DelegationInput input = parseDelegation(toolInput);
            Map<String, SubAgentDefinition> definitions = definitionResolver.resolve(boundSubAgentIds);
            String validationError = validate(input, definitions);
            if (validationError != null) {
                return json(Map.of("status", "failed", "error", validationError));
            }

            RuntimeContext context = runtimeContext(toolContext);
            String batchId = batchId(context.requestId(), input);
            ensureRecords(batchId, input, context);
            publishBatchStart(context, batchId, input, definitions);

            List<Map<String, Object>> results = "parallel".equals(input.mode()) && input.tasks().size() > 1
                    ? runParallel(batchId, input, context, definitions)
                    : runSequential(batchId, input, context, definitions);
            refreshBatch(batchId);
            publishBatchDone(context, batchId);
            // 返回时各任务已终态，reply 会作为 ToolResponse 进入主 Agent 的下一轮推理。
            return json(result(batchId, input, results));
        } catch (Exception e) {
            log.warn("[SubAgent] 批次委派失败: {}", e.getMessage());
            return json(Map.of("status", "failed", "error", "SubAgent 委派失败: " + e.getMessage()));
        }
    }

    @Override
    public String query(String toolInput, ToolCallParam toolContext) {
        try {
            Map<String, Object> args = args(toolInput);
            RuntimeContext context = runtimeContext(toolContext);
            String batchId = string(args.get("batch_id"));
            if (batchId != null && !batchId.isBlank()) {
                SubAgentTaskBatch batch = repository.findBatch(batchId);
                if (!ownedBy(batch, context.parentSessionId())) {
                    return denied();
                }
                return json(batchResult(batch));
            }
            List<Map<String, Object>> results = new ArrayList<>();
            for (String taskId : taskIds(args)) {
                SubAgentRun task = repository.findTask(taskId);
                if (!ownedBy(task, context.parentSessionId())) {
                    return denied();
                }
                results.add(taskResult(task));
            }
            if (results.isEmpty()) {
                return json(Map.of("status", "failed", "error", "缺少 task_id、task_ids 或 batch_id 参数"));
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("results", results);
            if (results.size() == 1) output.putAll(results.get(0));
            return json(output);
        } catch (Exception e) {
            return json(Map.of("status", "failed", "error", "查询失败: " + e.getMessage()));
        }
    }

    @Override
    public String cancel(String toolInput, ToolCallParam toolContext) {
        try {
            Map<String, Object> args = args(toolInput);
            RuntimeContext context = runtimeContext(toolContext);
            String taskId = string(args.get("task_id"));
            String batchId = string(args.get("batch_id"));
            if ((taskId == null || taskId.isBlank()) && (batchId == null || batchId.isBlank())) {
                return json(Map.of("status", "failed", "error", "缺少 task_id 或 batch_id 参数"));
            }
            if (taskId != null && !taskId.isBlank() && !ownedBy(repository.findTask(taskId), context.parentSessionId())) {
                return denied();
            }
            if (batchId != null && !batchId.isBlank() && !ownedBy(repository.findBatch(batchId), context.parentSessionId())) {
                return denied();
            }
            int affected = 0;
            if (taskId != null && !taskId.isBlank()) affected += repository.requestCancelTask(taskId);
            if (batchId != null && !batchId.isBlank()) affected += repository.requestCancelBatch(batchId);
            return json(Map.of("status", affected > 0 ? "cancel_requested" : "not_found", "affected", affected,
                    "task_id", taskId != null ? taskId : "", "batch_id", batchId != null ? batchId : ""));
        } catch (Exception e) {
            return json(Map.of("status", "failed", "error", "取消失败: " + e.getMessage()));
        }
    }

    @Override
    public Page<SubAgentRun> pageRuns(Long sessionId, String batchId, String parentRequestId,
                                      int pageNum, int pageSize) {
        return repository.pageTasks(sessionId, batchId, parentRequestId,
                Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 100));
    }

    @Override
    public Map<String, Object> getBatchDetail(String batchId, Long sessionId) {
        SubAgentTaskBatch batch = repository.findBatch(batchId);
        if (!ownedBy(batch, sessionId)) {
            throw new BizException("SubAgent 批次不存在或无权访问");
        }
        return batchResult(batch);
    }

    @Override
    public Map<String, Object> getTaskDetail(String taskId, Long sessionId) {
        SubAgentRun task = repository.findTask(taskId);
        if (!ownedBy(task, sessionId)) {
            throw new BizException("SubAgent 任务不存在或无权访问");
        }
        return taskResult(task);
    }

    @Override
    public Map<String, Object> cancelBatch(String batchId, Long sessionId) {
        SubAgentTaskBatch batch = repository.findBatch(batchId);
        if (!ownedBy(batch, sessionId)) {
            throw new BizException("SubAgent 批次不存在或无权访问");
        }
        int affected = repository.requestCancelBatch(batchId);
        return Map.of("batch_id", batchId, "status", affected > 0 ? "cancel_requested" : "not_found", "affected", affected);
    }

    @Override
    public Map<String, Object> cancelTask(String taskId, Long sessionId) {
        SubAgentRun task = repository.findTask(taskId);
        if (!ownedBy(task, sessionId)) {
            throw new BizException("SubAgent 任务不存在或无权访问");
        }
        int affected = repository.requestCancelTask(taskId);
        return Map.of("task_id", taskId, "status", affected > 0 ? "cancel_requested" : "not_found", "affected", affected);
    }

    @Override
    public int cancelByParentRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return 0;
        }
        return repository.requestCancelByParentRequestId(requestId);
    }

    @Override
    public Map<String, Object> getTaskThreadDetail(String taskId, Long sessionId) {
        SubAgentRun task = repository.findTask(taskId);
        if (!ownedBy(task, sessionId)) {
            throw new BizException("SubAgent 任务不存在或无权访问");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("task", taskResult(task));
        output.put("thread_id", task.getThreadId());
        output.put("available", threadManager.threadExists(task.getThreadId()));
        output.put("messages", threadManager.getMessageSnapshot(task.getThreadId()));
        return output;
    }

    @Override
    public Map<String, Object> getTaskEvents(String taskId, Long sessionId, Long cursor, int limit) {
        SubAgentRun task = repository.findTask(taskId);
        if (!ownedBy(task, sessionId)) {
            throw new BizException("SubAgent 任务不存在或无权访问");
        }
        List<com.lengbot.entity.SubAgentTaskEvent> events = taskEventService.list(taskId, cursor, limit);
        List<Map<String, Object>> items = events.stream().map(event -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cursor", String.valueOf(event.getId()));
            item.put("type", event.getEventType());
            item.put("payload", event.getPayload());
            item.put("create_time", event.getCreateTime());
            return item;
        }).toList();
        String nextCursor = events.isEmpty() ? (cursor != null ? String.valueOf(cursor) : null)
                : String.valueOf(events.get(events.size() - 1).getId());
        return Map.of("task_id", taskId, "events", items, "next_cursor", nextCursor != null ? nextCursor : "");
    }

    @Override
    public List<Map<String, Object>> listRuntimeSummaries(Long sessionId, String parentRequestId, int limit) {
        Map<String, String> displayNames = displayNames();
        return repository.pageTasks(sessionId, null, parentRequestId, 1,
                        Math.min(Math.max(limit, 1), 100)).getRecords().stream()
                .map(task -> taskResult(task, displayNames))
                .toList();
    }

    private List<Map<String, Object>> runSequential(String batchId, DelegationInput input, RuntimeContext context,
                                                      Map<String, SubAgentDefinition> definitions) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (int index = 0; index < input.tasks().size(); index++) {
            results.add(runTask(batchId, input.tasks().get(index), context, definitions, index));
            refreshBatch(batchId);
        }
        return results;
    }

    private List<Map<String, Object>> runParallel(String batchId, DelegationInput input, RuntimeContext context,
                                                    Map<String, SubAgentDefinition> definitions) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (int start = 0; start < input.tasks().size(); start += input.maxConcurrency()) {
            int end = Math.min(input.tasks().size(), start + input.maxConcurrency());
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            for (int index = start; index < end; index++) {
                int taskIndex = index;
                futures.add(CompletableFuture.supplyAsync(
                        () -> runTask(batchId, input.tasks().get(taskIndex), context, definitions, taskIndex), subAgentExecutor));
            }
            for (CompletableFuture<Map<String, Object>> future : futures) results.add(future.join());
            refreshBatch(batchId);
        }
        return results;
    }

    private Map<String, Object> runTask(String batchId, DelegatedTask task, RuntimeContext context,
                                        Map<String, SubAgentDefinition> definitions, int taskIndex) {
        String taskId = taskId(context.requestId(), task, taskIndex);
        SubAgentDefinition definition = definitions.get(task.subagentName());
        String displayName = definition != null ? definition.displayName() : task.subagentName();
        String icon = definition != null ? definition.icon() : null;
        // 先持久化 running，再发布开始事件。并行编排时，轮询批次详情可以立刻看到
        // 每个子任务的真实进度，而不是等整批 future 都结束后才从 pending 跳到 completed。
        String initialStatus = markTaskRunning(taskId);
        refreshBatch(batchId);
        publishTask(context, "subagent_task_start", batchId, taskId, task, taskIndex, displayName, icon, initialStatus, null);
        try {
            SubAgentExecutor.ExecutionResult result = executor.execute(definition, task.task(), taskId, task.threadId(),
                    context.parentThreadId(), context.taskContext(batchId, taskId, taskIndex));
            SubAgentRun run = ensureTerminalTaskResult(taskId, result);
            Map<String, Object> output = taskResult(run);
            output.put("reply", result.reply());
            output.put("thread_id", result.threadId());
            output.put("continued", result.continued());
            output.put("display_name", displayName);
            publishTask(context, "subagent_task_done", batchId, taskId, task, taskIndex, displayName,
                    icon, output.get("status").toString(), output);
            return output;
        } catch (Exception e) {
            SubAgentRun run = repository.findTask(taskId);
            if (run != null) {
                run.setStatus("failed");
                run.setErrorMessage(e.getMessage());
                run.setEndTime(LocalDateTime.now());
                repository.saveTask(run);
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("task_id", taskId);
            output.put("batch_id", batchId);
            output.put("subagent_name", task.subagentName());
            output.put("display_name", displayName);
            output.put("task", task.task());
            output.put("status", "failed");
            output.put("status_label", statusLabel("failed"));
            output.put("error", e.getMessage());
            publishTask(context, "subagent_task_done", batchId, taskId, task, taskIndex, displayName,
                    icon, "failed", output);
            return output;
        } finally {
            // 每个并行任务结束后即时刷新批次计数与状态；外部刷新接口据此获得实时进度。
            refreshBatch(batchId);
        }
    }

    private String markTaskRunning(String taskId) {
        SubAgentRun run = repository.findTask(taskId);
        if (run == null || Integer.valueOf(1).equals(run.getCancelRequested())
                || "cancelled".equals(run.getStatus())) {
            return "cancelled";
        }
        run.setStatus("running");
        if (run.getStartTime() == null) {
            run.setStartTime(LocalDateTime.now());
        }
        repository.saveTask(run);
        return "running";
    }

    /**
     * SPI 的正常实现会自行写入 completed/failed/cancelled；编排层仍兜底，
     * 防止替换执行器后把 running 状态返回给主 Agent 工具循环。
     */
    private SubAgentRun ensureTerminalTaskResult(String taskId, SubAgentExecutor.ExecutionResult executionResult) {
        SubAgentRun run = repository.findTask(taskId);
        if (run == null || isTerminal(run.getStatus())) {
            return run;
        }
        if (Integer.valueOf(1).equals(run.getCancelRequested())) {
            run.setStatus("cancelled");
        } else {
            run.setStatus("completed");
            run.setReply(executionResult != null && executionResult.reply() != null ? executionResult.reply() : "");
        }
        run.setEndTime(LocalDateTime.now());
        repository.saveTask(run);
        return run;
    }

    private void ensureRecords(String batchId, DelegationInput input, RuntimeContext context) {
        if (repository.findBatch(batchId) == null) {
            SubAgentTaskBatch batch = new SubAgentTaskBatch();
            batch.setBatchId(batchId);
            batch.setParentRequestId(context.requestId());
            batch.setParentThreadId(context.parentThreadId());
            batch.setParentSessionId(context.parentSessionId());
            batch.setMode(input.mode());
            batch.setAggregation(input.aggregation());
            batch.setStatus("pending");
            batch.setTotalCount(input.tasks().size());
            batch.setCompletedCount(0);
            batch.setFailedCount(0);
            batch.setCancelledCount(0);
            batch.setCancelRequested(0);
            repository.saveBatch(batch);
        }
        for (int index = 0; index < input.tasks().size(); index++) {
            DelegatedTask task = input.tasks().get(index);
            String taskId = taskId(context.requestId(), task, index);
            if (repository.findTask(taskId) != null) continue;
            SubAgentRun run = new SubAgentRun();
            run.setBatchId(batchId);
            run.setParentRequestId(context.requestId());
            run.setParentThreadId(context.parentThreadId());
            run.setParentSessionId(context.parentSessionId());
            run.setSubagentName(task.subagentName());
            run.setTask(task.task());
            run.setStatus("pending");
            run.setRequestId(taskId);
            run.setMode(input.mode());
            run.setCancelRequested(0);
            run.setToolCallCount(0);
            run.setThreadId(task.threadId() != null && !task.threadId().isBlank() ? task.threadId()
                    : SubAgentThreadManager.makeChildThreadId(context.parentThreadId(), task.subagentName(), taskId));
            repository.saveTask(run);
        }
    }

    private void refreshBatch(String batchId) {
        SubAgentTaskBatch batch = repository.findBatch(batchId);
        if (batch == null) return;
        List<SubAgentRun> tasks = repository.findTasks(batchId);
        int completed = 0, failed = 0, cancelled = 0, running = 0;
        for (SubAgentRun task : tasks) {
            if ("completed".equals(task.getStatus())) completed++;
            else if ("failed".equals(task.getStatus())) failed++;
            else if ("cancelled".equals(task.getStatus())) cancelled++;
            else if ("running".equals(task.getStatus())) running++;
        }
        batch.setCompletedCount(completed);
        batch.setFailedCount(failed);
        batch.setCancelledCount(cancelled);
        batch.setStatus(cancelled == tasks.size() && !tasks.isEmpty() ? "cancelled"
                : completed + failed + cancelled >= tasks.size() && !tasks.isEmpty() ? (failed > 0 ? "failed" : "completed")
                : running > 0 ? "running" : "pending");
        repository.saveBatch(batch);
    }

    private void publishBatchStart(RuntimeContext context, String batchId, DelegationInput input,
                                   Map<String, SubAgentDefinition> definitions) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (int index = 0; index < input.tasks().size(); index++) {
            DelegatedTask task = input.tasks().get(index);
            SubAgentDefinition definition = definitions.get(task.subagentName());
            Map<String, Object> taskMap = new LinkedHashMap<>();
            taskMap.put("task_id", taskId(context.requestId(), task, index));
            taskMap.put("subagent_name", task.subagentName());
            taskMap.put("display_name", definition != null ? definition.displayName() : task.subagentName());
            String icon = definition != null ? definition.icon() : null;
            if (icon != null && !icon.isEmpty()) {
                taskMap.put("icon", icon);
            }
            taskMap.put("task", task.task());
            taskMap.put("task_index", index);
            tasks.add(taskMap);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", 1);
        payload.put("parent_request_id", context.requestId());
        payload.put("batch_id", batchId);
        payload.put("mode", input.mode());
        payload.put("aggregation", input.aggregation());
        payload.put("tasks", tasks);
        payload.put("contentOffset", context.contentOffset());
        payload.put("delegationIndex", context.delegationIndex());
        // 与普通 tool_call 一致：携带正文前缀锚点，前端据此精确定位组件插入点，避免截断 AI 正文。
        if (context.contentPrefixAnchor() != null && !context.contentPrefixAnchor().isEmpty()) {
            payload.put("contentPrefixAnchor", context.contentPrefixAnchor());
        }
        eventPublisher.publish(context.chatContext(), "subagent_batch_start", payload);
    }

    private void publishTask(RuntimeContext context, String type, String batchId, String taskId, DelegatedTask task,
                             int taskIndex, String displayName, String icon, String status, Map<String, Object> result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", 1);
        payload.put("parent_request_id", context.requestId());
        payload.put("batch_id", batchId);
        payload.put("task_id", taskId);
        payload.put("task_index", taskIndex);
        payload.put("subagentName", task.subagentName());
        payload.put("displayName", displayName);
        if (icon != null && !icon.isEmpty()) {
            payload.put("icon", icon);
        }
        payload.put("task", task.task());
        payload.put("status", status);
        payload.put("status_label", statusLabel(status));
        payload.put("contentOffset", context.contentOffset());
        payload.put("delegationIndex", context.delegationIndex());
        if (result != null) payload.put("result", result);
        eventPublisher.publish(context.chatContext(), type, payload);
    }

    private void publishBatchDone(RuntimeContext context, String batchId) {
        SubAgentTaskBatch batch = repository.findBatch(batchId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", 1);
        payload.put("parent_request_id", context.requestId());
        payload.put("batch_id", batchId);
        payload.put("status", batch != null ? batch.getStatus() : "completed");
        payload.put("contentOffset", context.contentOffset());
        payload.put("delegationIndex", context.delegationIndex());
        eventPublisher.publish(context.chatContext(), "subagent_batch_done", payload);
    }

    private Map<String, Object> result(String batchId, DelegationInput input, List<Map<String, Object>> results) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("batch_id", batchId);
        output.put("mode", input.mode());
        output.put("aggregation", input.aggregation());
        SubAgentTaskBatch batch = repository.findBatch(batchId);
        output.put("status", batch != null ? batch.getStatus() : "completed");
        output.put("results", results);
        if (results.size() == 1) output.putAll(results.get(0));
        return output;
    }

    private Map<String, Object> batchResult(SubAgentTaskBatch batch) {
        if (batch == null) return Map.of("status", "not_found", "results", List.of());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("batch_id", batch.getBatchId());
        output.put("status", batch.getStatus());
        output.put("mode", batch.getMode());
        output.put("aggregation", batch.getAggregation());
        output.put("total_count", batch.getTotalCount());
        output.put("completed_count", batch.getCompletedCount());
        output.put("failed_count", batch.getFailedCount());
        output.put("cancelled_count", batch.getCancelledCount());
        output.put("cancel_requested", Integer.valueOf(1).equals(batch.getCancelRequested()));
        Map<String, String> displayNames = displayNames();
        output.put("results", repository.findTasks(batch.getBatchId()).stream()
                .map(task -> taskResult(task, displayNames)).toList());
        return output;
    }

    private Map<String, Object> taskResult(SubAgentRun task) {
        return taskResult(task, displayNames());
    }

    private Map<String, Object> taskResult(SubAgentRun task, Map<String, String> displayNames) {
        if (task == null) return new LinkedHashMap<>(Map.of("status", "not_found"));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("task_id", task.getRequestId());
        output.put("batch_id", task.getBatchId());
        output.put("status", task.getStatus());
        output.put("status_label", statusLabel(task.getStatus()));
        output.put("subagent_name", task.getSubagentName());
        output.put("display_name", displayNames.getOrDefault(task.getSubagentName(), task.getSubagentName()));
        output.put("task", task.getTask());
        output.put("thread_id", task.getThreadId());
        output.put("mode", task.getMode());
        output.put("reply", task.getReply());
        output.put("error", task.getErrorMessage());
        output.put("cancel_requested", Integer.valueOf(1).equals(task.getCancelRequested()));
        output.put("start_time", task.getStartTime());
        output.put("end_time", task.getEndTime());
        output.put("update_time", task.getUpdateTime());
        output.put("progress_summary", progressSummary(task));
        return output;
    }

    private String progressSummary(SubAgentRun task) {
        return switch (task.getStatus() != null ? task.getStatus() : "") {
            case "pending" -> "等待调度";
            case "completed" -> "任务已完成";
            case "failed" -> "任务执行失败";
            case "cancel_requested" -> "正在取消任务";
            case "cancelled" -> "任务已取消";
            default -> taskEventService.latestSummary(task.getRequestId());
        };
    }

    private Map<String, String> displayNames() {
        Map<String, String> result = new LinkedHashMap<>();
        subAgentService.list().forEach(subAgent -> result.put(subAgent.getName(),
                subAgent.getDisplayName() != null && !subAgent.getDisplayName().isBlank()
                        ? subAgent.getDisplayName() : subAgent.getName()));
        return result;
    }

    private String statusLabel(String status) {
        return switch (status != null ? status : "") {
            case "pending", "submitted" -> "等待调度";
            case "running" -> "运行中";
            case "completed" -> "已完成";
            case "failed" -> "执行失败";
            case "cancel_requested" -> "取消中";
            case "cancelled" -> "已取消";
            default -> "未知状态";
        };
    }

    private DelegationInput parseDelegation(String toolInput) throws Exception {
        Map<String, Object> args = args(toolInput);
        String mode = string(args.get("mode"));
        mode = mode == null || mode.isBlank() ? "sync" : mode.trim().toLowerCase();
        String aggregation = string(args.get("aggregation"));
        aggregation = aggregation == null || aggregation.isBlank() ? "return_all" : aggregation;
        int concurrency = number(args.get("max_concurrency"), DEFAULT_CONCURRENCY);
        concurrency = Math.max(1, Math.min(MAX_TASKS, concurrency));
        List<DelegatedTask> tasks = new ArrayList<>();
        if (args.get("tasks") instanceof List<?> rawTasks) {
            for (Object raw : rawTasks) if (raw instanceof Map<?, ?> task) {
                tasks.add(new DelegatedTask(string(task.get("subagent_name")), string(task.get("task")), string(task.get("thread_id"))));
            }
        }
        if (tasks.isEmpty()) tasks.add(new DelegatedTask(string(args.get("subagent_name")), string(args.get("task")), string(args.get("thread_id"))));
        return new DelegationInput(mode, tasks.size() > MAX_TASKS ? tasks.subList(0, MAX_TASKS) : tasks, concurrency, aggregation);
    }

    private String validate(DelegationInput input, Map<String, SubAgentDefinition> definitions) {
        if (!List.of("sync", "parallel").contains(input.mode())) return "mode 仅支持 sync、parallel；子智能体必须等待任务完成后返回结果";
        if (!List.of("return_all", "summarize").contains(input.aggregation())) return "aggregation 仅支持 return_all、summarize";
        for (DelegatedTask task : input.tasks()) {
            if (task.subagentName() == null || task.subagentName().isBlank()) return "缺少 subagent_name 参数";
            if (task.task() == null || task.task().isBlank()) return "缺少 task 参数";
            if (!definitions.containsKey(task.subagentName())) return "未在当前 Agent 绑定列表中找到子智能体: " + task.subagentName();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private RuntimeContext runtimeContext(ToolCallParam toolContext) {
        Map<String, Object> values = Map.of();
        try {
            if (toolContext != null) {
                Object ctx = toolContext.getRuntimeContext();
                if (ctx instanceof Map<?, ?> m) {
                    values = (Map<String, Object>) m;
                } else if (ctx instanceof io.agentscope.core.agent.RuntimeContext rc) {
                    // AgentScope RuntimeContext：chatContext/requestId 等经 builder().put() 存于 stringAttributes（getExtra()）
                    Map<String, Object> extra = rc.getExtra();
                    if (extra != null && !extra.isEmpty()) {
                        values = extra;
                    }
                }
            }
        } catch (Exception ignored) {
            // ToolCallParam 可能不支持 getContext，降级为空上下文
        }
        String requestId = string(values.get("requestId"));
        String parentThreadId = string(values.get("parentThreadId"));
        Long sessionId = longValue(values.get("sessionId"));
        ChatContext chatContext = values.get("chatContext") instanceof ChatContext ctx ? ctx : null;
        int offset = chatContext != null && chatContext.getSubAgentContentOffset() != null ? chatContext.getSubAgentContentOffset() : 0;
        Integer delegationIndex = chatContext != null ? chatContext.getSubAgentDelegationIndex() : null;
        String prefixAnchor = chatContext != null ? chatContext.getSubAgentContentPrefixAnchor() : null;
        return new RuntimeContext(requestId == null || requestId.isBlank() ? "subagent_" + System.currentTimeMillis() : requestId,
                parentThreadId != null ? parentThreadId : "", sessionId, chatContext, offset, delegationIndex, prefixAnchor);
    }

    private Map<String, Object> args(String input) throws Exception {
        return objectMapper.readValue(input != null && !input.isBlank() ? input : "{}", new TypeReference<>() {});
    }
    private String json(Map<String, Object> value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { return "{\"status\":\"failed\"}"; } }
    private String denied() { return json(Map.of("status", "forbidden", "error", "无权访问其他会话的 SubAgent 任务")); }
    private boolean ownedBy(SubAgentTaskBatch batch, Long sessionId) { return batch != null && sessionId != null && sessionId.equals(batch.getParentSessionId()); }
    private boolean ownedBy(SubAgentRun task, Long sessionId) { return task != null && sessionId != null && sessionId.equals(task.getParentSessionId()); }
    private List<String> taskIds(Map<String, Object> args) { List<String> ids = new ArrayList<>(); if (string(args.get("task_id")) != null) ids.add(string(args.get("task_id"))); if (args.get("task_ids") instanceof List<?> list) for (Object item : list) if (string(item) != null) ids.add(string(item)); return ids; }
    private String batchId(String requestId, DelegationInput input) { return "subagent_batch_" + hash(requestId + ":" + input.mode() + ":" + input.aggregation() + ":" + input.tasks()); }
    private String taskId(String requestId, DelegatedTask task, int index) { return "subagent_task_" + hash(requestId + ":" + index + ":" + task); }
    private String hash(String value) { return DigestUtils.md5DigestAsHex(value.getBytes(StandardCharsets.UTF_8)); }
    private boolean isTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }
    private String string(Object value) { return value != null && !value.toString().isBlank() ? value.toString() : null; }
    private int number(Object value, int fallback) { try { return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return fallback; } }
    private Long longValue(Object value) { try { return value != null ? Long.parseLong(value.toString()) : null; } catch (Exception e) { return null; } }

    private record DelegationInput(String mode, List<DelegatedTask> tasks, int maxConcurrency, String aggregation) {}
    private record DelegatedTask(String subagentName, String task, String threadId) {}
    private record RuntimeContext(String requestId, String parentThreadId, Long parentSessionId, ChatContext chatContext,
                                  int contentOffset, Integer delegationIndex, String contentPrefixAnchor) {
        ChatContext taskContext(String batchId, String taskId, int taskIndex) {
            if (chatContext == null) return null;
            ChatContext taskContext = new ChatContext();
            taskContext.setProviderId(chatContext.getProviderId());
            taskContext.setConfigMap(chatContext.getConfigMap());
            taskContext.setRequestId(requestId);
            taskContext.setAborted(chatContext.isAborted());
            taskContext.setRealtimeStatusEmitter(chatContext.getRealtimeStatusEmitter());
            // 子任务工具事件写回父会话，确保最终 assistant message 的 metadata 可完整回显。
            taskContext.setToolEventsList(chatContext.getToolEventsList());
            taskContext.setSubAgentContentOffset(contentOffset);
            taskContext.setSubAgentDelegationIndex(delegationIndex);
            taskContext.setSubAgentBatchId(batchId);
            taskContext.setSubAgentTaskId(taskId);
            taskContext.setSubAgentTaskIndex(taskIndex);
            return taskContext;
        }
    }
}
