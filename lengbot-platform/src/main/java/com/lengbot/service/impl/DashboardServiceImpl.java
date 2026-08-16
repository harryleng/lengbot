package com.lengbot.service.impl;

import com.lengbot.config.RedisCacheConfig;
import com.lengbot.service.DashboardService;
import com.lengbot.service.port.AgentDashboardPort;
import com.lengbot.service.port.ChatDashboardPort;
import com.lengbot.service.port.KnowledgeDashboardPort;
import com.lengbot.service.port.ModelDashboardPort;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dashboard 聚合统计：platform 只编排各域 Port，不直接访问 Mapper。
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final AgentDashboardPort agentDashboardPort;
    private final KnowledgeDashboardPort knowledgeDashboardPort;
    private final ChatDashboardPort chatDashboardPort;
    private final ModelDashboardPort modelDashboardPort;

    @Override
    @Cacheable(cacheNames = RedisCacheConfig.CACHE_DASHBOARD, key = "'basic'")
    public Map<String, Object> getBasicStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("agentCount", agentDashboardPort.countAgents());
        stats.put("knowledgeCount", knowledgeDashboardPort.countKnowledge());
        stats.put("sessionCount", chatDashboardPort.countSessions());
        stats.put("messageCount", chatDashboardPort.countMessages());
        stats.put("providerCount", modelDashboardPort.countProviders());
        stats.put("modelCount", modelDashboardPort.countModels());
        stats.put("documentCount", knowledgeDashboardPort.countDocuments());
        stats.put("chunkCount", knowledgeDashboardPort.countChunks());
        return stats;
    }

    @Override
    public Map<String, Object> getAgentStats() {
        return agentDashboardPort.getAgentStats();
    }

    @Override
    public Map<String, Object> getKnowledgeStats() {
        return knowledgeDashboardPort.getKnowledgeStats();
    }

    @Override
    public Map<String, Object> getChatStats(Integer days, String startDate, String endDate) {
        return chatDashboardPort.getChatStats(days, startDate, endDate);
    }
}
