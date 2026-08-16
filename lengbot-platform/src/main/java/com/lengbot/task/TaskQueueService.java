package com.lengbot.task;

import com.lengbot.entity.Task;
import com.lengbot.enums.TaskType;

import java.time.Duration;
import java.util.List;

/**
 * 任务队列服务：封装所有 Redis Stream / ZSet / Hash 操作
 * <p>业务层（TaskService、TaskExecutor）与消费层（TaskConsumer）均通过此接口操作队列，
 * 不直接访问 RedisTemplate，保证 Stream key / 消费组命名的统一。
 *
 * @author finch
 * @since 2026-07-18
 */
public interface TaskQueueService {

    /**
     * 投递任务到主 Stream（XADD）
     *
     * @param task 任务实体（status=PENDING）
     * @return Stream 消息 ID
     */
    String enqueue(Task task);

    /**
     * 投递延迟任务到 ZSet（ZADD score=delayAt）
     *
     * @param task    任务实体（status=PENDING_RETRY）
     * @param delayAt 触发时间戳（毫秒）
     */
    void enqueueDelayed(Task task, long delayAt);

    /**
     * 发布取消信号（SET cancel key + TTL）
     *
     * @param taskId 任务 ID
     */
    void publishCancel(Long taskId);

    /**
     * 检查任务是否已被取消
     *
     * @param taskId 任务 ID
     */
    boolean isCancelled(Long taskId);

    /**
     * 清除取消信号
     *
     * @param taskId 任务 ID
     */
    void clearCancel(Long taskId);

    /**
     * 上报进度（写 Redis Hash，TTL 1h），保证前端毫秒级可读
     *
     * @param taskId   任务 ID
     * @param progress 进度 0-100
     * @param message  状态消息
     */
    void reportProgress(Long taskId, int progress, String message);

    /**
     * 读取进度快照（Hash 直读；不存在返回 null）
     *
     * @param taskId 任务 ID
     */
    ProgressSnapshot getProgress(Long taskId);

    /**
     * ACK 主队列消息（XACK）
     *
     * @param streamId Stream 消息 ID
     */
    void ack(String streamId);

    /**
     * 投递到死信 Stream（XADD deadletter），并标记 task.dead_letter=1
     *
     * @param task             任务实体
     * @param originalStreamId 原主队列 Stream 消息 ID
     * @param error            错误信息
     */
    void sendToDeadLetter(Task task, String originalStreamId, String error);

    /**
     * 消费主队列（XREADGROUP GROUP group consumer &gt;）
     *
     * @param group        消费组
     * @param consumerName 消费者名（worker 唯一标识）
     * @param count        单次拉取条数
     * @param block        阻塞时长（无消息时等待）
     * @return 任务消息列表；空列表表示无消息
     */
    List<TaskMessage> readMessages(TaskType.Group group, String consumerName, int count, Duration block);

    /**
     * 拉取本组 PEL 中尚未 ACK 的消息（XREADGROUP ID 0）
     * <p>用于兜底：worker 拉到消息但崩溃前未 ACK，下个 worker 的此调用会重新拿到。
     * 同组所有 worker 共享 consumer name，PEL 是共享的。
     *
     * @param group        消费组
     * @param consumerName 消费者名（必须与 readMessages 一致，才能看到自己 PEL）
     * @param count        单次拉取条数
     */
    List<TaskMessage> readPending(TaskType.Group group, String consumerName, int count);

    /**
     * 扫描 PEL 中超时未 ACK 的僵尸消息（XPENDING）
     *
     * @param group          消费组
     * @param idleThreshold  最小空闲时长阈值
     * @param count          单次扫描上限
     */
    List<StaleMessage> scanStale(TaskType.Group group, Duration idleThreshold, int count);

    /**
     * 转移僵尸消息到当前消费者（XCLAIM）
     *
     * @param group        消费组
     * @param consumerName 新消费者名
     * @param minIdle      最小空闲时长（必须满足才会被 claim）
     * @param streamIds    待转移的 Stream 消息 ID 列表
     * @return 实际转移的条数
     */
    int claimMessages(TaskType.Group group, String consumerName, Duration minIdle, List<String> streamIds);

    /**
     * 扫描延迟队列中已到期的任务（ZRANGEBYSCORE）
     *
     * @param nowTimestamp 当前时间戳（毫秒）
     * @param count        单次扫描上限
     * @return 已到期的 taskId 列表
     */
    List<Long> scanDueDelayed(long nowTimestamp, int count);

    /**
     * 从延迟队列移除（ZREM）
     *
     * @param taskId 任务 ID
     * @return 是否实际移除（false 表示已被其他实例抢先处理）
     */
    boolean removeDelayed(Long taskId);

    /**
     * 初始化消费组（XGROUP CREATE ... MKSTREAM），幂等
     */
    void ensureGroups();

    /**
     * 采集队列运行时指标，供监控端点暴露
     *
     * @return 关键计数快照（main/deadletter XLEN、PEL 大小、延迟 ZSet 大小）
     */
    QueueMetrics snapshotMetrics();

    /**
     * 队列运行时指标快照
     */
    record QueueMetrics(
            long mainStreamLen,
            long deadLetterStreamLen,
            long defaultPendingEntries,
            long heavyPendingEntries,
            long delayedSize
    ) {
    }
}
