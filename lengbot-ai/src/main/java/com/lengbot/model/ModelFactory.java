package com.lengbot.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.common.BizException;
import com.lengbot.entity.ModelProvider;
import com.lengbot.enums.ErrorCode;
import com.lengbot.enums.ModelProviderType;
import com.lengbot.event.CacheInvalidationBroadcaster;
import com.lengbot.service.ModelProviderService;
import com.lengbot.service.SystemConfigService;
import com.lengbot.util.ModelProviderCacheUtil;
import com.lengbot.util.Msgs;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 模型工厂 — 基于 AgentScope Model 接口动态创建和管理模型实例。
 * <p>
 * 与 LengBot（Spring AI 版）的核心差异：
 * <ul>
 *   <li>缓存类型从 {@code ChatModel} 改为 AgentScope {@code Model}</li>
 *   <li>配置参数从 {@code ChatOptions} 改为 {@code GenerateOptions}</li>
 *   <li>调用方式从同步 {@code call()} 改为响应式 {@code Mono<ChatResponse>}</li>
 * </ul>
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelFactory {

    private final List<ModelProviderHandler> handlers;
    private final ModelProviderService modelProviderService;
    private final ModelProviderCacheUtil cacheUtil;
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;
    private final CacheInvalidationBroadcaster cacheInvalidationBroadcaster;

    /** 多实例广播的缓存域标识：model（按 providerId 索引） */
    private static final String CACHE_TYPE_MODEL = "model";
    private static final String CONNECTIVITY_CHECK_PROMPT = "你好，请回复OK";

    private Map<ModelProviderType, ModelProviderHandler> handlerMap;
    private final ConcurrentHashMap<Long, Model> modelCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        handlerMap = handlers.stream()
                .collect(Collectors.toMap(ModelProviderHandler::getProviderType, h -> h));
        // 注册多实例失效处理：其他实例广播 model 失效时，本实例同步清理本地缓存
        cacheInvalidationBroadcaster.register(CACHE_TYPE_MODEL, key -> {
            if (key == null) {
                modelCache.clear();
            } else {
                modelCache.remove(Long.parseLong(key));
            }
        });
        log.info("[ModelFactory] 已注册 {} 个模型处理器（AgentScope 引擎）: {}", handlerMap.size(), handlerMap.keySet());
    }

    /**
     * 获取 AgentScope Model 实例（按 providerId 缓存）。
     *
     * @param providerId 模型提供商 ID
     * @return AgentScope Model 实例
     */
    public Model getModel(Long providerId) {
        Long actualId = resolveProviderIdOrDefault(providerId);
        return modelCache.computeIfAbsent(actualId, id -> {
            ModelProvider provider = resolveProvider(actualId);
            ModelProviderHandler handler = getHandler(provider.getType());
            String defaultModelId = resolveModelId(provider, handler);
            log.info("[ModelFactory] 创建 AgentScope Model: providerId={}, type={}, defaultModel={}",
                    id, provider.getType(), defaultModelId);
            return handler.createModel(provider, defaultModelId);
        });
    }

    /**
     * 获取 Model 并构建指定模型的 GenerateOptions 上下文。
     *
     * @param providerId 模型提供商 ID
     * @param modelId    指定模型 ID（为空时使用默认模型）
     * @return Model 和 GenerateOptions 的封装
     */
    public ModelContext getModelWithContext(Long providerId, String modelId) {
        return getModelWithContext(providerId, modelId, null);
    }

    /**
     * 获取 Model 并构建指定模型 + 自定义参数的 GenerateOptions 上下文。
     *
     * @param providerId  模型提供商 ID
     * @param modelId     指定模型 ID
     * @param modelParams 模型参数（temperature、maxTokens 等）
     * @return Model 和 GenerateOptions 的封装
     */
    public ModelContext getModelWithContext(Long providerId, String modelId, Map<String, Object> modelParams) {
        Model model = getModel(providerId);
        Long actualId = resolveProviderIdOrDefault(providerId);
        Map<String, Object> config = new HashMap<>();
        if (modelId != null && !modelId.isBlank()) {
            config.put("modelId", modelId);
        }
        if (modelParams != null && !modelParams.isEmpty()) {
            config.putAll(modelParams);
        }
        GenerateOptions options = config.isEmpty() ? null : buildGenerateOptions(actualId, config);
        return new ModelContext(model, options);
    }

    /**
     * Model + 可选 GenerateOptions 的封装上下文。
     * <p>提供统一的调用入口，自动处理 options 的存在性。</p>
     * <p>AgentScope 的 {@link Model#stream} 是唯一调用入口（响应式 {@code Flux<ChatResponse>}），
     * 底层各厂商实现默认以 SSE 增量分片下发，因此同步调用统一走
     * {@link com.lengbot.util.ModelCalls} 做整流聚合，避免只取到最后一个分片而丢失内容。</p>
     */
    public record ModelContext(Model model, GenerateOptions options) {

        /**
         * 发起同步调用（阻塞等待结果，不带工具）。
         *
         * @param messages 消息列表（AgentScope Msg 类型）
         * @return ChatResponse 响应
         */
        public ChatResponse call(List<Msg> messages) {
            return call(messages, List.of());
        }

        /**
         * 发起同步调用（阻塞等待结果，携带工具 Schema）。
         *
         * @param messages     消息列表
         * @param toolSchemas  工具 Schema 列表（无工具时传空列表）
         * @return ChatResponse 响应
         */
        public ChatResponse call(List<Msg> messages, List<io.agentscope.core.model.ToolSchema> toolSchemas) {
            return com.lengbot.util.ModelCalls.call(model, messages, toolSchemas, options);
        }

        /**
         * 发起异步调用（返回 Mono，不带工具）。
         *
         * @param messages 消息列表
         * @return Mono&lt;ChatResponse&gt;
         */
        public Mono<ChatResponse> callAsync(List<Msg> messages) {
            return callAsync(messages, List.of());
        }

        /**
         * 发起异步调用（返回 Mono，携带工具 Schema）。
         *
         * @param messages     消息列表
         * @param toolSchemas  工具 Schema 列表
         * @return Mono&lt;ChatResponse&gt;
         */
        public Mono<ChatResponse> callAsync(List<Msg> messages, List<io.agentscope.core.model.ToolSchema> toolSchemas) {
            return com.lengbot.util.ModelCalls.callAsync(model, messages, toolSchemas, options);
        }

        /**
         * 获取底层 Model 实例。
         *
         * @return Model 实例
         */
        public Model model() {
            return model;
        }
    }

    /**
     * 根据提供商类型构建 GenerateOptions。
     *
     * @param providerId 模型提供商 ID
     * @param config     Agent 配置
     * @return GenerateOptions 实例
     */
    public GenerateOptions buildGenerateOptions(Long providerId, Map<String, Object> config) {
        Long actualId = resolveProviderIdOrDefault(providerId);
        ModelProvider provider = resolveProvider(actualId);
        ModelProviderHandler handler = getHandler(provider.getType());
        Map<String, Object> effectiveConfig = new HashMap<>(config);
        Object modelId = effectiveConfig.get("modelId");
        if (modelId == null || modelId.toString().isBlank()) {
            effectiveConfig.put("modelId", resolveModelId(provider, handler));
        }
        return handler.buildGenerateOptions(provider, effectiveConfig);
    }

    /**
     * 调整 GenerateOptions 以适配 Provider 的 API 约束。
     *
     * @param provider 模型提供商
     * @param config   Agent 配置
     * @param options  已构建的 GenerateOptions
     * @return 适配后的 GenerateOptions
     */
    public GenerateOptions adaptGenerateOptions(ModelProvider provider,
                                                 Map<String, Object> config,
                                                 GenerateOptions options) {
        if (provider == null || options == null) {
            return options;
        }
        return getHandler(provider.getType()).adaptGenerateOptions(provider, config, options);
    }

    /**
     * 判断当前 Provider 是否支持原生 API 工具调用。
     */
    public boolean supportsApiToolCalling(Long providerId, Map<String, Object> config) {
        if (providerId == null || providerId <= 0) {
            return true;
        }
        ModelProvider provider = resolveProvider(providerId);
        return getHandler(provider.getType()).supportsApiToolCalling(provider, config);
    }

    /**
     * 获取指定提供商的模型调参字段定义。
     */
    public List<ConfigField> getConfigFields(Long providerId) {
        Long actualId = resolveProviderIdOrDefault(providerId);
        ModelProvider provider = resolveProvider(actualId);
        return getHandler(provider.getType()).getConfigFields();
    }

    /**
     * 获取指定提供商的模型能力字段定义。
     */
    public List<ConfigField> getModelCapabilities(Long providerId) {
        Long actualId = resolveProviderIdOrDefault(providerId);
        ModelProvider provider = resolveProvider(actualId);
        return getHandler(provider.getType()).getModelCapabilities();
    }

    /**
     * 获取指定提供商类型的默认模型 ID。
     */
    public String getDefaultModelId(ModelProviderType type) {
        return getHandler(type).getCheapestModel();
    }

    /**
     * 清除指定提供商的 Model 缓存（凭证变更时调用）。
     */
    public void invalidateCache(Long providerId) {
        modelCache.remove(providerId);
        cacheUtil.evictProvider(providerId);
        cacheInvalidationBroadcaster.broadcast(CACHE_TYPE_MODEL, String.valueOf(providerId));
        log.info("[ModelFactory] 缓存已清除: providerId={}", providerId);
    }

    /**
     * 清除所有 Model 缓存。
     */
    public void invalidateAllCache() {
        modelCache.clear();
        cacheUtil.evictAll();
        cacheInvalidationBroadcaster.broadcast(CACHE_TYPE_MODEL, null);
        log.info("[ModelFactory] 所有缓存已清除");
    }

    /**
     * 检查模型提供商连通性（通过已保存的提供商 ID）。
     *
     * @param providerId 模型提供商 ID
     * @return 检查结果消息
     */
    public String checkConnectivity(Long providerId) {
        ModelProvider provider = resolveProvider(providerId);
        invalidateCache(providerId);
        return doCheckConnectivity(provider, null);
    }

    /**
     * 检查模型提供商连通性（通过表单实时数据，不依赖数据库）。
     *
     * @param type            提供商类型
     * @param apiKey          API 密钥
     * @param baseUrl         基础地址
     * @param modelId         默认模型 ID
     * @param completionsPath Chat Completions 请求路径
     * @return 检查结果消息
     */
    public String checkConnectivityByForm(ModelProviderType type, String apiKey, String baseUrl,
                                           String modelId, String completionsPath) {
        ModelProvider provider = new ModelProvider();
        provider.setType(type);
        provider.setApiKey(apiKey);
        provider.setBaseUrl(baseUrl);
        if (completionsPath != null && !completionsPath.isBlank()) {
            provider.setConfig(buildConnectivityConfig(completionsPath));
        }
        return doCheckConnectivity(provider, modelId);
    }

    /**
     * 联网拉取提供商下可用的模型列表（5 分钟 Redis 缓存）。
     */
    @Cacheable(value = "providerModels", key = "#providerId", unless = "#result == null")
    public List<FetchedModel> fetchModels(Long providerId) {
        ModelProvider provider = resolveProvider(providerId);
        ModelProviderHandler handler = getHandler(provider.getType());
        return handler.fetchModels(provider).stream()
                .filter(distinctByKey(FetchedModel::getModelId))
                .collect(Collectors.toList());
    }

    /**
     * 失效指定 provider 的模型列表缓存。
     */
    @CacheEvict(value = "providerModels", key = "#providerId")
    public void invalidateProviderModelsCache(Long providerId) {
        log.info("[ModelFactory] 失效模型列表缓存: providerId={}", providerId);
    }

    /**
     * 确保 config 含有效 modelId（旁路调用时避免 "unknown-model" 错误）。
     */
    public void ensureModelIdInConfig(Long providerId, Map<String, Object> config) {
        if (config == null || providerId == null) {
            return;
        }
        Object modelId = config.get("modelId");
        if (modelId != null && !modelId.toString().isBlank()
                && !"unknown-model".equalsIgnoreCase(modelId.toString().trim())) {
            return;
        }
        ModelProvider provider = resolveProvider(providerId);
        ModelProviderHandler handler = getHandler(provider.getType());
        config.put("modelId", resolveModelId(provider, handler));
    }

    /**
     * 获取所有可用的 providerId 列表。
     */
    public List<Long> getAvailableProviderIds() {
        List<ModelProvider> cached = cacheUtil.getAllProviders();
        if (!cached.isEmpty()) {
            return cached.stream().map(ModelProvider::getId).collect(Collectors.toList());
        }
        List<ModelProvider> providers = modelProviderService.list();
        if (!providers.isEmpty()) {
            cacheUtil.cacheAllProviders(providers);
        }
        return providers.stream().map(ModelProvider::getId).collect(Collectors.toList());
    }

    // ==================== 私有方法 ====================

    /**
     * 执行实际的连通性检查。
     */
    private String doCheckConnectivity(ModelProvider provider, String modelId) {
        try {
            ModelProviderHandler handler = getHandler(provider.getType());
            String checkModelId = resolveCheckModelId(provider, handler, modelId);
            Model model = handler.createModel(provider, checkModelId);
            GenerateOptions options = handler.buildGenerateOptions(provider, Map.of("modelId", checkModelId));
            Msg userMsg = Msgs.user(CONNECTIVITY_CHECK_PROMPT);

            ChatResponse response = com.lengbot.util.ModelCalls.call(model, List.of(userMsg), List.of(), options);
            log.info("[ModelFactory] 连通性检查通过: type={}, model={}", provider.getType(), checkModelId);
            return "连接成功，API Key 有效";
        } catch (Exception e) {
            log.warn("[ModelFactory] 连通性检查失败: type={}, error={}", provider.getType(), e.getMessage());
            throw new BizException(ErrorCode.MODEL_PROVIDER_CHECK_FAILED, e.getMessage());
        }
    }

    private String resolveCheckModelId(ModelProvider provider, ModelProviderHandler handler, String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            return modelId.trim();
        }
        return resolveModelId(provider, handler);
    }

    private String resolveModelId(ModelProvider provider, ModelProviderHandler handler) {
        String config = provider.getConfig();
        if (config != null && !config.isBlank()) {
            try {
                var node = objectMapper.readTree(config);
                if (node.has("modelId")) {
                    String modelId = node.get("modelId").asText("");
                    if (!modelId.isBlank()) {
                        return modelId;
                    }
                }
            } catch (Exception ignored) {
                // 配置解析失败时使用默认模型
            }
        }
        return handler.getCheapestModel();
    }

    private Long resolveProviderIdOrDefault(Long providerId) {
        if (providerId != null && providerId > 0) {
            return providerId;
        }
        Long defaultId = systemConfigService.getDefaultAiConfig().getProviderId();
        if (defaultId != null && defaultId > 0) {
            log.debug("[ModelFactory] providerId 为空，使用系统默认: {}", defaultId);
            return defaultId;
        }
        List<Long> available = getAvailableProviderIds();
        if (!available.isEmpty()) {
            log.debug("[ModelFactory] 系统默认未配置，使用第一个可用提供商: {}", available.get(0));
            return available.get(0);
        }
        throw new BizException(ErrorCode.MODEL_PROVIDER_NOT_FOUND);
    }

    private ModelProvider resolveProvider(Long providerId) {
        ModelProvider provider = cacheUtil.getProvider(providerId);
        if (provider == null) {
            provider = modelProviderService.getById(providerId);
            if (provider == null) {
                throw new BizException(ErrorCode.MODEL_PROVIDER_NOT_FOUND);
            }
            cacheUtil.cacheProvider(provider);
            log.debug("[ModelFactory] 缓存未命中，从数据库加载提供商: id={}", providerId);
        }
        return provider;
    }

    private String buildConnectivityConfig(String completionsPath) {
        try {
            return objectMapper.writeValueAsString(Map.of("completionsPath", completionsPath.trim()));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, e);
        }
    }

    private ModelProviderHandler getHandler(ModelProviderType type) {
        ModelProviderHandler handler = handlerMap.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("不支持的模型提供商类型: " + type);
        }
        return handler;
    }

    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        java.util.concurrent.ConcurrentHashMap<Object, Boolean> seen = new java.util.concurrent.ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }
}
