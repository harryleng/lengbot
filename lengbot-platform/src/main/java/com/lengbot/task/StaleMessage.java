package com.lengbot.task;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 待回收消息（PEL 中超时未 ACK 的消息）
 *
 * @author lw
 * @since 2026-07-18
 */
@Data
@AllArgsConstructor
public class StaleMessage {

    /** Stream 消息 ID */
    private String streamId;

    /** 原消费者名 */
    private String consumer;

    /** 空闲时长（毫秒） */
    private long idleMs;

    /** 投递次数 */
    private long deliveryCount;
}
