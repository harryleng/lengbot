package com.lengbot.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 模型调用异常分类工具
 * <p>将底层模型/网络异常翻译为用户友好的中文提示与错误码，
 * 供对话、Prompt 调试等多处复用，避免各处重复维护错误映射。</p>
 *
 * @author lw
 * @since 2026-07-07
 */
public final class ModelErrorClassifier {

    private static final int MAX_MESSAGE_LENGTH = 100;
    private static final int MAX_DETAIL_LENGTH = 300;

    private ModelErrorClassifier() {
    }

    /**
     * 将异常分类为用户友好的错误提示
     *
     * @param e 异常
     * @return 用户友好的中文错误提示
     */
    public static String classifyMessage(Throwable e) {
        if (e == null) {
            return "模型调用异常：未知错误";
        }

        String timeout = matchInChain(e, msg -> msg.contains("timeout") || msg.contains("timed out") || msg.contains("Timeout"));
        if (timeout != null) {
            return "模型响应超时，请稍后重试";
        }
        String rateLimited = matchInChain(e, msg -> msg.contains("429") || msg.contains("rate") || msg.contains("Rate"));
        if (rateLimited != null) {
            return "模型请求被限流，请稍后重试";
        }
        String auth = matchInChain(e, msg -> msg.contains("401") || msg.contains("403")
                || msg.contains("Unauthorized") || msg.contains("Forbidden")
                || msg.contains("模型认证失败") || msg.contains("Invalid API Key")
                || msg.contains("invalid_key"));
        if (auth != null) {
            return "模型认证失败，请检查 API Key 配置";
        }
        String tokenLimit = matchInChain(e, msg -> msg.contains("token")
                && (msg.contains("limit") || msg.contains("exceed") || msg.contains("maximum")));
        if (tokenLimit != null) {
            return "上下文长度超限，请缩短对话后重试";
        }
        String contentFilter = matchInChain(e, msg -> msg.contains("content_filter")
                || msg.contains("safety") || msg.contains("blocked"));
        if (contentFilter != null) {
            return "内容触发安全策略，请调整输入后重试";
        }

        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            Throwable root = unwrap(e);
            msg = root != null ? root.getMessage() : null;
        }
        if (msg == null) {
            msg = e.getClass().getSimpleName();
        }
        return "模型调用异常：" + (msg.length() > MAX_MESSAGE_LENGTH ? msg.substring(0, MAX_MESSAGE_LENGTH) + "..." : msg);
    }

    /**
     * 生成带底层错误细节的错误信息（友好提示 + 原始错误摘要）
     *
     * @param e 异常
     * @return 完整错误信息
     */
    public static String formatDetail(Throwable e) {
        if (e == null) {
            return "模型调用异常：未知错误";
        }
        String friendly = classifyMessage(e);
        Throwable root = unwrap(e);
        if (root == null) {
            return friendly;
        }
        String detail = root.getMessage();
        if (detail == null || detail.isBlank()) {
            return friendly;
        }
        String trimmed = detail.strip();
        if (trimmed.length() > MAX_DETAIL_LENGTH) {
            trimmed = trimmed.substring(0, MAX_DETAIL_LENGTH) + "...";
        }
        if (friendly.equals(trimmed) || friendly.contains(trimmed)) {
            return friendly;
        }
        return friendly + " | " + trimmed;
    }

    /**
     * 将异常分类为错误码
     *
     * @param e 异常
     * @return 错误码（TIMEOUT/RATE_LIMITED/AUTH_ERROR/TOKEN_LIMIT/CONTENT_FILTER/LLM_ERROR/UNKNOWN）
     */
    public static String classifyCode(Throwable e) {
        if (e == null) {
            return "UNKNOWN";
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = e;
        while (current != null && !seen.contains(current)) {
            seen.add(current);
            String code = classifyCodeSingle(current);
            if (!"UNKNOWN".equals(code) && !"LLM_ERROR".equals(code)) {
                return code;
            }
            current = current.getCause();
        }
        return "UNKNOWN";
    }

    /**
     * 展开异常链，获取最底层根因
     *
     * @param e 异常
     * @return 根因异常
     */
    public static Throwable unwrap(Throwable e) {
        if (e == null) {
            return null;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable root = e;
        while (root != null && !seen.contains(root)) {
            seen.add(root);
            Throwable cause = root.getCause();
            if (cause == null || cause == root) {
                break;
            }
            root = cause;
        }
        return root;
    }

    /**
     * 判断是否为不可重试的致命模型错误（如 API Key 无效、无可用提供商）
     *
     * @param e 异常
     * @return 致命错误返回 true
     */
    public static boolean isFatal(Throwable e) {
        if (e == null) {
            return false;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = e;
        while (current != null && !seen.contains(current)) {
            seen.add(current);
            if (current instanceof IllegalStateException) {
                return true;
            }
            String className = current.getClass().getName();
            if (className.contains("NonTransientAiException")) {
                return true;
            }
            if ("AUTH_ERROR".equals(classifyCodeSingle(current))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 将异常转为带友好提示和底层细节的运行时异常
     *
     * @param e 异常
     * @return 运行时异常
     */
    public static RuntimeException toRuntimeException(Throwable e) {
        return new RuntimeException(formatDetail(e), e);
    }

    private static String classifyCodeSingle(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) {
            return "UNKNOWN";
        }
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("Timeout")) {
            return "TIMEOUT";
        }
        if (msg.contains("429") || msg.contains("rate") || msg.contains("Rate")) {
            return "RATE_LIMITED";
        }
        if (msg.contains("401") || msg.contains("403")
                || msg.contains("Unauthorized") || msg.contains("Forbidden")
                || msg.contains("模型认证失败") || msg.contains("Invalid API Key")
                || msg.contains("invalid_key")) {
            return "AUTH_ERROR";
        }
        if (msg.contains("token") && (msg.contains("limit") || msg.contains("exceed"))) {
            return "TOKEN_LIMIT";
        }
        if (msg.contains("content_filter") || msg.contains("safety")) {
            return "CONTENT_FILTER";
        }
        return "LLM_ERROR";
    }

    private static String matchInChain(Throwable e, java.util.function.Predicate<String> matcher) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = e;
        while (current != null && !seen.contains(current)) {
            seen.add(current);
            String msg = current.getMessage();
            if (msg != null && matcher.test(msg)) {
                return msg;
            }
            current = current.getCause();
        }
        return null;
    }
}
