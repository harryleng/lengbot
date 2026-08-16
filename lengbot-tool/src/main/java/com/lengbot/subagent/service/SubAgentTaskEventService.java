package com.lengbot.subagent.service;

import com.lengbot.entity.SubAgentTaskEvent;

import java.util.List;
import java.util.Map;

/** SubAgent 任务运行事件服务。 */
public interface SubAgentTaskEventService {

    /** 记录一条可追溯的任务事件。 */
    void record(String taskId, String batchId, String eventType, Map<String, Object> payload);

    /** 按事件 ID 游标增量读取。 */
    List<SubAgentTaskEvent> list(String taskId, Long cursor, int limit);

    /** 获取最新的用户可读进度摘要。 */
    String latestSummary(String taskId);
}
