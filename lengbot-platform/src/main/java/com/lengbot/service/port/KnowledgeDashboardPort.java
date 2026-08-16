package com.lengbot.service.port;

import java.util.Map;

/**
 * 知识库域 Dashboard 统计端口，由 knowledge 模块实现。
 */
public interface KnowledgeDashboardPort {

    /**
     * @return 知识库总数
     */
    long countKnowledge();

    /**
     * @return 文档总数
     */
    long countDocuments();

    /**
     * @return 分块总数
     */
    long countChunks();

    /**
     * @return 知识库统计详情
     */
    Map<String, Object> getKnowledgeStats();
}
