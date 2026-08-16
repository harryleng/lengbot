package com.lengbot.service.port;

import java.util.Map;

/**
 * Agent 域 Dashboard 统计端口，由 agent 模块实现。
 */
public interface AgentDashboardPort {

    /**
     * @return Agent 总数
     */
    long countAgents();

    /**
     * @return Agent 统计详情（状态分布、最近创建等）
     */
    Map<String, Object> getAgentStats();
}
