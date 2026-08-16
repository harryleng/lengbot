package com.lengbot.common.task;

/**
 * 任务取消异常
 * <p>Worker 捕获后直接 markCancelled，不重试、不进死信。
 * 通常由 Executor 内部检测到 Redis 取消信号后抛出。
 *
 * @author finch
 * @since 2026-07-18
 */
public class TaskCancelledException extends TaskException {

    public TaskCancelledException() {
        super("任务已被用户取消");
    }

    public TaskCancelledException(String message) {
        super(message);
    }
}
