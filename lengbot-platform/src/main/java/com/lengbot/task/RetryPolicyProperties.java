package com.lengbot.task;

import com.lengbot.enums.TaskType;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * 任务重试策略配置加载器
 * <p>从 application.yml 的 lengbot.task.retry.* 读取，按 TaskType 提供对应策略，
 * 未配置的类型回落到 default。
 *
 * <pre>
 * lengbot:
 *   task:
 *     retry:
 *       default: { max-attempts: 3, backoff-base-ms: 5000 }
 *       GRAPH_EXTRACTION: { max-attempts: 2, backoff-base-ms: 30000 }
 * </pre>
 *
 * @author finch
 * @since 2026-07-18
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "lengbot.task.retry")
public class RetryPolicyProperties {

    /** 默认策略（必填，所有 TaskType 未显式配置时回落到此） */
    private RetryPolicy defaultPolicy = new RetryPolicy();

    /** 按 TaskType 名覆盖的策略 */
    private Map<TaskType, RetryPolicy> overrides = new EnumMap<>(TaskType.class);

    @PostConstruct
    void validate() {
        if (defaultPolicy == null) {
            defaultPolicy = new RetryPolicy();
        }
        if (defaultPolicy.getMaxAttempts() < 1) {
            log.warn("[RetryPolicy] default.max-attempts={} 非法，回落到 1", defaultPolicy.getMaxAttempts());
            defaultPolicy.setMaxAttempts(1);
        }
        overrides.forEach((type, p) -> {
            if (p == null) {
                overrides.put(type, defaultPolicy);
            } else if (p.getMaxAttempts() < 1) {
                log.warn("[RetryPolicy] {}.max-attempts={} 非法，回落到 1", type, p.getMaxAttempts());
                p.setMaxAttempts(1);
            }
        });
        log.info("[RetryPolicy] 加载完成, default={}, overrides={}", defaultPolicy, overrides.keySet());
    }

    /**
     * 取某 TaskType 的重试策略
     */
    public RetryPolicy resolve(TaskType type) {
        if (type == null) {
            return defaultPolicy;
        }
        return overrides.getOrDefault(type, defaultPolicy);
    }
}
