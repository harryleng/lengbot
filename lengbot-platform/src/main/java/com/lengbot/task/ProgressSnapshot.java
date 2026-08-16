package com.lengbot.task;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务进度快照（Redis Hash 缓存读出）
 *
 * @author finch
 * @since 2026-07-18
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProgressSnapshot {

    /** 进度 0-100 */
    private int progress;

    /** 状态消息 */
    private String message;

    /** 上报时间戳（毫秒） */
    private long ts;
}
