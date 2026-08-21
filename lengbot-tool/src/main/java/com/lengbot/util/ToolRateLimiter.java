package com.lengbot.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.enums.RateLimitWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具维度限流器
 * <p>按 (userId, toolName) 维度对工具调用做固定窗口限流。
 * Redis Key 形如 {@code tool:rate:{userId}:{toolName}:{windowStart}}，
 * 其中 {@code windowStart} 是窗口起点 epoch 秒（按窗口大小对齐），
 * 窗口结束时 key 自然过期。</p>
 *
 * <p>限流配置存储于 {@code tool.rate_limit_config}，JSON 结构：
 * {@code {"limit": 10, "window": "MINUTE|HOUR|DAY"}}。</p>
 *
 * @author lw
 * @since 2026-07-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRateLimiter {

    private static final String KEY_PREFIX = "tool:rate:";

    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;

    /**
     * 尝试获取一次调用配额
     *
     * @param userId           当前用户 ID（系统级调用传 0）
     * @param toolName         工具标识
     * @param rateLimitConfig  {@code {"limit":N,"window":"MINUTE|HOUR|DAY"}}
     * @return true=允许调用，false=已超阈值
     */
    public boolean tryAcquire(Long userId, String toolName, String rateLimitConfig) {
        RateLimitConfig cfg = parse(rateLimitConfig);
        if (cfg == null) {
            return true;
        }
        long windowSeconds = cfg.window.toSeconds();
        long windowStart = alignToWindowStart(windowSeconds);
        // Key 包含窗口起点，新窗口开始时自动从 0 计数
        String key = KEY_PREFIX + userId + ":" + toolName + ":" + windowStart;
        long count = redisUtil.rateLimitIncrement(key, windowSeconds);
        if (count > cfg.limit) {
            log.warn("[ToolRateLimit] 触发限流: userId={}, tool={}, count={}, limit={}, window={}",
                    userId, toolName, count, cfg.limit, cfg.window.getCode());
            return false;
        }
        return true;
    }

    /**
     * 按 windowSeconds 对齐当前时间到窗口起点（epoch 秒）
     */
    private long alignToWindowStart(long windowSeconds) {
        long now = System.currentTimeMillis() / 1000L;
        return now - (now % windowSeconds);
    }

    /**
     * 解析限流配置，配置非法（缺字段/格式错）返回 null（视为不限流）
     */
    private RateLimitConfig parse(String rateLimitConfig) {
        if (rateLimitConfig == null || rateLimitConfig.isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(rateLimitConfig);
            if (!node.has("limit") || !node.has("window")) {
                return null;
            }
            int limit = node.get("limit").asInt(0);
            String windowCode = node.get("window").asText("MINUTE");
            if (limit <= 0) {
                return null;
            }
            return new RateLimitConfig(limit, RateLimitWindow.fromValue(windowCode));
        } catch (Exception e) {
            log.warn("[ToolRateLimit] 解析限流配置失败: config={}, error={}", rateLimitConfig, e.getMessage());
            return null;
        }
    }

    private record RateLimitConfig(int limit, RateLimitWindow window) {}
}
