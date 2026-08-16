package com.lengbot.workflow;

import lombok.Builder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 节点超时/重试 SSE 事件上下文
 */
@Builder
public record NodeResilienceEventContext(
        String nodeId,
        String nodeLabel,
        String nodeType,
        String executionId,
        String parentNodeId,
        Integer iterationIndex,
        Integer stepIndex,
        Consumer<Map<String, Object>> eventEmitter
) {

    /**
     * 推送重试中事件
     *
     * @param reason        connect_timeout / read_timeout / execution_error
     * @param failedAttempt 已失败的尝试次数（从 1 开始）
     * @param maxAttempts   总尝试次数上限
     */
    public void emitRetry(String reason, int failedAttempt, int maxAttempts) {
        if (eventEmitter == null) {
            return;
        }
        Map<String, Object> event = baseEvent("workflow_node_retry");
        event.put("reason", reason);
        event.put("attempt", failedAttempt);
        event.put("maxAttempts", maxAttempts);
        event.put("nextAttempt", failedAttempt + 1);
        event.put("message", buildRetryMessage(reason, failedAttempt, maxAttempts));
        eventEmitter.accept(event);
    }

    /**
     * 推送最终失败事件（重试耗尽或未开启重试）
     */
    public void emitFailure(String reason, int attempts, int maxAttempts, String detailMessage) {
        if (eventEmitter == null) {
            return;
        }
        Map<String, Object> event = baseEvent("workflow_node_failure");
        event.put("reason", reason);
        event.put("attempt", attempts);
        event.put("maxAttempts", maxAttempts);
        event.put("final", true);
        event.put("message", buildFailureMessage(reason, attempts, maxAttempts, detailMessage));
        eventEmitter.accept(event);
    }

    private Map<String, Object> baseEvent(String type) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("nodeId", nodeId);
        event.put("nodeLabel", nodeLabel);
        event.put("nodeType", nodeType);
        if (executionId != null) {
            event.put("executionId", executionId);
        }
        if (parentNodeId != null) {
            event.put("parentNodeId", parentNodeId);
        }
        if (iterationIndex != null) {
            event.put("iterationIndex", iterationIndex);
        }
        if (stepIndex != null) {
            event.put("stepIndex", stepIndex);
        }
        return event;
    }

    static String classifyFailureReason(Throwable cause, boolean outerTimeout) {
        if (outerTimeout || cause instanceof java.util.concurrent.TimeoutException) {
            return "read_timeout";
        }
        String msg = cause != null && cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";
        if (msg.contains("connect timed out") || msg.contains("connection timed out")
                || msg.contains("connection reset") || msg.contains("connect timeout")
                || msg.contains("连接超时") || msg.contains("连接失败")) {
            return "connect_timeout";
        }
        if (msg.contains("timeout") || msg.contains("超时")) {
            return "read_timeout";
        }
        return "execution_error";
    }

    static String reasonLabel(String reason) {
        return switch (reason) {
            case "connect_timeout" -> "连接超时";
            case "read_timeout" -> "响应超时";
            default -> "执行失败";
        };
    }

    private String buildRetryMessage(String reason, int failedAttempt, int maxAttempts) {
        String label = nodeLabel != null && !nodeLabel.isBlank() ? nodeLabel : "节点";
        int retryNo = failedAttempt;
        int retryTotal = Math.max(1, maxAttempts - 1);
        return label + "：" + reasonLabel(reason) + "，正在重试 " + retryNo + "/" + retryTotal;
    }

    private String buildFailureMessage(String reason, int attempts, int maxAttempts, String detailMessage) {
        String label = nodeLabel != null && !nodeLabel.isBlank() ? nodeLabel : "节点";
        String base = label + "：" + reasonLabel(reason);
        if (maxAttempts > 1) {
            base += "，已尝试 " + attempts + "/" + maxAttempts + " 次";
        }
        if (detailMessage != null && !detailMessage.isBlank()) {
            return base + "（" + detailMessage + "）";
        }
        return base;
    }
}
