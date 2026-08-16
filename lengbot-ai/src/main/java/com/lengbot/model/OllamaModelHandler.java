package com.lengbot.model;

import com.lengbot.entity.ModelProvider;
import com.lengbot.enums.ModelProviderType;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ollama 本地模型处理器 — 基于 AgentScope OllamaChatModel。
 * <p>支持 llama3、qwen2.5、deepseek-r1 等本地部署模型。</p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Slf4j
@Component
public class OllamaModelHandler implements ModelProviderHandler {

    /** Ollama 支持原生工具调用的最低版本要求 */
    private static final double TOOL_CALLING_MIN_VERSION = 0.3;

    @Override
    public ModelProviderType getProviderType() {
        return ModelProviderType.OLLAMA;
    }

    @Override
    public Model createModel(ModelProvider provider) {
        return createModel(provider, getCheapestModel());
    }

    @Override
    public Model createModel(ModelProvider provider, String defaultModelId) {
        OllamaChatModel.Builder builder = OllamaChatModel.builder()
                .modelName(defaultModelId);

        if (provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()) {
            builder.baseUrl(provider.getBaseUrl());
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
        return "qwen2.5:latest";
    }

    @Override
    public List<ConfigField> getConfigFields() {
        List<ConfigField> fields = new ArrayList<>();
        fields.add(ConfigField.builder()
                .key("modelId").label("模型名称").type("text")
                .defaultValue("qwen2.5:latest")
                .hint("Ollama 模型名称，如 llama3:latest、qwen2.5:7b")
                .build());
        fields.add(ConfigField.builder()
                .key("temperature").label("温度").type("slider")
                .min(0.0).max(2.0).step(0.1).defaultValue(0.7).build());
        fields.add(ConfigField.builder()
                .key("topP").label("核采样").type("slider")
                .min(0.0).max(1.0).step(0.05).defaultValue(0.9).build());
        fields.add(ConfigField.builder()
                .key("maxTokens").label("最大 Token").type("number")
                .min(256.0).max(4096.0).step(256.0).defaultValue(2048).build());
        return fields;
    }

    @Override
    public List<ConfigField> getModelCapabilities() {
        return AgentCapabilityConfigFields.ollamaFields();
    }

    /**
     * Ollama 工具调用支持取决于 Ollama 版本和模型能力。
     * <p>默认返回 false，可通过覆盖配置启用。</p>
     */
    @Override
    public boolean supportsApiToolCalling(ModelProvider provider, Map<String, Object> config) {
        // Ollama 部分模型支持工具调用，需要根据具体模型判断
        return false;
    }

    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
    }

    private int toInt(Object val) {
        return val instanceof Number ? ((Number) val).intValue() : Integer.parseInt(val.toString());
    }
}
