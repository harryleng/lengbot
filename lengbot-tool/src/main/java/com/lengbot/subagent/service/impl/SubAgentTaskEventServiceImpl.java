package com.lengbot.subagent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.entity.SubAgentTaskEvent;
import com.lengbot.subagent.service.SubAgentTaskEventService;
import com.lengbot.subagent.spi.SubAgentTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** SubAgent 任务事件服务实现。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubAgentTaskEventServiceImpl implements SubAgentTaskEventService {

    private final ObjectMapper objectMapper;
    private final SubAgentTaskRepository repository;

    @Override
    public void record(String taskId, String batchId, String eventType, Map<String, Object> payload) {
        if (taskId == null || taskId.isBlank() || eventType == null || eventType.isBlank()) {
            return;
        }
        // token 用于当前 SSE 实时展示，逐字持久化会制造大量无价值事件。
        if ("subagent_token".equals(eventType)) {
            return;
        }
        try {
            SubAgentTaskEvent event = new SubAgentTaskEvent();
            event.setTaskId(taskId);
            event.setBatchId(batchId);
            event.setEventType(eventType);
            event.setPayload(objectMapper.writeValueAsString(payload != null ? payload : Map.of()));
            repository.saveTaskEvent(event);
        } catch (Exception e) {
            // 运行观测失败不能影响 SubAgent 主流程。
            log.debug("[SubAgent] 记录任务事件失败: taskId={}, type={}, error={}", taskId, eventType, e.getMessage());
        }
    }

    @Override
    public List<SubAgentTaskEvent> list(String taskId, Long cursor, int limit) {
        return repository.findTaskEvents(taskId, cursor, Math.min(Math.max(limit, 1), 100));
    }

    @Override
    public String latestSummary(String taskId) {
        SubAgentTaskEvent event = repository.findLatestTaskEvent(taskId);
        if (event == null) {
            return "等待调度";
        }
        return switch (event.getEventType()) {
            case "subagent_task_start" -> "正在执行";
            case "subagent_tool_call" -> "正在调用工具";
            case "subagent_tool_result" -> "工具执行完成";
            case "subagent_task_done" -> "任务已完成";
            case "subagent_error" -> "任务执行异常";
            case "subagent_error_retry" -> "正在重试";
            default -> "已更新运行状态";
        };
    }
}
