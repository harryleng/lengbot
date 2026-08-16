package com.lengbot.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 工具类
 *
 * @author finch
 * @since 2026-05-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisUtil {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String CANCEL_SIGNAL_PREFIX = "lengbot:task:cancel:";

    // ==================== 任务取消信号 ====================
    // 已迁移到 TaskQueueService.publishCancel / isCancelled / clearCancel。
    // 以下方法保留 @Deprecated 作为过渡，便于既有 Executor 平滑迁移；新代码一律走 TaskQueueService。

    /**
     * 设置任务取消信号（Redis key，比DB标记更快检测）
     *
     * @param taskId 任务ID
     * @deprecated 请改用 {@link com.lengbot.task.TaskQueueService#publishCancel}
     */
    @Deprecated
    public void setCancelSignal(Long taskId) {
        stringRedisTemplate.opsForValue().set(CANCEL_SIGNAL_PREFIX + taskId, "1", 1, TimeUnit.HOURS);
        log.debug("[Redis] 设置取消信号, taskId={}", taskId);
    }

    /**
     * 检查任务是否有取消信号
     *
     * @param taskId 任务ID
     * @return true=已请求取消
     * @deprecated 请改用 {@link com.lengbot.task.TaskQueueService#isCancelled}
     */
    @Deprecated
    public boolean hasCancelSignal(Long taskId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(CANCEL_SIGNAL_PREFIX + taskId));
    }

    /**
     * 清除任务取消信号
     *
     * @param taskId 任务ID
     * @deprecated 请改用 {@link com.lengbot.task.TaskQueueService#clearCancel}
     */
    @Deprecated
    public void clearCancelSignal(Long taskId) {
        stringRedisTemplate.delete(CANCEL_SIGNAL_PREFIX + taskId);
    }

    // ==================== 通用 String 操作 ====================

    /**
     * 设置缓存（带过期时间，秒）
     */
    public void set(String key, String value, long timeout) {
        stringRedisTemplate.opsForValue().set(key, value, timeout, TimeUnit.SECONDS);
    }

    /**
     * 设置缓存（永不过期）
     */
    public void set(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    /**
     * 获取缓存
     *
     * @return 缓存值，不存在返回 null
     */
    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 删除缓存
     */
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 判断缓存是否存在
     */
    public boolean exists(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    /**
     * 自增（key 不存在时自动从 0 开始）
     *
     * @return 自增后的值
     */
    public long increment(String key) {
        Long val = stringRedisTemplate.opsForValue().increment(key);
        return val != null ? val : 1L;
    }

    /**
     * 自增指定步长
     */
    public long increment(String key, long delta) {
        Long val = stringRedisTemplate.opsForValue().increment(key, delta);
        return val != null ? val : delta;
    }

    /**
     * 设置 key 的过期时间（秒）
     */
    public boolean expire(String key, long timeoutSeconds) {
        return Boolean.TRUE.equals(stringRedisTemplate.expire(key, timeoutSeconds, TimeUnit.SECONDS));
    }

    /**
     * 设置 key 的过期时间（Duration）
     */
    public boolean expire(String key, Duration timeout) {
        return Boolean.TRUE.equals(stringRedisTemplate.expire(key, timeout));
    }

    // ==================== 限流（固定窗口） ====================
    // 用于工具维度限流等场景，按 (维度, key) Redis 计数 + Lua 原子 INCR/EXPIRE

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;
    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        // 第一次进入窗口时设置过期；后续仅 INCR。返回当前累计计数。
        RATE_LIMIT_SCRIPT.setScriptText(
                "local c = redis.call('INCR', KEYS[1]) " +
                "if c == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end " +
                "return c"
        );
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    /**
     * 固定窗口限流原子计数：返回当前窗口内的累计调用次数
     * <p>第一次进入窗口（key 不存在）时同时设置 TTL，避免独立 EXPIRE 漏设导致 key 永驻。
     * 调用方拿到 count 后自行判定是否超过阈值。</p>
     *
     * @param key            限流 Key（建议已包含 userId/toolName/window 维度）
     * @param windowSeconds  窗口大小（秒）
     * @return 当前窗口内的累计计数（含本次）
     */
    public long rateLimitIncrement(String key, long windowSeconds) {
        Long val = stringRedisTemplate.execute(RATE_LIMIT_SCRIPT, List.of(key), String.valueOf(windowSeconds));
        return val != null ? val : 1L;
    }

    // ==================== Hash 操作 ====================
    // 用于 API Key 用量累计、LLM 调用统计、批量计数等场景，避免每请求一次 DB UPDATE

    /**
     * 写入 Hash 单个字段（覆盖）
     */
    public void hashPut(String key, String field, String value) {
        stringRedisTemplate.opsForHash().put(key, field, value);
    }

    /**
     * 读取 Hash 单个字段
     *
     * @return 字段值；key/field 不存在返回 null
     */
    public String hashGet(String key, String field) {
        Object val = stringRedisTemplate.opsForHash().get(key, field);
        return val != null ? String.valueOf(val) : null;
    }

    /**
     * Hash 字段自增
     *
     * @return 自增后的值
     */
    public long hashIncrement(String key, String field, long delta) {
        Long val = stringRedisTemplate.opsForHash().increment(key, field, delta);
        return val != null ? val : delta;
    }

    /**
     * 获取 Hash 全部字段（快照）
     *
     * @return field → value 映射；key 不存在返回空 Map
     */
    public Map<String, String> hashEntries(String key) {
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(key);
        if (raw == null || raw.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>(raw.size());
        for (Map.Entry<Object, Object> e : raw.entrySet()) {
            result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return result;
    }

    /**
     * 删除 Hash 中的字段
     *
     * @return 实际删除数量
     */
    public long hashDelete(String key, String... fields) {
        if (fields == null || fields.length == 0) {
            return 0L;
        }
        Object[] objFields = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            objFields[i] = fields[i];
        }
        Long val = stringRedisTemplate.opsForHash().delete(key, objFields);
        return val != null ? val : 0L;
    }

    // ==================== ZSet 操作 ====================
    // 用于排行榜、按时间窗口取 Top N、延迟队列等场景

    /**
     * 向 ZSet 添加成员（分数覆盖）
     *
     * @return true=新增；false=已存在仅更新分数
     */
    public boolean zAdd(String key, String member, double score) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForZSet().add(key, member, score));
    }

    /**
     * ZSet 成员分数自增
     *
     * @return 自增后的分数
     */
    public double zIncrementScore(String key, String member, double delta) {
        Double val = stringRedisTemplate.opsForZSet().incrementScore(key, member, delta);
        return val != null ? val : delta;
    }

    /**
     * 按分数区间取成员（升序）
     *
     * @param min 分数下限（含）
     * @param max 分数上限（含）
     * @return 成员集合，含分数
     */
    public Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> zRangeByScore(
            String key, double min, double max) {
        return stringRedisTemplate.opsForZSet().rangeByScoreWithScores(key, min, max);
    }

    /**
     * 按分数区间取成员（降序，取 Top N）
     */
    public Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> zReverseRangeByScore(
            String key, double min, double max, long offset, long count) {
        return stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, min, max, offset, count);
    }

    /**
     * 移除 ZSet 中的成员
     *
     * @return 实际删除数量
     */
    public long zRemove(String key, String... members) {
        Long val = stringRedisTemplate.opsForZSet().remove(key, (Object[]) members);
        return val != null ? val : 0L;
    }

    // ==================== Pub/Sub ====================

    /**
     * 向指定频道发布消息（多实例广播缓存失效等场景）
     */
    public void convertAndSend(String channel, String message) {
        stringRedisTemplate.convertAndSend(channel, message);
        log.debug("[Redis] 发布消息: channel={}, payload={}", channel, message);
    }
}

