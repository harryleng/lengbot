package com.lengbot.service.impl;

import com.lengbot.entity.Model;
import com.lengbot.entity.ModelProvider;
import com.lengbot.enums.CommonStatus;
import com.lengbot.enums.ModelProviderType;
import com.lengbot.enums.ModelType;
import com.lengbot.model.ModelProviderHandler;
import com.lengbot.service.ModelService;
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
    private final ModelService modelService;
    private final List<ModelProviderHandler> handlers;

    private EmbeddingModel embeddingModel;
    private Map<ModelProviderType, ModelProviderHandler> handlerMap;

    @PostConstruct
    public void init() {
        handlerMap = handlers.stream()
                .collect(java.util.stream.Collectors.toMap(ModelProviderHandler::getProviderType, h -> h));

        // 解析 embedding 模型（来自 model 表的 type=embedding 记录）
        Model embeddingModelEntity = resolveEmbeddingModel();
        if (embeddingModelEntity != null) {
            try {
                this.embeddingModel = buildEmbeddingModel(embeddingModelEntity);
                log.info("[TextEmbeddingService] 嵌入模型初始化成功: type={}, model={}, dims={}",
                        embeddingModelEntity.getType(), embeddingModel.getModelName(), embeddingModel.getDimensions());
            } catch (Exception e) {
                log.error("[TextEmbeddingService] 嵌入模型初始化失败: modelId={}", embeddingModelEntity.getModelId(), e);
            }
        } else {
            log.warn("[TextEmbeddingService] 未在 model 表中找到 type=embedding 的启用模型，嵌入功能将不可用");
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
     * 解析嵌入模型（来自 model 表 type=embedding 的启用记录）
     * <p>解析顺序：
     * <ol>
     *   <li>系统配置 {@code embedding.providerId} 指定的提供商下，取 type=embedding 的模型；</li>
     *   <li>否则取 model 表中第一个 type=embedding 的启用模型。</li>
     * </ol>
     * 其所属提供商的可用性在 {@link #buildEmbeddingModel(Model)} 中校验。
     * </p>
     */
    private Model resolveEmbeddingModel() {
        List<Model> embeddingModels = modelService.listByType(ModelType.EMBEDDING);
        if (embeddingModels.isEmpty()) {
            log.warn("[TextEmbeddingService] 未在 model 表中找到 type=embedding 的启用模型，嵌入功能不可用");
            return null;
        }
        // 1. 系统配置显式指定的嵌入提供商：优先取该 provider 下的 embedding 模型
        String embeddingProviderId = systemConfigService.getConfigValue("embedding.providerId");
        if (embeddingProviderId != null && !embeddingProviderId.isBlank()) {
            Long pid = Long.parseLong(embeddingProviderId);
            Model matched = embeddingModels.stream()
                    .filter(m -> pid.equals(m.getProviderId()))
                    .findFirst()
                    .orElse(null);
            if (matched != null) {
                return matched;
            }
        }
        // 2. 否则取第一个 embedding 模型（其 provider 在 buildEmbeddingModel 中校验可用性）
        return embeddingModels.get(0);
    }

    /**
     * 判断提供商是否可作为嵌入提供商使用：状态为启用，且（非 Ollama 时）具备 API Key。
     */
    private boolean isUsableEmbeddingProvider(ModelProvider provider) {
        if (provider == null || provider.getStatus() == null || provider.getStatus() != CommonStatus.ACTIVE) {
            return false;
        }
        if (provider.getType() == ModelProviderType.OLLAMA) {
            return true;
        }
        return provider.getApiKey() != null && !provider.getApiKey().isBlank();
    }

    /**
     * 根据 model 表中的 embedding 记录构建 EmbeddingModel。
     * <p>模型名与维度取自该记录的真实字段（model_id / dimension）；
     * dimension 为空时退回按提供商类型的默认值，保证未配置维度时不致失败。</p>
     */
    private EmbeddingModel buildEmbeddingModel(Model embeddingModel) {
        ModelProvider provider = modelProviderService.getById(embeddingModel.getProviderId());
        if (!isUsableEmbeddingProvider(provider)) {
            throw new IllegalStateException(
                    "嵌入模型所属提供商不可用（未启用或缺少 API Key）: providerId=" + embeddingModel.getProviderId());
        }
        ExecutionConfig defaultConfig = ExecutionConfig.MODEL_DEFAULTS;
        String modelId = embeddingModel.getModelId();
        int dimension = embeddingModel.getDimension() != null
                ? embeddingModel.getDimension()
                : defaultDimension(provider.getType());
        // 仅对支持 dimensions 参数的模型下发该参数；其余模型（如 bge 系列）使用其原生维度，
        // 避免向不支持的模型（SiliconFlow 的 BAAI/bge-* 等）下发 dimensions 触发 400。
        boolean sendDimensions = supportsDimensions(provider.getType(), modelId);
        log.info("[TextEmbeddingService] 构建嵌入模型: provider={}, model={}, dims={}, sendDimensions={}",
                provider.getType(), modelId, dimension, sendDimensions);
        return switch (provider.getType()) {
            case DASHSCOPE -> {
                String apiKey = provider.getApiKey();
                String baseUrl = provider.getBaseUrl();
                var builder = DashScopeTextEmbedding.builder()
                        .apiKey(apiKey)
                        .modelName(modelId)
                        .executionConfig(defaultConfig);
                if (sendDimensions) {
                    builder.dimensions(dimension);
                }
                if (baseUrl != null && !baseUrl.isBlank()) {
                    builder.baseUrl(baseUrl);
                }
                yield builder.build();
            }
            case OPENAI -> {
                String apiKey = provider.getApiKey();
                String baseUrl = provider.getBaseUrl();
                var builder = OpenAITextEmbedding.builder()
                        .apiKey(apiKey)
                        .modelName(modelId)
                        .executionConfig(defaultConfig);
                if (sendDimensions) {
                    builder.dimensions(dimension);
                }
                if (baseUrl != null && !baseUrl.isBlank()) {
                    builder.baseUrl(baseUrl);
                }
                yield builder.build();
            }
            case OLLAMA -> {
                String baseUrl = provider.getBaseUrl() != null ? provider.getBaseUrl() : "http://localhost:11434";
                var builder = OllamaTextEmbedding.builder()
                        .baseUrl(baseUrl)
                        .modelName(modelId)
                        .executionConfig(defaultConfig);
                if (sendDimensions) {
                    builder.dimensions(dimension);
                }
                yield builder.build();
            }
            default -> throw new IllegalArgumentException(
                    "不支持的嵌入模型提供商类型: " + provider.getType());
        };
    }

    /**
     * 判断指定模型是否支持下发 {@code dimensions} 参数。
     * <p>OpenAI 仅 {@code text-embedding-3-*} 系列支持该参数；Ollama 支持；
     * DashScope 等固定维度模型不支持，下发会触发 400。bge 等开源模型亦不支持。</p>
     */
    private boolean supportsDimensions(ModelProviderType type, String modelId) {
        return switch (type) {
            case OLLAMA -> true;
            case OPENAI -> modelId != null && modelId.startsWith("text-embedding-3");
            case DASHSCOPE -> false;
            default -> false;
        };
    }

    /**
     * 各供应商的默认嵌入维度（仅当 model 表未配置 dimension 时使用）。
     */
    private int defaultDimension(ModelProviderType type) {
        return switch (type) {
            case DASHSCOPE -> 1024;
            case OPENAI -> 1536;
            case OLLAMA -> 768;
            default -> 1536;
        };
    }

    /** 延迟初始化嵌入模型（用于 provider / 模型动态变更后重建） */
    public void refresh() {
        Model embeddingModelEntity = resolveEmbeddingModel();
        if (embeddingModelEntity != null) {
            this.embeddingModel = buildEmbeddingModel(embeddingModelEntity);
            log.info("[TextEmbeddingService] 嵌入模型已刷新: modelId={}, dims={}",
                    embeddingModel.getModelName(), embeddingModel.getDimensions());
        }
    }
}
