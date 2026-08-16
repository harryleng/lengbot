package com.lengbot.service.port;

/**
 * 模型域 Dashboard 统计端口，由 ai 模块实现。
 */
public interface ModelDashboardPort {

    /**
     * @return 模型提供商总数
     */
    long countProviders();

    /**
     * @return 模型总数
     */
    long countModels();
}
