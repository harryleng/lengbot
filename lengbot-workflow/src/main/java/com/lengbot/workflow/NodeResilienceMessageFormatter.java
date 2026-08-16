package com.lengbot.workflow;

import java.util.concurrent.TimeoutException;

/**
 * 节点失败信息用户可见文案格式化
 */
public final class NodeResilienceMessageFormatter {

    private NodeResilienceMessageFormatter() {
    }

    /**
     * 生成面向用户的失败说明（不含 Java 类名与堆栈）
     */
    public static String formatUserMessage(Throwable error) {
        if (error == null) {
            return "节点执行失败";
        }
        Throwable root = unwrap(error);
        String reason = resolveFailureReason(root);
        return switch (reason) {
            case "connect_timeout" -> extractTimeoutSeconds(root, "连接超时");
            case "read_timeout" -> extractTimeoutSeconds(root, "响应超时");
            default -> sanitizeMessage(root.getMessage());
        };
    }

    /**
     * 失败原因：connect_timeout / read_timeout / execution_error
     */
    public static String resolveFailureReason(Throwable error) {
        if (error == null) {
            return "execution_error";
        }
        return NodeResilienceEventContext.classifyFailureReason(unwrap(error), false);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String extractTimeoutSeconds(Throwable error, String prefix) {
        String msg = error != null ? error.getMessage() : null;
        if (msg != null) {
            int start = msg.indexOf('（');
            int end = msg.indexOf("秒）", start);
            if (start >= 0 && end > start) {
                return prefix + msg.substring(start, end + 2);
            }
            int secIdx = msg.indexOf("秒");
            if (secIdx > 0) {
                String digits = msg.substring(0, secIdx).replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    return prefix + "（" + digits + "秒）";
                }
            }
        }
        if (error instanceof TimeoutException) {
            return prefix;
        }
        return prefix;
    }

    private static String sanitizeMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "节点执行失败";
        }
        String msg = raw.trim();
        if (msg.startsWith("执行失败:")) {
            msg = msg.substring("执行失败:".length()).trim();
        }
        if (msg.startsWith("java.") || msg.startsWith("javax.")) {
            int colon = msg.indexOf(':');
            if (colon > 0 && colon < msg.length() - 1) {
                msg = msg.substring(colon + 1).trim();
            }
        }
        if (msg.contains("TimeoutException")) {
            return extractTimeoutSeconds(new TimeoutException(msg), "响应超时");
        }
        return msg.isBlank() ? "节点执行失败" : msg;
    }
}
