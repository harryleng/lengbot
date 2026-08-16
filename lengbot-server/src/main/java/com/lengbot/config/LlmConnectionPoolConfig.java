package com.lengbot.config;

/**
 * LLM 调用 WebClient 配置。
 * <p>已废弃：Spring AI 时代提供统一 WebClient.Builder 的配置。</p>
 * <p>新工程已迁移至 AgentScope Java，LLM 调用由 AgentScope 的 OpenAIClient/DashScopeClient
 * 管理，不再依赖 spring-webflux 的 WebClient，此处不再需要声明 Bean。</p>
 *
 * @deprecated Spring AI 迁移遗留，保留空壳避免残留引用
 */
@Deprecated
@org.springframework.context.annotation.Configuration
public class LlmConnectionPoolConfig {
    // 已清空：原实现依赖 spring-webflux WebClient，迁移后无引用方
}
