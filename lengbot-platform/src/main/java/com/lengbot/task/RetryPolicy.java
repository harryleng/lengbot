package com.lengbot.task;

import lombok.Data;

/**
 * 任务重试策略（按 TaskType 配置，未配置则用默认值）
 *
 * @author lw
 * @since 2026-07-18
 */
@Data
public class RetryPolicy {

    /** 最大尝试次数（含首次；1 表示不重试） */
    private int maxAttempts = 3;

    /** 退避基准时间（毫秒），首次失败后的等待时长 */
    private long backoffBaseMs = 5_000L;

    /** 退避乘数（指数退避：base * multiplier^attempt） */
    private double backoffMultiplier = 2.0d;

    /** 退避上限（毫秒），防止指数爆炸 */
    private long backoffMaxMs = 600_000L;

    /**
     * 计算第 attempt 次失败后的等待时长
     *
     * @param attempt 当前已尝试次数（0 表示首次失败，1 表示第 1 次重试前）
     * @return 下次重试延迟（毫秒）
     */
    public long computeDelay(int attempt) {
        long delay = (long) (backoffBaseMs * Math.pow(backoffMultiplier, attempt));
        return Math.min(delay, backoffMaxMs);
    }
}
