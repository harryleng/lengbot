package com.lengbot.config;

import com.lengbot.task.TaskQueueService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Redis Stream 消费组初始化
 * <p>应用启动时幂等创建 main stream 的两个消费组（cg:default / cg:heavy），
 * BUSYGROUP 错误由 TaskQueueServiceImpl 内部吞掉。
 *
 * @author lw
 * @since 2026-07-18
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisStreamConfig {

    private final TaskQueueService taskQueueService;

    @PostConstruct
    void initGroups() {
        try {
            taskQueueService.ensureGroups();
        } catch (Exception e) {
            // 启动期 Redis 不可用时仅记录日志，不阻塞启动；后续消费循环会持续重试
            log.warn("[RedisStream] 消费组初始化失败（Redis 不可达？后续会重试）: {}", e.getMessage());
        }
    }
}
