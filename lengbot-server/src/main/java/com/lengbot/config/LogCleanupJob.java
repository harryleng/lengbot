package com.lengbot.config;

import com.lengbot.service.LlmTraceService;
import com.lengbot.service.WorkflowTestRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 日志表 TTL 清理任务
 * <p>llm_trace / workflow_test_run 等日志类表无界增长，按保留期物理删除过期数据。
 * 默认每天凌晨 2 点执行一次，保留天数由 {@code lengbot.log-cleanup.retention-days} 配置。</p>
 *
 * @author finch
 * @since 2026-07-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogCleanupJob {

    private final LlmTraceService llmTraceService;
    private final WorkflowTestRunService workflowTestRunService;

    /** 日志保留天数，默认 30 天；配置为 0 或负数时禁用清理 */
    @Value("${lengbot.log-cleanup.retention-days:30}")
    private int retentionDays;

    /**
     * 每天凌晨 2:03 执行清理（错开整点，降低与其他定时任务撞车的概率）
     */
    @Scheduled(cron = "0 3 2 * * ?")
    public void cleanup() {
        if (retentionDays <= 0) {
            log.info("[LogCleanup] retention-days={} 跳过清理", retentionDays);
            return;
        }
        log.info("[LogCleanup] 开始清理 {} 天前的日志数据", retentionDays);
        try {
            int traceDeleted = llmTraceService.cleanupByAge(retentionDays);
            int workflowDeleted = workflowTestRunService.cleanupByAge(retentionDays);
            log.info("[LogCleanup] 完成: llm_trace删除={}, workflow_test_run删除={}",
                    traceDeleted, workflowDeleted);
        } catch (Exception e) {
            log.error("[LogCleanup] 清理失败", e);
        }
    }
}
