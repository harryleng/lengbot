package com.lengbot.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Trace ID 过滤器：为每个 HTTP 请求生成或继承 traceId，写入 MDC 与响应头
 * <p>链路：前端请求头 {@code X-Trace-Id}（可空）→ 校验合法后沿用，否则生成 32 位 UUID；
 * 同步写入 {@link MDC}（供 logback pattern {@code %X{traceId}} 引用）与响应头，前端拦截器读取后回写 sessionStorage，
 * ErrorBoundary 上报时附带，实现前后端日志关联。</p>
 *
 * @author finch
 * @since 2026-07-21
 */
@Slf4j
public class TraceIdFilter extends OncePerRequestFilter {

    /** 前后端约定的 traceId 请求/响应头名 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** MDC 中 traceId 的键名，logback pattern 通过 %X{traceId} 引用 */
    public static final String MDC_TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. 优先沿用前端透传的 traceId（便于用户反馈错误时跨请求关联），不合法则生成新 ID
        String traceId = sanitize(request.getHeader(HEADER_TRACE_ID));
        if (traceId == null) {
            traceId = generate();
        }
        // 2. 写入 MDC + 响应头
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 防止线程池复用时残留上一个请求的 traceId
            MDC.remove(MDC_TRACE_ID);
        }
    }

    /**
     * 校验前端透传的 traceId：仅允许 32 位十六进制（UUID 去横线）或 36 位标准 UUID，防止任意字符串污染日志
     *
     * @param raw 原始请求头值
     * @return 合法的 traceId，不合法返回 null
     */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 64) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean valid = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F')
                    || c == '-';
            if (!valid) {
                return null;
            }
        }
        return trimmed;
    }

    /**
     * 生成 32 位无横线 UUID（日志更紧凑）
     *
     * @return traceId
     */
    private static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
