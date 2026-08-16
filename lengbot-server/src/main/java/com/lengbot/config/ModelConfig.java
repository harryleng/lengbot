package com.lengbot.config;

/**
 * AI 模型配置。
 * <p>已废弃：Spring AI 时代手动创建 EmbeddingModel Bean 的配置。</p>
 * <p>新工程已迁移至 AgentScope Java，embedding 由 lengbot-ai 模块的 TextEmbeddingServiceImpl
 * 统一管理（按 provider 类型 DASHSCOPE/OPENAI/OLLAMA 解析），此处不再需要声明 Bean。</p>
 *
 * @deprecated Spring AI 迁移遗留，保留空壳避免残留引用；如需恢复请按 AgentScope 方式实现
 */
@Deprecated
@org.springframework.context.annotation.Configuration
public class ModelConfig {
    // 已清空：原实现依赖 spring-ai/dashscope embedding，迁移后无引用方
}
