package com.lengbot.task.impl;

import com.lengbot.entity.Task;
import com.lengbot.enums.TaskType;
import com.lengbot.task.ProgressSnapshot;
import com.lengbot.task.StaleMessage;
import com.lengbot.task.TaskMessage;
import com.lengbot.task.TaskQueueService;
import org.springframework.util.CollectionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 任务队列服务实现：基于 Redis Stream（消费者组） + ZSet（延迟） + Hash（进度）
 *
 * @author finch
 * @since 2026-07-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskQueueServiceImpl implements TaskQueueService {

    private final StringRedisTemplate redis;

    /** 主队列 Stream key */
    private static final String KEY_MAIN_STREAM = "lengbot:task:stream:main";
    /** 死信 Stream key */
    private static final String KEY_DEAD_LETTER_STREAM = "lengbot:task:stream:deadletter";
    /** 延迟队列 ZSet key（score=触发时间戳毫秒） */
    private static final String KEY_DELAY_ZSET = "lengbot:task:zset:delay";
    /** 取消信号 key 前缀（沿用 RedisUtil 既有命名） */
    private static final String KEY_CANCEL_PREFIX = "lengbot:task:cancel:";
    /** 进度 Hash key 前缀 */
    private static final String KEY_PROGRESS_PREFIX = "lengbot:task:progress:";

    /** 默认消费组（短任务） */
    private static final String GROUP_DEFAULT = "cg:default";
    /** 重型消费组（长任务：图谱抽取、问答对生成） */
    private static final String GROUP_HEAVY = "cg:heavy";

    /** 进度 Hash TTL（秒）：1 小时，过期自动清理 */
    private static final long PROGRESS_TTL_SECONDS = 3_600L;
    /** 取消信号 TTL（秒）：1 小时，与既有 RedisUtil 保持一致 */
    private static final long CANCEL_TTL_SECONDS = 3_600L;

    /** 消息体字段名 */
    private static final String FIELD_TASK_ID = "task_id";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_TS = "ts";
    private static final String FIELD_V = "v";
    private static final String FIELD_ATTEMPTS = "attempts";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_ORIGINAL_ID = "original_id";
    private static final String FIELD_FAILED_AT = "failed_at";

    /** 消息体 schema 版本 */
    private static final String SCHEMA_VERSION = "1";

    /** Hash 字段名（进度） */
    private static final String FIELD_PROGRESS = "progress";
    private static final String FIELD_MESSAGE = "message";

    @Override
    public String enqueue(Task task) {
        // XADD main * task_id type ts v attempts
        Map<String, String> fields = baseFields(task);
        RecordId recordId = redis.opsForStream()
                .add(StreamRecords.string(fields).withStreamKey(KEY_MAIN_STREAM));
        String streamId = recordId.getValue();
        log.debug("[TaskQueue] XADD main, taskId={}, streamId={}", task.getId(), streamId);
        return streamId;
    }

    @Override
    public void enqueueDelayed(Task task, long delayAt) {
        // ZADD delay delayAt taskId
        redis.opsForZSet().add(KEY_DELAY_ZSET, task.getId().toString(), delayAt);
        log.debug("[TaskQueue] ZADD delay, taskId={}, delayAt={}", task.getId(), delayAt);
    }

    @Override
    public void publishCancel(Long taskId) {
        redis.opsForValue().set(KEY_CANCEL_PREFIX + taskId, "1", CANCEL_TTL_SECONDS, TimeUnit.SECONDS);
        log.debug("[TaskQueue] SET cancel, taskId={}", taskId);
    }

    @Override
    public boolean isCancelled(Long taskId) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_CANCEL_PREFIX + taskId));
    }

    @Override
    public void clearCancel(Long taskId) {
        redis.delete(KEY_CANCEL_PREFIX + taskId);
    }

    @Override
    public void reportProgress(Long taskId, int progress, String message) {
        // 进度 Hash 写入 + 刷新 TTL，保证前端毫秒级可读
        String key = KEY_PROGRESS_PREFIX + taskId;
        Map<String, String> fields = new LinkedHashMap<>(3);
        fields.put(FIELD_PROGRESS, String.valueOf(progress));
        fields.put(FIELD_MESSAGE, message == null ? "" : message);
        fields.put(FIELD_TS, String.valueOf(System.currentTimeMillis()));
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, PROGRESS_TTL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public ProgressSnapshot getProgress(Long taskId) {
        String key = KEY_PROGRESS_PREFIX + taskId;
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        if (CollectionUtils.isEmpty(raw)) {
            return null;
        }
        ProgressSnapshot snapshot = new ProgressSnapshot();
        Object p = raw.get(FIELD_PROGRESS);
        Object m = raw.get(FIELD_MESSAGE);
        Object t = raw.get(FIELD_TS);
        if (p != null) {
            try {
                snapshot.setProgress(Integer.parseInt(p.toString()));
            } catch (NumberFormatException ignored) {
                snapshot.setProgress(0);
            }
        }
        snapshot.setMessage(m == null ? "" : m.toString());
        if (t != null) {
            try {
                snapshot.setTs(Long.parseLong(t.toString()));
            } catch (NumberFormatException ignored) {
                snapshot.setTs(0L);
            }
        }
        return snapshot;
    }

    @Override
    public void ack(String streamId) {
        // XACK main cg:default <streamId>  —— 默认组与重型组都在同一个 main stream 上
        // 这里统一对两个组都尝试 ACK（XACK 对未消费的组返回 0，无副作用）
        RecordId recordId = RecordId.of(streamId);
        redis.opsForStream().acknowledge(KEY_MAIN_STREAM, GROUP_DEFAULT, recordId);
        redis.opsForStream().acknowledge(KEY_MAIN_STREAM, GROUP_HEAVY, recordId);
        log.debug("[TaskQueue] XACK main, streamId={}", streamId);
    }

    @Override
    public void sendToDeadLetter(Task task, String originalStreamId, String error) {
        // XADD deadletter * task_id type error attempts original_id failed_at ts v
        Map<String, String> fields = new LinkedHashMap<>(8);
        fields.put(FIELD_TASK_ID, String.valueOf(task.getId()));
        fields.put(FIELD_TYPE, task.getType() == null ? "" : task.getType().name());
        fields.put(FIELD_ERROR, error == null ? "" : error);
        fields.put(FIELD_ATTEMPTS, String.valueOf(task.getAttempts() == null ? 0 : task.getAttempts()));
        fields.put(FIELD_ORIGINAL_ID, originalStreamId == null ? "" : originalStreamId);
        fields.put(FIELD_FAILED_AT, String.valueOf(System.currentTimeMillis()));
        fields.put(FIELD_TS, String.valueOf(System.currentTimeMillis()));
        fields.put(FIELD_V, SCHEMA_VERSION);

        redis.opsForStream()
                .add(StreamRecords.string(fields).withStreamKey(KEY_DEAD_LETTER_STREAM));
        log.warn("[TaskQueue] XADD deadletter, taskId={}, originalStreamId={}, error={}",
                task.getId(), originalStreamId, error);
    }

    @Override
    public List<TaskMessage> readMessages(TaskType.Group group, String consumerName, int count, Duration block) {
        // XREADGROUP GROUP <group> <consumer> COUNT n BLOCK ms STREAMS main >
        Consumer consumer = Consumer.from(groupName(group), consumerName);
        StreamReadOptions options = StreamReadOptions.empty().count(count);
        if (block != null && !block.isZero() && !block.isNegative()) {
            options = options.block(block);
        }
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                consumer,
                options,
                StreamOffset.create(KEY_MAIN_STREAM, ReadOffset.lastConsumed()));

        return toMessages(records);
    }

    @Override
    public List<TaskMessage> readPending(TaskType.Group group, String consumerName, int count) {
        // XREADGROUP GROUP <group> <consumer> COUNT n STREAMS main 0
        Consumer consumer = Consumer.from(groupName(group), consumerName);
        StreamReadOptions options = StreamReadOptions.empty().count(count);
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                consumer,
                options,
                StreamOffset.create(KEY_MAIN_STREAM, ReadOffset.from("0")));

        return toMessages(records);
    }

    /** MapRecord 列表 → TaskMessage 列表 */
    private List<TaskMessage> toMessages(List<MapRecord<String, Object, Object>> records) {
        if (CollectionUtils.isEmpty(records)) {
            return List.of();
        }
        List<TaskMessage> messages = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> rec : records) {
            TaskMessage msg = parseMessage(rec);
            if (msg != null) {
                messages.add(msg);
            }
        }
        return messages;
    }

    @Override
    public List<StaleMessage> scanStale(TaskType.Group group, Duration idleThreshold, int count) {
        // XPENDING main <group> - + <count> —— Spring Data Redis 提供封装版
        PendingMessages pending = redis.opsForStream().pending(
                KEY_MAIN_STREAM,
                groupName(group),
                Range.unbounded(),
                (long) count);

        if (pending == null || pending.isEmpty()) {
            return List.of();
        }

        long thresholdMs = idleThreshold == null ? 0 : idleThreshold.toMillis();
        List<StaleMessage> stale = new ArrayList<>();
        for (PendingMessage pm : pending) {
            long idleMs = pm.getElapsedTimeSinceLastDelivery().toMillis();
            if (idleMs >= thresholdMs) {
                stale.add(new StaleMessage(
                        pm.getIdAsString(),
                        pm.getConsumerName(),
                        idleMs,
                        pm.getTotalDeliveryCount()));
            }
        }
        return stale;
    }

    @Override
    public int claimMessages(TaskType.Group group, String consumerName, Duration minIdle, List<String> streamIds) {
        if (CollectionUtils.isEmpty(streamIds)) {
            return 0;
        }
        // XCLAIM main <group> <consumer> <minIdleMs> <id1> <id2> ...
        RecordId[] ids = streamIds.stream()
                .map(RecordId::of)
                .toArray(RecordId[]::new);

        List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                KEY_MAIN_STREAM,
                groupName(group),
                consumerName,
                minIdle == null ? Duration.ZERO : minIdle,
                ids);

        int n = claimed == null ? 0 : claimed.size();
        if (n > 0) {
            log.info("[TaskQueue] XCLAIM main, group={}, consumer={}, claimed={}",
                    groupName(group), consumerName, n);
        }
        return n;
    }

    @Override
    public List<Long> scanDueDelayed(long nowTimestamp, int count) {
        // ZRANGEBYSCORE delay 0 now LIMIT 0 count
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                .rangeByScoreWithScores(KEY_DELAY_ZSET, 0, nowTimestamp, 0, count);

        if (CollectionUtils.isEmpty(tuples)) {
            return List.of();
        }
        return tuples.stream()
                .map(t -> t.getValue())
                .filter(v -> v != null)
                .map(v -> {
                    try {
                        return Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        log.warn("[TaskQueue] 延迟队列中存在非法 taskId, value={}", v);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public boolean removeDelayed(Long taskId) {
        // ZREM delay taskId —— 返回值 > 0 表示当前实例抢到处理权
        Long removed = redis.opsForZSet().remove(KEY_DELAY_ZSET, taskId.toString());
        return removed != null && removed > 0;
    }

    @Override
    public void ensureGroups() {
        // XGROUP CREATE main cg:default $ MKSTREAM  —— 幂等
        // 忽略 BUSYGROUP 错误（组已存在）
        tryCreateGroup(KEY_MAIN_STREAM, GROUP_DEFAULT);
        tryCreateGroup(KEY_MAIN_STREAM, GROUP_HEAVY);
        // 死信 Stream 不需要消费组（人工运维读取）
    }

    @Override
    public QueueMetrics snapshotMetrics() {
        // 采集队列关键计数，单次接口调用对 Redis 压力可控（5 个 O(1)/O(logN) 命令）
        Long mainLen = redis.opsForStream().size(KEY_MAIN_STREAM);
        Long deadLetterLen = redis.opsForStream().size(KEY_DEAD_LETTER_STREAM);
        Long defaultPending = pendingCount(GROUP_DEFAULT);
        Long heavyPending = pendingCount(GROUP_HEAVY);
        Long delaySize = redis.opsForZSet().zCard(KEY_DELAY_ZSET);
        return new QueueMetrics(
                mainLen == null ? 0 : mainLen,
                deadLetterLen == null ? 0 : deadLetterLen,
                defaultPending == null ? 0 : defaultPending,
                heavyPending == null ? 0 : heavyPending,
                delaySize == null ? 0 : delaySize);
    }

    /** 取指定消费组 PEL 中未 ACK 消息数（XPENDING - + 1 的 count 部分） */
    private Long pendingCount(String group) {
        try {
            org.springframework.data.redis.connection.stream.PendingMessagesSummary summary =
                    redis.opsForStream().pending(KEY_MAIN_STREAM, group);
            return summary == null ? 0L : summary.getTotalPendingMessages();
        } catch (Exception e) {
            // 组尚未创建或 Redis 异常时返回 0，不影响监控端点
            log.warn("[TaskQueue] 读取 PEL 失败, group={}, error={}", group, e.getMessage());
            return 0L;
        }
    }

    // ──────────────────────────────────────────
    //  内部工具方法
    // ──────────────────────────────────────────

    /** 组名映射：DEFAULT -> cg:default, HEAVY -> cg:heavy */
    private String groupName(TaskType.Group group) {
        return switch (group) {
            case HEAVY -> GROUP_HEAVY;
            default -> GROUP_DEFAULT;
        };
    }

    /** 基础消息字段（除 attempts 外其他都必填） */
    private Map<String, String> baseFields(Task task) {
        Map<String, String> fields = new LinkedHashMap<>(5);
        fields.put(FIELD_TASK_ID, String.valueOf(task.getId()));
        fields.put(FIELD_TYPE, task.getType() == null ? "" : task.getType().name());
        fields.put(FIELD_TS, String.valueOf(System.currentTimeMillis()));
        fields.put(FIELD_V, SCHEMA_VERSION);
        fields.put(FIELD_ATTEMPTS, String.valueOf(task.getAttempts() == null ? 0 : task.getAttempts()));
        return fields;
    }

    /** 幂等创建消费组：组已存在时静默忽略 */
    private void tryCreateGroup(String streamKey, String group) {
        try {
            redis.opsForStream().createGroup(streamKey, ReadOffset.from("0"), group);
            log.info("[TaskQueue] XGROUP CREATE 成功, stream={}, group={}", streamKey, group);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            // BUSYGROUP 表示组已存在，幂等
            if (msg.contains("BUSYGROUP") || msg.contains("already exists")) {
                log.info("[TaskQueue] 消费组已存在, stream={}, group={}", streamKey, group);
            } else {
                log.error("[TaskQueue] XGROUP CREATE 失败, stream={}, group={}, error={}",
                        streamKey, group, msg, e);
            }
        }
    }

    /** 解析 MapRecord 为 TaskMessage（异常返回 null） */
    private TaskMessage parseMessage(MapRecord<String, Object, Object> rec) {
        Map<Object, Object> v = rec.getValue();
        if (CollectionUtils.isEmpty(v)) {
            return null;
        }
        Object taskIdRaw = v.get(FIELD_TASK_ID);
        if (taskIdRaw == null) {
            return null;
        }
        try {
            Long taskId = Long.parseLong(taskIdRaw.toString());
            String type = v.getOrDefault(FIELD_TYPE, "").toString();
            int attempts = 0;
            Object a = v.get(FIELD_ATTEMPTS);
            if (a != null) {
                attempts = Integer.parseInt(a.toString());
            }
            return new TaskMessage(rec.getId().getValue(), taskId, type, attempts);
        } catch (NumberFormatException e) {
            log.warn("[TaskQueue] 解析任务消息失败, streamId={}, value={}", rec.getId(), v);
            return null;
        }
    }
}
