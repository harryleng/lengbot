package com.lengbot.common.task;

/**
 * 任务执行异常基类，所有 TaskExecutor 抛出的业务异常应继承此类
 * <p>Worker 根据子类类型决定路由：
 * <ul>
 *   <li>{@link RetryableTaskException}  → 进延迟队列重试</li>
 *   <li>{@link FatalTaskException}      → 直接 markFailed + 死信</li>
 *   <li>{@link TaskCancelledException}  → markCancelled</li>
 * </ul>
 *
 * @author lw
 * @since 2026-07-18
 */
public abstract class TaskException extends RuntimeException {

    protected TaskException(String message) {
        super(message);
    }

    protected TaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
