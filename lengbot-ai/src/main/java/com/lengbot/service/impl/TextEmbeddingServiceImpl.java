package com.lengbot.service.impl;

import com.lengbot.entity.ModelProvider;
import com.lengbot.enums.ModelProviderType;
import com.lengbot.model.ModelProviderHandler;
import com.lengbot.service.TextEmbeddingService;
import com.lengbot.service.ModelProviderService;
import com.lengbot.service.SystemConfigService;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;
import io.agentscope.core.embedding.ollama.OllamaTextEmbedding;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文本嵌入服务实现（AgentScope 引擎）
 * <p>根据系统默认模型提供商自动创建对应的嵌入模型</p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextEmbeddingServiceImpl implements TextEmbeddingService {

    private final ModelProviderService modelProviderService;
    private final SystemConfigService systemConfigService;
    private final List<ModelProviderHandler> handlers;

    private EmbeddingModel embeddingModel;
    private Map<ModelProviderType, ModelProviderHandler> handlerMap;

    @PostConstruct
    public void init() {
        handlerMap = handlers.stream()
                .collect(java.util.stream.Collectors.toMap(ModelProviderHandler::getProviderType, h -> h));

        // 获取默认启用的嵌入模型提供商
        ModelProvider embeddingProvider = resolveEmbeddingProvider();
        if (embeddingProvider != null) {
            try {
                this.embeddingModel = createEmbeddingModel(embeddingProvider);
                log.info("[TextEmbeddingService] 嵌入模型初始化成功: type={}, model={}, dims={}",
                        embeddingProvider.getType(), embeddingModel.getModelName(), embeddingModel.getDimensions());
            } catch (Exception e) {
                log.error("[TextEmbeddingService] 嵌入模型初始化失败: type={}", embeddingProvider.getType(), e);
            }
        } else {
            log.warn("[TextEmbeddingService] 未找到可用的嵌入模型提供商，嵌入功能将不可用");
        }
    }

    @Override
    public EmbeddingModel getEmbeddingModel() {
        if (embeddingModel == null) {
            throw new IllegalStateException("嵌入模型未初始化，请检查模型提供商配置");
        }
        return embeddingModel;
    }

    /**
     * 解析嵌入模型提供商
     * <p>优先使用系统配置中指定的嵌入提供商，否则使用第一个启用的提供商</p>
     */
    private ModelProvider resolveEmbeddingProvider() {
        String embeddingProviderId = systemConfigService.getConfigValue("embedding.providerId");
        if (embeddingProviderId != null && !embeddingProviderId.isBlank()) {
            ModelProvider provider = modelProviderService.getById(Long.parseLong(embeddingProviderId));
            if (provider != null) {
                return provider;
            }
        }
        // 回退：使用 DashScope（默认），或第一个启用且有 API Key 的提供商
        List<ModelProvider> providers = modelProviderService.list();
        return providers.stream()
                .filter(p -> p.getApiKey() != null && !p.getApiKey().isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * 根据提供商类型创建 EmbeddingModel
     */
    private EmbeddingModel createEmbeddingModel(ModelProvider provider) {
        ExecutionConfig defaultConfig = ExecutionConfig.MODEL_DEFAULTS;
        return switch (provider.getType()) {
            case DASHSCOPE -> {
                String apiKey = provider.getApiKey();
                String baseUrl = provider.getBaseUrl();
                // DashScope 默认使用 text-embedding-v1，维度 1024
                var builder = DashScopeTextEmbedding.builder()
                        .apiKey(apiKey)
                        .modelName("text-embedding-v1")
                        .dimensions(1024)
                        .executionConfig(defaultConfig);
                if (baseUrl != null && !baseUrl.isBlank()) {
                    builder.baseUrl(baseUrl);
                }
                yield builder.build();
            }
            case OPENAI -> {
                String apiKey = provider.getApiKey();
                String baseUrl = provider.getBaseUrl();
                // OpenAI 默认使用 text-embedding-3-small
                var builder = OpenAITextEmbedding.builder()
                        .apiKey(apiKey)
                        .modelName("text-embedding-3-small")
                        .dimensions(1536)
                        .executionConfig(defaultConfig);
                if (baseUrl != null && !baseUrl.isBlank()) {
                    builder.baseUrl(baseUrl);
                }
                yield builder.build();
            }
            case OLLAMA -> {
                String baseUrl = provider.getBaseUrl() != null ? provider.getBaseUrl() : "http://localhost:11434";
                // Ollama 默认使用 nomic-embed-text
                yield OllamaTextEmbedding.builder()
                        .baseUrl(baseUrl)
                        .modelName("nomic-embed-text")
                        .dimensions(768)
                        .executionConfig(defaultConfig)
                        .build();
            }
            default -> throw new IllegalArgumentException(
                    "不支持的嵌入模型提供商类型: " + provider.getType());
        };
    }

    /** 延迟初始化嵌入模型（用于 provider 动态变更后重建） */
    public void refresh() {
        ModelProvider provider = resolveEmbeddingProvider();
        if (provider != null) {
            this.embeddingModel = createEmbeddingModel(provider);
            log.info("[TextEmbeddingService] 嵌入模型已刷新: type={}, model={}",
                    provider.getType(), embeddingModel.getModelName());
        }
    }
}
