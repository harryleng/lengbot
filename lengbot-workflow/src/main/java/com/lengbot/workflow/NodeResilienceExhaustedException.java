package com.lengbot.workflow;

import lombok.Getter;

/**
 * 节点重试耗尽后抛出的异常，携带失败分类信息供 SSE 回显
 */
@Getter
public class NodeResilienceExhaustedException extends RuntimeException {

    private final String failureReason;
    private final int attempts;
    private final int maxAttempts;

    public NodeResilienceExhaustedException(String message, String failureReason, int attempts, int maxAttempts, Throwable cause) {
        super(message, cause);
        this.failureReason = failureReason;
        this.attempts = attempts;
        this.maxAttempts = maxAttempts;
    }
}
