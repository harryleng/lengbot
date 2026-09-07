package com.lengbot.config;

import com.lengbot.subagent.service.SubAgentTaskService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SubAgent 孤儿任务回收器（崩溃兜底）
 *
 * <p>为什么需要单独一个扫描器：{@link TaskZombieScheduler} 只回收 {@code task} 表，
 * 那是 Redis Stream 异步任务队列（文档解析、图谱抽取、评测）的状态机。
 * 而子 Agent 是<b>同步嵌套在对话链路里</b>执行的——父 Agent 调用 delegate 工具后阻塞等待，
 * 既不入 task 表、也没有 PEL 可查，所以那套 PEL 确认机制在这里根本用不上。</p>
 *
 * <p>后果：进程被强杀 / 容器重启时，运行中的 {@code subagent_run} 没有机会推进状态，
 * 会永久停在 pending/running，连带让所属批次 {@code subagent_task_batch} 的
 * {@code completed_count + failed_count} 永远凑不满 total，前端协作面板一直转圈。</p>
 *
 * <p>判定方式退化成"超时即孤儿"：这里没有 PEL 可以确认 worker 是否还活着，
 * 所以阈值必须<b>大于子 Agent 单次执行的最长合理耗时</b>，否则会误杀正在跑的任务。
 * 推进用 CAS（仅 pending/running → failed），即使误判区间内 worker 已完成也会被 CAS 挡住。</p>
 *
 * @author lw
 * @since 2026-09-07
 */
@Slf4j
@Configuration
public class SubAgentRunZombieScheduler {

    private final SubAgentTaskService subAgentTaskService;

    /** 是否启用（本地调试时可关） */
    @Value("${lengbot.subagent.reap.enabled:true}")
    private boolean enabled;

    /** 扫描间隔（秒） */
    @Value("${lengbot.subagent.reap.interval-seconds:120}")
    private long intervalSeconds;

    /**
     * 孤儿判定阈值（分钟）：update_time 距今超过该值且仍未终结 → 判为崩溃残留。
     * 必须大于子 Agent 单次执行的最长合理耗时，默认 30 分钟。
     */
    @Value("${lengbot.subagent.reap.timeout-minutes:30}")
    private int timeoutMinutes;

    /** 单轮最大扫描条数 */
    @Value("${lengbot.subagent.reap.batch-size:200}")
    private int batchSize;

    private ScheduledExecutorService scheduler;

    public SubAgentRunZombieScheduler(SubAgentTaskService subAgentTaskService) {
        this.subAgentTaskService = subAgentTaskService;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("[SubAgentReap] 已禁用（lengbot.subagent.reap.enabled=false）");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "subagent-reap-scheduler");
            t.setDaemon(true);
            return t;
        });
        // 用 scheduleWithFixedDelay 而非 AtFixedRate：单轮耗时长时不会堆叠执行
        scheduler.scheduleWithFixedDelay(this::scan, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("[SubAgentReap] 启动, interval={}s, timeout={}min, batchSize={}",
                intervalSeconds, timeoutMinutes, batchSize);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 执行一轮回收；包级可见便于单测直接调用，绕过定时器 */
    void scan() {
        try {
            subAgentTaskService.reapOrphanRuns(timeoutMinutes, batchSize);
        } catch (Exception e) {
            // 周期任务绝不能因异常退出调度循环，否则后续再也不会执行
            log.warn("[SubAgentReap] 周期任务异常: {}", e.getMessage(), e);
        }
    }
}
