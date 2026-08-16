package com.lengbot.model;

import com.lengbot.entity.ModelProvider;
import com.lengbot.enums.ModelProviderType;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 模型处理器 — 基于 AgentScope OpenAIChatModel（OpenAI 兼容 API）。
 * <p>支持 deepseek-chat、deepseek-reasoner 等模型。</p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Slf4j
@Component
public class DeepSeekModelHandler implements ModelProviderHandler {

    @Override
    public ModelProviderType getProviderType() {
        return ModelProviderType.DEEPSEEK;
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

        if (provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()) {
            builder.baseUrl(provider.getBaseUrl());
        } else {
            builder.baseUrl("https://api.deepseek.com/v1");
        }

        return builder.build();
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

        return builder.build();
    }

    @Override
    public String getCheapestModel() {
        return "deepseek-chat";
    }

    @Override
    public List<ConfigField> getConfigFields() {
        List<ConfigField> fields = new ArrayList<>();
        fields.add(ConfigField.builder()
                .key("modelId").label("模型").type("select")
                .options(List.of(
                        ConfigField.Option.builder().value("deepseek-chat").label("DeepSeek-Chat（推荐）").build(),
                        ConfigField.Option.builder().value("deepseek-reasoner").label("DeepSeek-Reasoner（推理增强）").build()
                ))
                .defaultValue("deepseek-chat")
                .build());
        fields.add(ConfigField.builder()
                .key("temperature").label("温度").type("slider")
                .min(0.0).max(2.0).step(0.1).defaultValue(0.7).build());
        fields.add(ConfigField.builder()
                .key("topP").label("核采样").type("slider")
                .min(0.0).max(1.0).step(0.05).defaultValue(1.0).build());
        fields.add(ConfigField.builder()
                .key("maxTokens").label("最大 Token").type("number")
                .min(256.0).max(8192.0).step(256.0).defaultValue(4096).build());
        return fields;
    }

    @Override
    public List<ConfigField> getModelCapabilities() {
        return List.of();
    }

    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
    }

    private int toInt(Object val) {
        return val instanceof Number ? ((Number) val).intValue() : Integer.parseInt(val.toString());
    }
}
