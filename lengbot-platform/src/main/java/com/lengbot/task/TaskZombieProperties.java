package com.lengbot.task;

import com.lengbot.enums.TaskType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * 僵尸任务扫描配置
 * <p>周期扫描 status=RUNNING 但 update_time 已超时的任务：若对应 streamId 不在 PEL 中
 * （表示已被 ACK 但状态未推进，疑似 worker 崩溃），强制 markFailed 并回滚关联资源。
 *
 * <pre>
 * lengbot:
 *   task:
 *     zombie:
 *       interval-seconds: 60
 *       default-timeout-minutes: 10
 *       batch-size: 100
 *       overrides:
 *         GRAPH_EXTRACTION: 60
 *         QA_PAIR_GENERATE: 30
 * </pre>
 *
 * @author lw
 * @since 2026-07-18
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "lengbot.task.zombie")
public class TaskZombieProperties {

    /** 扫描周期（秒），默认 60s */
    private long intervalSeconds = 60L;

    /** 默认超时阈值（分钟）：RUNNING 任务 update_time 超过此值视为疑似僵尸 */
    private long defaultTimeoutMinutes = 10L;

    /** 单次扫描上限，避免一次性处理过多任务造成 DB 压力 */
    private int batchSize = 100;

    /** 按 TaskType 覆盖的超时阈值（分钟），未配置的类型回落到 default */
    private Map<TaskType, Long> overrides = new EnumMap<>(TaskType.class);

    /**
     * 取某 TaskType 的超时阈值（分钟）
     *
     * @param type 任务类型；null 时返回默认值
     */
    public long resolveTimeoutMinutes(TaskType type) {
        if (type == null) {
            return defaultTimeoutMinutes;
        }
        return overrides.getOrDefault(type, defaultTimeoutMinutes);
    }
}
