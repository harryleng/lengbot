package com.lengbot.config;

import com.lengbot.enums.TaskType;
import com.lengbot.task.StaleMessage;
import com.lengbot.task.TaskQueueService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 任务队列监控调度器
 * <p>周期性扫描两个消费组的 PEL（pending entries list），对空闲超过阈值的"僵尸消息"
 * 输出告警日志，便于运维介入。
 *
 * <p>说明：本调度器只做监控告警，不主动 XCLAIM 转移消息。
 * 崩溃恢复依赖 {@link TaskConsumerConfig} 的「共享 consumer name + ID-0 双拉」机制：
 * 同组所有 worker 共享一个虚拟 consumer，PEL 也是共享的，任一 worker 拉到未 ACK 的消息后
 * 下个 worker 的 ID-0 拉取会自然接管。
 *
 * <p>XINFO CONSUMERS 只能看到聚合后一条记录，因此 XPENDING 是观察堆积的主要入口。
 *
 * @author lw
 * @since 2026-07-18
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class TaskReclaimScheduler {

    private final TaskQueueService taskQueueService;

    /** 扫描周期（秒） */
    @Value("${lengbot.task.reclaim.interval-seconds:30}")
    private long intervalSeconds;

    /** 僵尸消息判定阈值：空闲超过此时间视为消费异常 */
    @Value("${lengbot.task.reclaim.stale-threshold-minutes:5}")
    private long staleThresholdMinutes;

    /** 单次扫描上限 */
    @Value("${lengbot.task.reclaim.batch-size:100}")
    private int batchSize;

    /** 阈值告警去重：上次告警时间，避免日志洪泛 */
    private volatile long lastWarnAt;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-reclaim-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::scan, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("[TaskReclaim] 启动, interval={}s, staleThreshold={}min", intervalSeconds, staleThresholdMinutes);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 扫描 PEL：仅监控告警，不主动 XCLAIM
     */
    private void scan() {
        try {
            Duration threshold = Duration.ofMinutes(staleThresholdMinutes);
            int totalStale = 0;
            for (TaskType.Group group : TaskType.Group.values()) {
                List<StaleMessage> stale = taskQueueService.scanStale(group, threshold, batchSize);
                if (!stale.isEmpty()) {
                    totalStale += stale.size();
                    // 输出明细，便于排查具体卡住的消息（按 idle 时长倒序，取前 10）
                    stale.stream()
                            .sorted((a, b) -> Long.compare(b.getIdleMs(), a.getIdleMs()))
                            .limit(10)
                            .forEach(s -> log.warn(
                                    "[TaskReclaim] 僵尸消息, group={}, streamId={}, consumer={}, idleMs={}, deliveries={}",
                                    group, s.getStreamId(), s.getConsumer(), s.getIdleMs(), s.getDeliveryCount()));
                }
            }
            if (totalStale > 0) {
                // 同一小时内只告警一次总数
                long now = System.currentTimeMillis();
                if (now - lastWarnAt > 3_600_000L) {
                    log.warn("[TaskReclaim] 当前僵尸消息总数={}, 阈值={}min。worker 重启或下次 PEL 拉取会自动接管",
                            totalStale, staleThresholdMinutes);
                    lastWarnAt = now;
                }
            }
        } catch (Exception e) {
            log.warn("[TaskReclaim] 周期任务异常: {}", e.getMessage(), e);
        }
    }
}
