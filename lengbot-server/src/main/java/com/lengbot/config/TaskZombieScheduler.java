package com.lengbot.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lengbot.entity.Document;
import com.lengbot.entity.Task;
import com.lengbot.enums.DocumentStatus;
import com.lengbot.enums.TaskStatus;
import com.lengbot.enums.TaskType;
import com.lengbot.service.DocumentService;
import com.lengbot.service.TaskService;
import com.lengbot.task.StaleMessage;
import com.lengbot.task.TaskQueueService;
import com.lengbot.task.TaskZombieProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 僵尸任务扫描器
 * <p>定期扫描 status=RUNNING 但 update_time 已超时的任务，识别真正的孤儿任务并强制失败：
 *
 * <p>判定流程：
 * <ol>
 *   <li>查 DB：status=RUNNING 且 update_time &lt; now() - timeout(type) 的任务（按 TaskType 取阈值）</li>
 *   <li>查 Redis：拉取两个消费组的 PEL，建立"仍在 PEL 中"的 streamId 集合</li>
 *   <li>对每个候选任务：
 *     <ul>
 *       <li>streamId 仍在 PEL 中 → worker 可能还在跑或自然恢复中，<b>跳过</b></li>
 *       <li>streamId 不在 PEL 中（已 ACK 但状态未推进）→ 确认孤儿，<b>markFailed + 回滚 Document</b></li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>设计动机：handleMessage 把 RUNNING 视为"别处正在处理"直接 ACK，导致 worker 崩溃后
 * 任务永远卡 RUNNING。本扫描器补齐这个状态机盲点。
 *
 * <p>幂等性：markFailed 通过状态 CAS（仅 RUNNING → FAILED）保证重复扫描不会误改终态任务。
 *
 * @author lw
 * @since 2026-07-18
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class TaskZombieScheduler {

    private final TaskService taskService;
    private final TaskQueueService taskQueueService;
    private final DocumentService documentService;
    private final TaskZombieProperties zombieProperties;

    /** 文档类任务类型集合：失败时需联动回滚 Document 状态 */
    private static final Set<TaskType> DOCUMENT_TASK_TYPES = Set.of(
            TaskType.DOCUMENT_UPLOAD, TaskType.DOCUMENT_INGEST);

    private ScheduledExecutorService scheduler;

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-zombie-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::scan,
                zombieProperties.getIntervalSeconds(),
                zombieProperties.getIntervalSeconds(),
                TimeUnit.SECONDS);
        log.info("[TaskZombie] 启动, interval={}s, defaultTimeout={}min",
                zombieProperties.getIntervalSeconds(), zombieProperties.getDefaultTimeoutMinutes());
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 扫描一轮：识别孤儿 RUNNING 任务并强制失败
     * <p>包级可见以便单测直接调用，绕过定时器</p>
     */
    void scan() {
        try {
            // 1. 拉取两个消费组的 PEL streamId 集合（idle 阈值=0 表示拿全量）
            //    用 streamId 集合 O(1) 判断"消息是否仍在 PEL"
            Set<String> liveStreamIds = collectLiveStreamIds();
            if (liveStreamIds == null) {
                // Redis 异常时保守跳过，避免误判
                log.warn("[TaskZombie] PEL 查询失败，本轮跳过");
                return;
            }

            // 2. DB 扫描 RUNNING 任务：按 update_time 升序，便于先处理最老的
            LocalDateTime now = LocalDateTime.now();
            List<Task> candidates = taskService.list(new LambdaQueryWrapper<Task>()
                    .eq(Task::getStatus, TaskStatus.RUNNING)
                    .lt(Task::getUpdateTime, now.minusMinutes(zombieProperties.getDefaultTimeoutMinutes()))
                    .orderByAsc(Task::getUpdateTime)
                    .last("LIMIT " + zombieProperties.getBatchSize()));

            if (candidates.isEmpty()) {
                return;
            }

            int failedCount = 0;
            int skippedInPel = 0;
            int skippedNotDue = 0;
            for (Task task : candidates) {
                // 2.1 按任务类型精细化判定超时（超过 type 专属阈值才算孤儿）
                long typeTimeout = zombieProperties.resolveTimeoutMinutes(task.getType());
                LocalDateTime deadline = task.getUpdateTime().plusMinutes(typeTimeout);
                if (deadline.isAfter(now)) {
                    // 未到本类型阈值（仅过默认阈值，比如 GRAPH_EXTRACTION 配了 60min 还在跑）
                    skippedNotDue++;
                    continue;
                }

                // 2.2 streamId 仍在 PEL 中 → worker 可能还在跑，跳过
                //     注意：streamId 为空属于异常情况（不应该发生），按孤儿处理
                String streamId = task.getStreamId();
                if (streamId != null && liveStreamIds.contains(streamId)) {
                    skippedInPel++;
                    continue;
                }

                // 2.3 确认孤儿：CAS markFailed（仅 RUNNING → FAILED，防并发）
                String reason = String.format(
                        "任务执行超时未完结（update_time=%s，已超过 %d 分钟），疑似 worker 崩溃",
                        task.getUpdateTime(), typeTimeout);
                boolean marked = casMarkFailed(task.getId(), reason);
                if (!marked) {
                    // 状态已变更（worker 最后一刻推进了状态），跳过
                    continue;
                }

                // 2.4 联动回滚 Document 状态（仅文档类任务且 Document 处于中间态）
                rollbackDocumentIfIntermediate(task);

                // 2.5 清理可能残留的取消信号（避免下次相同 ID 任务读到脏数据）
                try {
                    taskQueueService.clearCancel(task.getId());
                } catch (Exception ignored) {
                    // 清理失败不影响主流程
                }

                failedCount++;
                log.warn("[TaskZombie] 强制失败, taskId={}, type={}, streamId={}, updateTime={}",
                        task.getId(), task.getType(), streamId, task.getUpdateTime());
            }

            if (failedCount > 0 || skippedInPel > 0 || skippedNotDue > 0) {
                log.info("[TaskZombie] 扫描完成, candidates={}, failed={}, skippedInPel={}, skippedNotDue={}",
                        candidates.size(), failedCount, skippedInPel, skippedNotDue);
            }
        } catch (Exception e) {
            log.warn("[TaskZombie] 周期任务异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 拉取两个消费组的 PEL 全量 streamId
     * <p>用 idle 阈值=0 + 大 count 拿全量；Redis 异常时返回 null（调用方保守跳过）</p>
     */
    private Set<String> collectLiveStreamIds() {
        Set<String> live = new HashSet<>();
        for (TaskType.Group group : TaskType.Group.values()) {
            try {
                List<StaleMessage> pending = taskQueueService.scanStale(group, Duration.ZERO, 1_000);
                for (StaleMessage m : pending) {
                    if (m.getStreamId() != null) {
                        live.add(m.getStreamId());
                    }
                }
            } catch (Exception e) {
                log.warn("[TaskZombie] 拉取 PEL 失败, group={}, error={}", group, e.getMessage());
                return null;
            }
        }
        return live;
    }

    /**
     * CAS 状态推进：仅当任务仍为 RUNNING 时改为 FAILED
     * <p>防并发：本扫描器与 worker 的 markSuccess/markCancelled 都可能并发推进状态，
     * 通过状态 CAS 避免覆盖 worker 的最新状态</p>
     *
     * @return 是否实际推进（false 表示状态已变更，应跳过）
     */
    private boolean casMarkFailed(Long taskId, String reason) {
        boolean updated = taskService.lambdaUpdate()
                .eq(Task::getId, taskId)
                .eq(Task::getStatus, TaskStatus.RUNNING)
                .set(Task::getStatus, TaskStatus.FAILED)
                .set(Task::getError, reason)
                .set(Task::getUpdateTime, LocalDateTime.now())
                .update();
        if (updated) {
            // 同步死信标记，便于运维查死信流
            try {
                taskService.markDeadLetter(taskId, reason);
            } catch (Exception e) {
                log.debug("[TaskZombie] markDeadLetter 失败(非关键), taskId={}, err={}",
                        taskId, e.getMessage());
            }
        }
        return updated;
    }

    /**
     * 文档类任务失败时联动回滚：仅当 Document 处于中间态（非 UPLOADED/COMPLETED/FAILED）
     * <p>逻辑与 TaskConsumerConfig.recoverDocument 对齐，但作用域是崩溃后的清理</p>
     */
    private void rollbackDocumentIfIntermediate(Task task) {
        if (!DOCUMENT_TASK_TYPES.contains(task.getType()) || task.getRefId() == null) {
            return;
        }
        Document doc = documentService.getById(task.getRefId());
        if (doc == null) {
            return;
        }
        DocumentStatus status = doc.getStatus();
        // 已在终态（已上传/已完成/已失败）的文档不重复处理
        if (status == DocumentStatus.UPLOADED
                || status == DocumentStatus.COMPLETED
                || status == DocumentStatus.FAILED) {
            return;
        }
        doc.setStatus(DocumentStatus.FAILED);
        doc.setErrorMessage("任务执行超时崩溃，已自动回滚，请重新触发");
        documentService.updateById(doc);
    }
}
