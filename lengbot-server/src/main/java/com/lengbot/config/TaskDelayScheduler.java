package com.lengbot.config;

import com.lengbot.entity.Task;
import com.lengbot.enums.TaskStatus;
import com.lengbot.task.TaskQueueService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 延迟队列扫描调度器
 * <p>周期扫描 Redis ZSet（lengbot:task:zset:delay）中已到期的任务，
 * 用 ZREM 原子抢占（多实例互斥），抢占成功的实例负责把任务重新 XADD 到主队列。
 *
 * <p>典型场景：RetryableTaskException 触发后，任务进延迟队列退避等待重试。
 *
 * @author lw
 * @since 2026-07-18
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class TaskDelayScheduler {

    private final TaskQueueService taskQueueService;
    private final com.lengbot.service.TaskService taskService;

    /** 扫描周期（秒），默认 1 秒，保证退避到期后能在 1 秒内重新入队 */
    @Value("${lengbot.task.delay.interval-seconds:1}")
    private long intervalSeconds;

    /** 单次扫描上限 */
    @Value("${lengbot.task.delay.batch-size:100}")
    private int batchSize;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-delay-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::scanDue, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("[TaskDelay] 启动, interval={}s", intervalSeconds);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 扫描到期延迟任务：ZREM 原子抢占 → 加载 task → 状态校验 → XADD 主队列
     * <p>包级可见以便单测直接调用，绕过定时器</p>
     */
    void scanDue() {
        try {
            long now = System.currentTimeMillis();
            List<Long> dueIds = taskQueueService.scanDueDelayed(now, batchSize);
            if (dueIds.isEmpty()) {
                return;
            }

            int reEnqueued = 0;
            int skipped = 0;
            for (Long taskId : dueIds) {
                // 1. ZREM 抢占：返回 false 表示已被其他实例抢先，跳过
                if (!taskQueueService.removeDelayed(taskId)) {
                    skipped++;
                    continue;
                }

                try {
                    // 2. 加载任务并校验状态：仅 PENDING_RETRY 才重投
                    Task task = taskService.getById(taskId);
                    if (task == null) {
                        log.warn("[TaskDelay] 任务不存在, taskId={}", taskId);
                        continue;
                    }
                    if (task.getStatus() != TaskStatus.PENDING_RETRY) {
                        // 状态已变更（被取消/手动重投/重复消费），跳过
                        log.info("[TaskDelay] 任务状态非 PENDING_RETRY，跳过, taskId={}, status={}",
                                taskId, task.getStatus());
                        continue;
                    }

                    // 3. XADD 重新入队主 Stream
                    String streamId = taskQueueService.enqueue(task);
                    taskService.lambdaUpdate()
                            .eq(Task::getId, taskId)
                            .set(Task::getStatus, TaskStatus.PENDING)
                            .set(Task::getStreamId, streamId)
                            .set(Task::getNextRetryAt, null)
                            .update();
                    reEnqueued++;
                    log.info("[TaskDelay] 延迟任务到期重投, taskId={}, attempts={}, streamId={}",
                            taskId, task.getAttempts(), streamId);
                } catch (Exception e) {
                    log.error("[TaskDelay] 重投失败, taskId={}", taskId, e);
                    // 失败时把任务放回延迟队列（5 秒后再试），避免丢失
                    try {
                        taskQueueService.enqueueDelayed(fallbackTask(taskId), now + 5_000L);
                    } catch (Exception ignored) {
                        // 放不回去就交由人工介入
                    }
                }
            }

            if (reEnqueued > 0 || skipped > 0) {
                log.info("[TaskDelay] 扫描完成, due={}, reEnqueued={}, skipped={}", dueIds.size(), reEnqueued, skipped);
            }
        } catch (Exception e) {
            log.warn("[TaskDelay] 周期任务异常: {}", e.getMessage(), e);
        }
    }

    /** 构造一个最小 Task 用于 enqueueDelayed 兜底放回 */
    private Task fallbackTask(Long taskId) {
        Task t = new Task();
        t.setId(taskId);
        return t;
    }
}
