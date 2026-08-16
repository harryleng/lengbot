package com.lengbot.common.task;

/**
 * 可重试任务异常
 * <p>用于描述瞬时故障（如 Redis 连接抖动、LLM 网关 503、文件锁竞争等），
 * Worker 会按 RetryPolicy 退避后重投主队列。
 *
 * @author finch
 * @since 2026-07-18
 */
public class RetryableTaskException extends TaskException {

    public RetryableTaskException(String message) {
        super(message);
    }

    public RetryableTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
