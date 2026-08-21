package com.lengbot.util;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.constant.ToolResultPrefixes;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 工具维度限流装饰器
 * <p>包装原始 {@link ToolBase}，在 callAsync 前用 {@link ToolRateLimiter} 按
 * (userId, toolName) 维度判定配额；超限时返回结构化错误 JSON 回喂给 LLM，
 * 让模型感知"该工具已被限流，请改用其他方式"，避免硬抛异常被外层兜底成 500。</p>
 *
 * @author lw
 * @since 2026-07-21
 */
@Slf4j
public class RateLimitedToolCallback extends ToolBase {

    private final ToolBase delegate;
    private final ToolRateLimiter rateLimiter;
    private final String rateLimitConfig;
    private final ObjectMapper objectMapper;

    public RateLimitedToolCallback(ToolBase delegate, ToolRateLimiter rateLimiter, String rateLimitConfig) {
        super(ToolBase.builder()
                .name(delegate.getName())
                .description(delegate.getDescription())
                .inputSchema(delegate.getParameters()));
        this.delegate = delegate;
        this.rateLimiter = rateLimiter;
        this.rateLimitConfig = rateLimitConfig;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 1. 解析当前用户 ID：优先从 ToolCallParam 取，其次从 SaToken 会话取（系统调用降级为 0）
        Long userId = resolveUserId(param);
        String toolName = delegate.getName();

        // 2. 限流判定：未开启/配置非法时直接放行
        if (!rateLimiter.tryAcquire(userId, toolName, rateLimitConfig)) {
            String msg = "工具调用已被限流，请稍后重试或改用其他方式";
            log.warn("[ToolRateLimit] 拦截调用: userId={}, tool={}", userId, toolName);
            return Mono.just(ToolResultBlock.of(null, toolName,
                    TextBlock.builder().text(ToolResultPrefixes.failureJson(msg)).build()));
        }

        // 3. 通过限流，委托原回调执行
        return delegate.callAsync(param);
    }

    /**
     * 从 ToolCallParam 上下文或 SaToken 会话解析用户 ID
     */
    @SuppressWarnings("unchecked")
    private Long resolveUserId(ToolCallParam param) {
        if (param != null) {
            try {
                Object ctx = param.getContext();
                if (ctx instanceof Map<?, ?> map) {
                    Object uid = map.get("userId");
                    if (uid instanceof Number n) {
                        return n.longValue();
                    }
                    if (uid instanceof String s && !s.isBlank()) {
                        try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (Exception ignored) {
                // ToolCallParam 可能不支持 getContext，降级到 SaToken
            }
        }
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            // 非会话上下文（定时任务、内部调用）按系统用户处理
            return 0L;
        }
    }
}
