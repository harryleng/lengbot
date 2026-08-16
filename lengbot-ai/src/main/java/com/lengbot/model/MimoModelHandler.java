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
 * 小米 MiMo 模型处理器 — 基于 AgentScope OpenAIChatModel（兼容协议）。
 * <p>通过小米 MiMo 开放平台接入，同时发送 Authorization 和 api-key 两种认证头。</p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MimoModelHandler implements ModelProviderHandler {

    private final ObjectMapper objectMapper;

    /** 小米 MiMo 开放平台默认地址 */
    private static final String DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1";

    @Override
    public ModelProviderType getProviderType() {
        return ModelProviderType.MIMO;
    }

    @Override
    public Model createModel(ModelProvider provider) {
        return createModel(provider, getCheapestModel());
    }

    @Override
    public Model createModel(ModelProvider provider, String defaultModelId) {
        String baseUrl = (provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank())
                ? provider.getBaseUrl() : DEFAULT_BASE_URL;

        // MiMo 同时支持两种认证方式：Authorization: Bearer 和 api-key 头
        Map<String, String> extraHeaders = Map.of("api-key", provider.getApiKey());

        return OpenAIChatModel.builder()
                .apiKey(provider.getApiKey())
                .baseUrl(baseUrl)
                .modelName(defaultModelId)
                .build();
    }

    @Override
    public GenerateOptions buildGenerateOptions(ModelProvider provider, Map<String, Object> config) {
        GenerateOptions.Builder builder = GenerateOptions.builder();

        String modelId = config.containsKey("modelId")
                ? config.get("modelId").toString()
                : getCheapestModel();
        builder.modelName(modelId);

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
        return "mimo-v2.5";
    }

    @Override
    public List<ConfigField> getConfigFields() {
        List<ConfigField> fields = new ArrayList<>();
        fields.add(ConfigField.builder()
                .key("modelId").label("模型").type("select")
                .options(List.of(
                        ConfigField.Option.builder().value("mimo-v2.5-pro").label("MiMo v2.5 Pro").build(),
                        ConfigField.Option.builder().value("mimo-v2.5").label("MiMo v2.5").build(),
                        ConfigField.Option.builder().value("mimo-v2-omni").label("MiMo v2 Omni（多模态）").build(),
                        ConfigField.Option.builder().value("MiMo-7B-RL").label("MiMo-7B-RL").build(),
                        ConfigField.Option.builder().value("MiMo-7B").label("MiMo-7B").build()
                ))
                .defaultValue("mimo-v2.5-pro")
                .hint("多模态建议选用 mimo-v2.5 或 mimo-v2-omni")
                .build());
        fields.add(ConfigField.builder()
                .key("temperature").label("温度").type("slider")
                .min(0.0).max(2.0).step(0.1).defaultValue(1.0)
                .hint("值越高回答越随机创造性")
                .build());
        fields.add(ConfigField.builder()
                .key("topP").label("核采样").type("slider")
                .min(0.0).max(1.0).step(0.05).defaultValue(0.95).build());
        fields.add(ConfigField.builder()
                .key("maxTokens").label("最大 Token").type("number")
                .min(256.0).max(32768.0).step(256.0).defaultValue(4096)
                .hint("单次回答的最大长度")
                .build());
        fields.add(ConfigField.builder()
                .key("presencePenalty").label("存在惩罚").type("slider")
                .min(-2.0).max(2.0).step(0.1).defaultValue(0.0).build());
        fields.add(ConfigField.builder()
                .key("frequencyPenalty").label("频率惩罚").type("slider")
                .min(-2.0).max(2.0).step(0.1).defaultValue(0.0).build());
        return fields;
    }

    @Override
    public List<ConfigField> getModelCapabilities() {
        return AgentCapabilityConfigFields.mimoFields();
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
            Map<String, Object> response = restClient.get().uri(url).retrieve().body(Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null) return List.of();

            return data.stream()
                    .map(m -> FetchedModel.of(m.get("id").toString()))
                    .sorted(Comparator.comparing(FetchedModel::getModelId))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[MimoHandler] 拉取模型列表失败: url={}, error={}", url, e.getMessage());
            throw new RuntimeException("拉取模型列表失败: " + e.getMessage());
        }
    }

    private String resolveModelsEndpoint(ModelProvider provider) {
        if (provider.getModelsEndpoint() != null && !provider.getModelsEndpoint().isBlank()) {
            return provider.getModelsEndpoint();
        }
        return resolveBaseUrl(provider) + "/v1/models";
    }

    private void addExtraHeaders(RestClient.Builder builder, String headersJson) {
        if (headersJson == null || headersJson.isBlank()) return;
        try {
            Map<String, String> headers = objectMapper.readValue(headersJson, new TypeReference<>() {});
            headers.forEach(builder::defaultHeader);
        } catch (Exception e) {
            log.warn("[MimoHandler] 解析额外请求头失败: {}", e.getMessage());
        }
    }

    private String resolveBaseUrl(ModelProvider provider) {
        if (provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()) {
            String url = provider.getBaseUrl().replaceAll("/+$", "");
            if (url.endsWith("/v1")) url = url.substring(0, url.length() - 3);
            return url;
        }
        return DEFAULT_BASE_URL;
    }

    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
    }

    private int toInt(Object val) {
        return val instanceof Number ? ((Number) val).intValue() : Integer.parseInt(val.toString());
    }
}
