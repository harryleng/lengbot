package com.lengbot.service;

import com.lengbot.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * LLM 调用统计服务
 * <p>基于 Redis Hash 记录每次调用的 Token 消耗、延迟、成功/失败率</p>
 * <p>Redis key: lengbot:llm:stats:{scope}:{id}:{date}</p>
 *
 * @author lw
 * @since 2026-06-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmCallStatsService {

    private final RedisUtil redisUtil;

    private static final String KEY_PREFIX = "lengbot:llm:stats:";
    private static final long KEY_TTL_DAYS = 7;

    // Hash field 常量
    private static final String FIELD_TOTAL_CALLS = "totalCalls";
    private static final String FIELD_SUCCESS_CALLS = "successCalls";
    private static final String FIELD_FAIL_CALLS = "failCalls";
    private static final String FIELD_TOTAL_PROMPT_TOKENS = "totalPromptTokens";
    private static final String FIELD_TOTAL_COMPLETION_TOKENS = "totalCompletionTokens";
    private static final String FIELD_TOTAL_LATENCY_MS = "totalLatencyMs";

    /**
     * 记录一次成功的 LLM 调用
     *
     * @param userId           用户ID
     * @param promptTokens     输入 Token 数
     * @param completionTokens 输出 Token 数
     * @param latencyMs        调用延迟（毫秒）
     */
    public void recordSuccess(Long userId, int promptTokens, int completionTokens, long latencyMs) {
        String key = buildKey(userId);
        // Hash 累计：totalCalls / successCalls / 各类 Token / 延迟，写完刷新 TTL（7 天）
        redisUtil.hashIncrement(key, FIELD_TOTAL_CALLS, 1);
        redisUtil.hashIncrement(key, FIELD_SUCCESS_CALLS, 1);
        redisUtil.hashIncrement(key, FIELD_TOTAL_PROMPT_TOKENS, promptTokens);
        redisUtil.hashIncrement(key, FIELD_TOTAL_COMPLETION_TOKENS, completionTokens);
        redisUtil.hashIncrement(key, FIELD_TOTAL_LATENCY_MS, latencyMs);
        redisUtil.expire(key, Duration.ofDays(KEY_TTL_DAYS));
    }

    /**
     * 记录一次失败的 LLM 调用
     *
     * @param userId    用户ID
     * @param latencyMs 调用延迟（毫秒）
     */
    public void recordFailure(Long userId, long latencyMs) {
        String key = buildKey(userId);
        redisUtil.hashIncrement(key, FIELD_TOTAL_CALLS, 1);
        redisUtil.hashIncrement(key, FIELD_FAIL_CALLS, 1);
        redisUtil.hashIncrement(key, FIELD_TOTAL_LATENCY_MS, latencyMs);
        redisUtil.expire(key, Duration.ofDays(KEY_TTL_DAYS));
    }

    /**
     * 获取用户当日 LLM 调用统计
     *
     * @param userId 用户ID
     * @return 统计信息
     */
    public Map<String, Object> getStats(Long userId) {
        String key = buildKey(userId);
        Map<String, String> entries = redisUtil.hashEntries(key);

        Map<String, Object> stats = new HashMap<>();
        long totalCalls = parseLong(entries.get(FIELD_TOTAL_CALLS));
        stats.put("totalCalls", totalCalls);
        stats.put("successCalls", parseLong(entries.get(FIELD_SUCCESS_CALLS)));
        stats.put("failCalls", parseLong(entries.get(FIELD_FAIL_CALLS)));
        stats.put("totalPromptTokens", parseLong(entries.get(FIELD_TOTAL_PROMPT_TOKENS)));
        stats.put("totalCompletionTokens", parseLong(entries.get(FIELD_TOTAL_COMPLETION_TOKENS)));

        long totalLatency = parseLong(entries.get(FIELD_TOTAL_LATENCY_MS));
        stats.put("avgLatencyMs", totalCalls > 0 ? totalLatency / totalCalls : 0);
        stats.put("date", LocalDate.now().toString());

        return stats;
    }

    private String buildKey(Long userId) {
        return KEY_PREFIX + "user:" + userId + ":" + LocalDate.now();
    }

    private long parseLong(String value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
