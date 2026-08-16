package com.lengbot.task;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 任务消息（Stream 消息体解析结果）
 *
 * @author finch
 * @since 2026-07-18
 */
@Data
@AllArgsConstructor
public class TaskMessage {

    /** Stream 消息 ID，ACK 时使用 */
    private String streamId;

    /** 任务 ID */
    private Long taskId;

    /** 任务类型字符串 */
    private String type;

    /** 当前尝试次数（含本次） */
    private int attempts;
}
