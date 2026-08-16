package com.lengbot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.common.BizException;
import com.lengbot.dto.ChatAttachmentDTO;
import com.lengbot.entity.LlmTrace;
import com.lengbot.entity.Message;
import com.lengbot.enums.ErrorCode;
import com.lengbot.service.ChatAttachmentService;
import com.lengbot.service.MessageService;
import com.lengbot.service.LlmTraceService;
import com.lengbot.service.ResearchTaskProjectionService;
import com.lengbot.subagent.service.SubAgentTaskService;
import com.lengbot.vo.ResearchTaskProjectionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于消息工具事件和 SubAgent Run 的只读状态聚合。
 * <p>请求 ID 是唯一过滤条件，避免当前协作状态混入同一会话的历史任务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchTaskProjectionServiceImpl implements ResearchTaskProjectionService {

    private static final Set<String> RUNNING_STATUSES = Set.of("pending", "running", "cancel_requested", "submitted");
    private static final Set<String> FAILED_STATUSES = Set.of("failed", "cancelled", "timeout");

    private final MessageService messageService;
    private final ChatAttachmentService chatAttachmentService;
    private final SubAgentTaskService subAgentTaskService;
    private final LlmTraceService llmTraceService;
    private final ObjectMapper objectMapper;

    @Override
    public ResearchTaskProjectionVO getProjection(Long sessionId, String parentRequestId) {
        if (sessionId == null || parentRequestId == null || parentRequestId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "sessionId 与 parentRequestId 不能为空");
        }

        // 1. 仅读取本轮助手消息，按最后一次 write_todos 结果取得完整快照。
        List<Message> messages = messageService.listAssistantByRequestId(sessionId, parentRequestId);
        List<Map<String, Object>> todos = new ArrayList<>();
        Map<String, Map<String, Object>> artifacts = new LinkedHashMap<>();
        long version = 0L;
        LocalDateTime updateTime = null;
        for (Message message : messages) {
            version = Math.max(version, epochMillis(message.getCreateTime()));
            updateTime = later(updateTime, message.getCreateTime());
            extractToolState(message.getMetadata(), todos, artifacts);
        }

        // 2. 输入附件只取本轮第一条助手消息之前最近的用户消息，不使用会话全量附件。
        Message userMessage = messageService.getUserByRequestId(sessionId, parentRequestId);
        if (userMessage == null && !messages.isEmpty()) {
            // 兼容本次改造前未记录 requestId 的历史用户消息。
            userMessage = messageService.getPreviousUserMessage(sessionId, messages.get(0).getId());
        }
        List<ChatAttachmentDTO> attachments = loadAttachments(userMessage);

        // 3. Run 摘要由既有任务服务提供；其 parentRequestId 过滤与调度、取消保持同一事实源。
        List<Map<String, Object>> subagents = subAgentTaskService.listRuntimeSummaries(sessionId, parentRequestId, 100);
        for (Map<String, Object> run : subagents) {
            LocalDateTime runUpdateTime = parseTime(run.get("update_time"));
            version = Math.max(version, epochMillis(runUpdateTime));
            updateTime = later(updateTime, runUpdateTime);
        }

        // 4. 用量仅来自已落库的 Trace；不虚构上下文窗口、额度或百分比。
        LlmTrace trace = llmTraceService.findLatestByRequestId(parentRequestId);
        if (trace != null) {
            version = Math.max(version, epochMillis(trace.getCreateTime()));
            updateTime = later(updateTime, trace.getCreateTime());
        }

        ResearchTaskProjectionVO projection = new ResearchTaskProjectionVO();
        projection.setParentRequestId(parentRequestId);
        projection.setStatus(resolveStatus(todos, subagents));
        projection.setVersion(version);
        projection.setUpdateTime(updateTime);
        projection.setTodos(List.copyOf(todos));
        projection.setAttachments(attachments);
        projection.setArtifacts(List.copyOf(artifacts.values()));
        projection.setSubagents(subagents);
        projection.setUsage(buildUsage(trace));
        return projection;
    }

    /** 将 Trace 的真实计数投影为前端展示数据，不暴露调用内容。 */
    private Map<String, Object> buildUsage(LlmTrace trace) {
        Map<String, Object> usage = new LinkedHashMap<>();
        if (trace == null) {
            usage.put("available", false);
            return usage;
        }
        usage.put("available", true);
        usage.put("inputTokens", nonNegative(trace.getInputTokens()));
        usage.put("outputTokens", nonNegative(trace.getOutputTokens()));
        usage.put("totalTokens", nonNegative(trace.getTotalTokens()));
        usage.put("status", trace.getStatus());
        return usage;
    }

    private long nonNegative(Integer value) {
        return value == null ? 0L : Math.max(0, value.longValue());
    }

    /** 从本轮消息元数据恢复 Todo 与产物，解析失败只跳过损坏的单条事件。 */
    private void extractToolState(String metadata, List<Map<String, Object>> todos,
                                  Map<String, Map<String, Object>> artifacts) {
        if (metadata == null || metadata.isBlank()) {
            return;
        }
        try {
            JsonNode toolEvents = objectMapper.readTree(metadata).path("toolEvents");
            if (!toolEvents.isArray()) {
                return;
            }
            for (JsonNode event : toolEvents) {
                if (!"tool_result".equals(event.path("type").asText())) {
                    continue;
                }
                String toolName = event.path("toolName").asText();
                JsonNode result = readJson(event.path("result").asText(null));
                if ("write_todos".equals(toolName)
                        && result.path("success").asBoolean(false) && result.path("todos").isArray()) {
                    todos.clear();
                    result.path("todos").forEach(item -> todos.add(objectMapper.convertValue(item, Map.class)));
                }
                if ("present_artifacts".equals(toolName) && result.path("artifacts").isArray()) {
                    // 产物按 path 去重：同一文件多次交付保留最新
                    for (JsonNode artifact : result.path("artifacts")) {
                        Map<String, Object> value = objectMapper.convertValue(artifact, Map.class);
                        String key = firstNonBlank(value.get("path"), value.get("url"), value.get("name"));
                        if (key != null) {
                            artifacts.put(key, value);
                        }
                    }
                }
                // sandbox_write_file / sandbox_append_file 写 outputs/ 时也算产物
                // 同一文件多次写入只保留最后一次的快照（url 是写入时刻的预签名）
                if (("sandbox_write_file".equals(toolName) || "sandbox_append_file".equals(toolName))
                        && result.path("success").asBoolean(false)
                        && result.has("url")) {
                    Map<String, Object> value = objectMapper.convertValue(result, Map.class);
                    String key = firstNonBlank(value.get("path"), value.get("url"), value.get("name"));
                    if (key != null) {
                        // 统一字段名：sandbox_write_file 的结果是平铺结构，已含 path/url/name/contentType/size
                        artifacts.put(key, value);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[ResearchProjection] 跳过无法解析的消息元数据: {}", e.getMessage());
        }
    }

    private List<ChatAttachmentDTO> loadAttachments(Message userMessage) {
        if (userMessage == null || userMessage.getMetadata() == null || userMessage.getMetadata().isBlank()) {
            return List.of();
        }
        try {
            JsonNode attachments = objectMapper.readTree(userMessage.getMetadata()).path("attachments");
            if (!attachments.isArray()) {
                return List.of();
            }
            List<ChatAttachmentDTO> values = new ArrayList<>();
            attachments.forEach(node -> values.add(objectMapper.convertValue(node, ChatAttachmentDTO.class)));
            return chatAttachmentService.refreshPreviewUrls(values);
        } catch (Exception e) {
            log.debug("[ResearchProjection] 解析请求附件失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String resolveStatus(List<Map<String, Object>> todos, List<Map<String, Object>> subagents) {
        if (subagents.stream().anyMatch(run -> RUNNING_STATUSES.contains(String.valueOf(run.get("status"))))) {
            return "running";
        }
        if (todos.stream().anyMatch(todo -> {
            String status = String.valueOf(todo.get("status"));
            return "pending".equals(status) || "in_progress".equals(status);
        })) {
            return "running";
        }
        if (subagents.stream().anyMatch(run -> FAILED_STATUSES.contains(String.valueOf(run.get("status"))))) {
            return "failed";
        }
        if (!subagents.isEmpty() || !todos.isEmpty()) {
            return "completed";
        }
        return "idle";
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return objectMapper.nullNode();
        }
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private long epochMillis(LocalDateTime value) {
        return value == null ? 0L : value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private LocalDateTime later(LocalDateTime left, LocalDateTime right) {
        return left == null || right != null && right.isAfter(left) ? right : left;
    }

    private LocalDateTime parseTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        try {
            return value != null ? LocalDateTime.parse(String.valueOf(value)) : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
