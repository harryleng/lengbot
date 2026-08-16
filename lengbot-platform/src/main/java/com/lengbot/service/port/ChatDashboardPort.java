package com.lengbot.service.port;

import java.util.Map;

/**
 * 对话域 Dashboard 统计端口，由 agent 模块实现。
 */
public interface ChatDashboardPort {

    /**
     * @return 会话总数
     */
    long countSessions();

    /**
     * @return 消息总数
     */
    long countMessages();

    /**
     * @return 对话统计详情（含消息趋势）
     */
    Map<String, Object> getChatStats(Integer days, String startDate, String endDate);
}
