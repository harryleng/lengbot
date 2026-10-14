package com.lengbot.workflow;

import com.lengbot.service.WorkflowCheckpointService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工作流检查点启动自愈
 * <p>后端启动时扫描「最新检查点为 RUNNING 且超过宽限期」的孤儿运行，逐个续跑，
 * 使进程崩溃/重启期间未完成的运行自动恢复，不再整轮丢失。</p>
 *
 * @author lw
 * @since 2026-10-14
 */
@Slf4j
@Component
@Order(100)
public class WorkflowCheckpointRecovery implements ApplicationRunner {

    private final WorkflowCheckpointService workflowCheckpointService;
    private final WorkflowExecutorService workflowExecutorService;

    private final boolean autoRecover;
    private final int graceSeconds;

    public WorkflowCheckpointRecovery(WorkflowCheckpointService workflowCheckpointService,
                                       WorkflowExecutorService workflowExecutorService,
                                       @Value("${lengbot.workflow.checkpoint.auto-recover:true}") boolean autoRecover,
                                       @Value("${lengbot.workflow.checkpoint.recover-grace-seconds:120}") int graceSeconds) {
        this.workflowCheckpointService = workflowCheckpointService;
        this.workflowExecutorService = workflowExecutorService;
        this.autoRecover = autoRecover;
        this.graceSeconds = graceSeconds;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoRecover) {
            log.info("[WorkflowCheckpointRecovery] 自动恢复已关闭（lengbot.workflow.checkpoint.auto-recover=false），跳过孤儿运行扫描");
            return;
        }
        // 异步执行，避免阻塞应用启动
        Thread recoveryThread = new Thread(this::recover, "workflow-checkpoint-recovery");
        recoveryThread.setDaemon(true);
        recoveryThread.start();
    }

    private void recover() {
        try {
            List<String> runIds = workflowCheckpointService.listRecoverableRunIds(graceSeconds);
            if (runIds == null || runIds.isEmpty()) {
                log.info("[WorkflowCheckpointRecovery] 无可恢复的孤儿运行");
                return;
            }
            log.info("[WorkflowCheckpointRecovery] 发现 {} 个孤儿运行，开始续跑: {}", runIds.size(), runIds);
            for (String runId : runIds) {
                try {
                    String result = workflowExecutorService.resumeFromCheckpoint(runId);
                    log.info("[WorkflowCheckpointRecovery] 运行 {} 续跑完成，输出长度={}",
                            runId, result != null ? result.length() : 0);
                } catch (Exception e) {
                    log.error("[WorkflowCheckpointRecovery] 运行 {} 续跑失败: {}", runId, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("[WorkflowCheckpointRecovery] 孤儿运行扫描异常: {}", e.getMessage(), e);
        }
    }
}
