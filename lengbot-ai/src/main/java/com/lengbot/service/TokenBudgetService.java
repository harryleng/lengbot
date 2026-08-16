package com.lengbot.service;

import com.lengbot.common.BizException;
import com.lengbot.entity.User;
import com.lengbot.enums.ErrorCode;
import com.lengbot.mapper.LlmTraceMapper;
import com.lengbot.mapper.UserMapper;
import com.lengbot.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Token 预算控制服务
 * <p>基于 Redis 实现用户级和全局级日 Token 限额，防止滥用</p>
 * <p>Redis key 格式：lengbot:token_budget:{scope}:{id}:{date}</p>
 *
 * @author finch
 * @since 2026-06-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBudgetService {

    private final StringRedisTemplate stringRedisTemplate;
    private final SystemConfigService systemConfigService;
    private final UserMapper userMapper;
    private final LlmTraceMapper llmTraceMapper;

    private static final String KEY_PREFIX = "lengbot:token_budget:";
    private static final long KEY_TTL_HOURS = 25;

    /** 默认用户日限额 */
    private static final long DEFAULT_USER_DAILY_LIMIT = 1_000_000L;
    /** 默认全局日限额 */
    private static final long DEFAULT_GLOBAL_DAILY_LIMIT = 10_000_000L;
    /** 默认单次调用上限 */
    private static final int DEFAULT_SINGLE_CALL_LIMIT = 32_000;

    // 排行榜时间范围标识（前端 radio button value 与此对齐）
    private static final String RANGE_TODAY = "today";
    private static final String RANGE_7D = "7d";
    private static final String RANGE_14D = "14d";
    private static final String RANGE_30D = "30d";

    /**
     * 检查用户 Token 预算，超限则抛出异常
     *
     * @param userId          用户ID
     * @param estimatedTokens 预估消耗量
     */
    public void checkBudget(Long userId, int estimatedTokens) {
        // 1. 单次调用上限
        int singleLimit = getSingleCallLimit();
        if (estimatedTokens > singleLimit) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "单次调用 Token 超限: 预估 " + estimatedTokens + ", 上限 " + singleLimit);
        }

        // 2. 用户日限额
        String userKey = KEY_PREFIX + "user:" + userId + ":" + LocalDate.now();
        Long userUsed = stringRedisTemplate.opsForValue().get(userKey) != null
                ? Long.parseLong(stringRedisTemplate.opsForValue().get(userKey)) : 0L;
        long userLimit = getUserDailyLimit();
        if (userUsed + estimatedTokens > userLimit) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "今日 Token 预算已用尽: 已用 " + userUsed + ", 限额 " + userLimit);
        }

        // 3. 全局日限额
        String globalKey = KEY_PREFIX + "global:" + LocalDate.now();
        Long globalUsed = stringRedisTemplate.opsForValue().get(globalKey) != null
                ? Long.parseLong(stringRedisTemplate.opsForValue().get(globalKey)) : 0L;
        long globalLimit = getGlobalDailyLimit();
        if (globalUsed + estimatedTokens > globalLimit) {
            throw new BizException(ErrorCode.BAD_REQUEST, "系统今日 Token 预算已用尽，请明天再试");
        }
    }

    /**
     * 记录实际 Token 消耗（调用完成后）
     *
     * @param userId          用户ID
     * @param promptTokens    输入 Token 数
     * @param completionTokens 输出 Token 数
     */
    public void recordUsage(Long userId, int promptTokens, int completionTokens) {
        int totalTokens = promptTokens + completionTokens;
        String today = LocalDate.now().toString();

        // 1. 用户维度累加
        String userKey = KEY_PREFIX + "user:" + userId + ":" + today;
        stringRedisTemplate.opsForValue().increment(userKey, totalTokens);
        stringRedisTemplate.expire(userKey, Duration.ofHours(KEY_TTL_HOURS));

        // 2. 全局维度累加
        String globalKey = KEY_PREFIX + "global:" + today;
        stringRedisTemplate.opsForValue().increment(globalKey, totalTokens);
        stringRedisTemplate.expire(globalKey, Duration.ofHours(KEY_TTL_HOURS));

        log.debug("[TokenBudget] userId={}, prompt={}, completion={}, total={}", userId, promptTokens, completionTokens, totalTokens);
    }

    /**
     * 获取用户当日 Token 使用统计
     *
     * @param userId 用户ID
     * @return 统计信息
     */
    public Map<String, Object> getUsageStats(Long userId) {
        String today = LocalDate.now().toString();
        String userKey = KEY_PREFIX + "user:" + userId + ":" + today;
        String globalKey = KEY_PREFIX + "global:" + today;

        Long userUsed = stringRedisTemplate.opsForValue().get(userKey) != null
                ? Long.parseLong(stringRedisTemplate.opsForValue().get(userKey)) : 0L;
        Long globalUsed = stringRedisTemplate.opsForValue().get(globalKey) != null
                ? Long.parseLong(stringRedisTemplate.opsForValue().get(globalKey)) : 0L;

        Map<String, Object> stats = new HashMap<>();
        stats.put("userUsed", userUsed);
        stats.put("userLimit", getUserDailyLimit());
        stats.put("globalUsed", globalUsed);
        stats.put("globalLimit", getGlobalDailyLimit());
        stats.put("date", today);
        return stats;
    }

    /**
     * 获取 Token 限额配置
     */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("userDailyLimit", getUserDailyLimit());
        config.put("globalDailyLimit", getGlobalDailyLimit());
        config.put("singleCallLimit", getSingleCallLimit());
        return config;
    }

    /**
     * 更新 Token 限额配置
     */
    public void updateConfig(Map<String, Object> config) {
        if (config.containsKey("userDailyLimit")) {
            systemConfigService.updateConfigValue("llm.token.user.dailyLimit",
                    String.valueOf(config.get("userDailyLimit")));
        }
        if (config.containsKey("globalDailyLimit")) {
            systemConfigService.updateConfigValue("llm.token.global.dailyLimit",
                    String.valueOf(config.get("globalDailyLimit")));
        }
        if (config.containsKey("singleCallLimit")) {
            systemConfigService.updateConfigValue("llm.token.singleCallLimit",
                    String.valueOf(config.get("singleCallLimit")));
        }
    }

    /**
     * 获取全局 Token 使用统计
     */
    public Map<String, Object> getGlobalStats() {
        String today = LocalDate.now().toString();
        String globalKey = KEY_PREFIX + "global:" + today;
        Long globalUsed = parseLong(stringRedisTemplate.opsForValue().get(globalKey));

        Map<String, Object> stats = new HashMap<>();
        stats.put("globalUsed", globalUsed);
        stats.put("globalLimit", getGlobalDailyLimit());
        stats.put("date", today);
        return stats;
    }

    /**
     * 获取用户 Token 消耗排行（当日 Top N）
     *
     * @param limit 返回条数
     * @return 排行列表，每项含 userId / username / avatar / usedTokens
     */
    public List<Map<String, Object>> getUserRanking(int limit) {
        String today = LocalDate.now().toString();
        String pattern = KEY_PREFIX + "user:*:" + today;

        // SCAN 扫描所有用户 key
        Set<String> keys = new HashSet<>();
        stringRedisTemplate.execute(connection -> {
            var cursor = connection.scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(pattern).count(1000).build());
            cursor.forEachRemaining(bytes -> keys.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
            return null;
        }, true);

        // 解析 userId → usedTokens，按消耗降序排列，取 Top N
        List<Map<String, Object>> ranking = keys.stream()
                .map(key -> {
                    String userId = key.replace(KEY_PREFIX + "user:", "").replace(":" + today, "");
                    long used = parseLong(stringRedisTemplate.opsForValue().get(key));
                    Map<String, Object> row = new HashMap<>();
                    row.put("userId", userId);
                    row.put("usedTokens", used);
                    return row;
                })
                .sorted((a, b) -> Long.compare((long) b.get("usedTokens"), (long) a.get("usedTokens")))
                .limit(limit)
                .collect(Collectors.toList());

        // 批量查询用户信息
        if (!ranking.isEmpty()) {
            List<Long> userIds = ranking.stream()
                    .map(r -> Long.parseLong((String) r.get("userId")))
                    .collect(Collectors.toList());
            Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                    .collect(Collectors.toMap(User::getId, u -> u));
            for (Map<String, Object> row : ranking) {
                Long uid = Long.parseLong((String) row.get("userId"));
                User user = userMap.get(uid);
                if (user != null) {
                    row.put("username", user.getNickname() != null ? user.getNickname() : user.getUsername());
                    row.put("avatar", user.getAvatar());
                }
            }
        }

        return ranking;
    }

    /**
     * 获取用户 Token 消耗排行（按时间范围分发：今日走 Redis 实时，其他走 DB 历史聚合）
     *
     * @param range 时间范围标识：today / 7d / 14d / 30d
     * @param limit 返回条数
     * @return 排行列表，字段同 {@link #getUserRanking(int)}（userId / username / avatar / usedTokens）
     */
    public List<Map<String, Object>> getUserRanking(String range, int limit) {
        // 1. 今日：复用 Redis 实时聚合（含正在进行的本次调用）
        if (range == null || RANGE_TODAY.equals(range)) {
            return getUserRanking(limit);
        }
        // 2. 历史范围：DB llm_trace 表聚合（自然日切：今天 + 前 N-1 天）
        LocalDateTime[] window = resolveRangeWindow(range);
        List<Map<String, Object>> rows = llmTraceMapper.aggregateUserTokens(window[0], window[1], limit);
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        // 3. 批量补 username/avatar（与 Redis 路径返回结构对齐，前端 a-table 复用渲染）
        // DB 返回行 key 为 snake_case（user_id / total_tokens），与 LlmTraceMapper.aggregateUserTokens 别名对齐
        List<Long> userIds = rows.stream()
                .map(r -> ((Number) r.get("user_id")).longValue())
                .collect(Collectors.toList());
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        List<Map<String, Object>> ranking = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long uid = ((Number) row.get("user_id")).longValue();
            Map<String, Object> r = new HashMap<>();
            r.put("userId", uid);
            // 数据库 SUM 返回 BigDecimal/Long，统一转 long 便于前端处理
            r.put("usedTokens", ((Number) row.get("total_tokens")).longValue());
            User user = userMap.get(uid);
            if (user != null) {
                r.put("username", user.getNickname() != null ? user.getNickname() : user.getUsername());
                r.put("avatar", user.getAvatar());
            }
            ranking.add(r);
        }
        return ranking;
    }

    /**
     * 个人页一次性返回本人 Token 用量（今日实时 + 近 7 天历史）
     *
     * @param userId 当前登录用户ID（Controller 从 StpUtil 取，前端不可传，防越权）
     * @return {userUsed, userLimit, last7dUsed, date}
     */
    public Map<String, Object> getMyUsage(Long userId) {
        // 1. 今日消耗 + 限额（Redis 实时 + system_config 全局值）
        Map<String, Object> today = getUsageStats(userId);
        // 2. 近 7 天累计（DB 历史聚合，自然日切）
        LocalDateTime[] window = resolveRangeWindow(RANGE_7D);
        long last7d = llmTraceMapper.sumUserTokens(userId, window[0], window[1]);
        // 3. 组装返回（保持字段顺序，前端按序展示）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userUsed", today.get("userUsed"));
        result.put("userLimit", today.get("userLimit"));
        result.put("last7dUsed", last7d);
        result.put("date", today.get("date"));
        return result;
    }

    /**
     * 解析自然日切时间窗口：end 统一为明天 0:00（不含），begin 为今天往前推 (days-1) 天的 0:00（含）
     * <p>"近 7 天" = 今天 + 前 6 天共 7 个自然日；"今日"特化为 [今天 0:00, 明天 0:00)</p>
     *
     * @param range today / 7d / 14d / 30d
     * @return [begin, end]，长度 2
     */
    private LocalDateTime[] resolveRangeWindow(String range) {
        int days;
        switch (range) {
            case RANGE_TODAY: days = 1; break;
            case RANGE_7D:    days = 7; break;
            case RANGE_14D:   days = 14; break;
            case RANGE_30D:   days = 30; break;
            default:
                throw new BizException(ErrorCode.BAD_REQUEST, "不支持的时间范围: " + range);
        }
        LocalDate today = LocalDate.now();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        LocalDateTime begin = today.minusDays(days - 1L).atStartOfDay();
        return new LocalDateTime[]{begin, end};
    }

    private long parseLong(String val) {
        if (val == null || val.isBlank()) return 0L;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private long getUserDailyLimit() {
        String val = systemConfigService.getConfigValue("llm.token.user.dailyLimit");
        if (val != null && !val.isBlank()) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_USER_DAILY_LIMIT;
    }

    private long getGlobalDailyLimit() {
        String val = systemConfigService.getConfigValue("llm.token.global.dailyLimit");
        if (val != null && !val.isBlank()) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_GLOBAL_DAILY_LIMIT;
    }

    private int getSingleCallLimit() {
        String val = systemConfigService.getConfigValue("llm.token.singleCallLimit");
        if (val != null && !val.isBlank()) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_SINGLE_CALL_LIMIT;
    }
}
