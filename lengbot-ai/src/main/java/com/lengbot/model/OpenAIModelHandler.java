package com.lengbot.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.entity.ModelProvider;
import com.lengbot.enums.ModelProviderType;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OpenAI 模型处理器 — 基于 AgentScope OpenAIChatModel。
 * <p>同时兼容 DeepSeek 等 OpenAI 兼容 API 提供商。</p>
 * <p>
 * 与 LengBot（Spring AI 版）的核心差异：
 * <ul>
 *   <li>使用 {@code OpenAIChatModel}（AgentScope）替代 {@code OpenAiChatModel}（Spring AI）</li>
 *   <li>配置参数从 {@code OpenAiChatOptions} 改为 {@code GenerateOptions}</li>
 *   <li>API 客户端由 AgentScope 内部管理，不再需要手动构建 {@code OpenAiApi}</li>
 * </ul>
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAIModelHandler implements ModelProviderHandler {

    private final ObjectMapper objectMapper;

    @Override
    public ModelProviderType getProviderType() {
        return ModelProviderType.OPENAI;
    }

    @Override
    public Model createModel(ModelProvider provider) {
        return createModel(provider, getCheapestModel());
    }

    @Override
    public Model createModel(ModelProvider provider, String defaultModelId) {
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(provider.getApiKey())
                .modelName(defaultModelId);

        // 配置自定义 baseUrl（如 DeepSeek、OneAPI 等兼容服务）
        if (provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()) {
            builder.baseUrl(provider.getBaseUrl());
        }

        // 配置自定义 completions 路径（AgentScope 通过 endpointPath 注入自定义端点）
        String completionsPath = resolveCompletionsPath(provider);
        if (completionsPath != null) {
            builder.endpointPath(completionsPath);
        }

        return builder.build();
    }

    @Override
    public GenerateOptions buildGenerateOptions(ModelProvider provider, Map<String, Object> config) {
        GenerateOptions.Builder builder = GenerateOptions.builder();

        // 模型 ID：优先使用 config 中的指定值
        String modelId = config.containsKey("modelId")
                ? config.get("modelId").toString()
                : getCheapestModel();
        builder.modelName(modelId);

        // 调参
        if (config.containsKey("temperature")) {
            builder.temperature(toDouble(config.get("temperature")));
        }
        if (config.containsKey("topP")) {
            builder.topP(toDouble(config.get("topP")));
        }
        if (config.containsKey("maxTokens")) {
            builder.maxTokens(toInt(config.get("maxTokens")));
        }
        if (config.containsKey("presencePenalty")) {
            builder.presencePenalty(toDouble(config.get("presencePenalty")));
        }
        if (config.containsKey("frequencyPenalty")) {
            builder.frequencyPenalty(toDouble(config.get("frequencyPenalty")));
        }

        return builder.build();
    }

    @Override
    public String getCheapestModel() {
        return "gpt-4o-mini";
    }

    @Override
    public List<ConfigField> getConfigFields() {
        List<ConfigField> fields = new ArrayList<>();
        fields.add(ConfigField.builder()
                .key("modelId")
                .label("模型")
                .type("select")
                .options(List.of(
                        ConfigField.Option.builder().value("gpt-4o-mini").label("GPT-4o Mini（视觉）").build(),
                        ConfigField.Option.builder().value("gpt-4o").label("GPT-4o（视觉）").build(),
                        ConfigField.Option.builder().value("gpt-4-turbo").label("GPT-4 Turbo（视觉）").build(),
                        ConfigField.Option.builder().value("gpt-4").label("GPT-4").build(),
                        ConfigField.Option.builder().value("gpt-3.5-turbo").label("GPT-3.5 Turbo").build()
                ))
                .defaultValue("gpt-4o-mini")
                .hint("多模态请选用 gpt-4o / gpt-4o-mini / gpt-4-turbo 等视觉模型")
                .build());
        fields.add(ConfigField.builder()
                .key("temperature").label("温度").type("slider")
                .min(0.0).max(2.0).step(0.1).defaultValue(0.7)
                .hint("值越高回答越随机，值越低回答越确定")
                .build());
        fields.add(ConfigField.builder()
                .key("topP").label("核采样").type("slider")
                .min(0.0).max(1.0).step(0.05).defaultValue(1.0)
                .hint("控制词汇选择的多样性")
                .build());
        fields.add(ConfigField.builder()
                .key("maxTokens").label("最大 Token").type("number")
                .min(256.0).max(8192.0).step(256.0).defaultValue(2048)
                .hint("单次回答的最大长度")
                .build());
        fields.add(ConfigField.builder()
                .key("presencePenalty").label("存在惩罚").type("slider")
                .min(-2.0).max(2.0).step(0.1).defaultValue(0.0)
                .hint("正值降低重复话题的概率")
                .build());
        fields.add(ConfigField.builder()
                .key("frequencyPenalty").label("频率惩罚").type("slider")
                .min(-2.0).max(2.0).step(0.1).defaultValue(0.0)
                .hint("正值降低重复用词的概率")
                .build());
        return fields;
    }

    @Override
    public List<ConfigField> getModelCapabilities() {
        return AgentCapabilityConfigFields.openAiFields();
    }

    @Override
    public List<FetchedModel> fetchModels(ModelProvider provider) {
        String url = resolveModelsEndpoint(provider);

        try {
            RestClient.Builder clientBuilder = RestClient.builder()
                    .defaultHeader("Authorization", "Bearer " + provider.getApiKey());
            addExtraHeaders(clientBuilder, provider.getHeadersJson());
            RestClient restClient = clientBuilder.build();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null) return List.of();

            return data.stream()
                    .map(m -> FetchedModel.of(m.get("id").toString()))
                    .sorted(Comparator.comparing(FetchedModel::getModelId))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[OpenAIHandler] 拉取模型列表失败: url={}, error={}", url, e.getMessage());
            throw new RuntimeException("拉取模型列表失败: " + e.getMessage());
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 解析模型列表 API 地址。
     */
    private String resolveModelsEndpoint(ModelProvider provider) {
        if (provider.getModelsEndpoint() != null && !provider.getModelsEndpoint().isBlank()) {
            return provider.getModelsEndpoint();
        }
        return resolveBaseUrl(provider) + "/v1/models";
    }

    /**
     * 添加额外请求头（用于兼容代理服务）。
     */
    private void addExtraHeaders(RestClient.Builder builder, String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return;
        }
        try {
            Map<String, String> headers = objectMapper.readValue(headersJson, new TypeReference<>() {});
            headers.forEach(builder::defaultHeader);
        } catch (Exception e) {
            log.warn("[OpenAIHandler] 解析额外请求头失败: {}", e.getMessage());
        }
    }

    private String resolveBaseUrl(ModelProvider provider) {
        if (provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()) {
            String url = provider.getBaseUrl().replaceAll("/+$", "");
            if (url.endsWith("/v1")) {
                url = url.substring(0, url.length() - 3);
            }
            return url;
        }
        return "https://api.openai.com";
    }

    /**
     * 解析 Chat Completions 请求路径。
     */
    private String resolveCompletionsPath(ModelProvider provider) {
        Map<String, Object> config = parseProviderConfig(provider.getConfig());
        Object value = config.get("completionsPath");
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        String path = value.toString().trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    private Map<String, Object> parseProviderConfig(String config) {
        if (config == null || config.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(config, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[OpenAIHandler] 解析提供商配置失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
    }

    private int toInt(Object val) {
        return val instanceof Number ? ((Number) val).intValue() : Integer.parseInt(val.toString());
    }
}
