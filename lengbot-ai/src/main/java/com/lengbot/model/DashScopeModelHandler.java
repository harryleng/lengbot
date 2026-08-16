package com.lengbot.model;

import com.lengbot.entity.ModelProvider;
import com.lengbot.enums.ModelProviderType;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 阿里云 DashScope（通义千问）模型处理器 — 基于 AgentScope DashScopeChatModel。
 * <p>支持 qwen-plus、qwen-max、qwen-turbo 等通义系列模型。</p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Slf4j
@Component
public class DashScopeModelHandler implements ModelProviderHandler {

    @Override
    public ModelProviderType getProviderType() {
        return ModelProviderType.DASHSCOPE;
    }

    @Override
    public Model createModel(ModelProvider provider) {
        return createModel(provider, getCheapestModel());
    }

    @Override
    public Model createModel(ModelProvider provider, String defaultModelId) {
        return DashScopeChatModel.builder()
                .apiKey(provider.getApiKey())
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
        if (config.containsKey("enableSearch")) {
            builder.additionalBodyParam("enable_search",
                    Boolean.parseBoolean(config.get("enableSearch").toString()));
        }

        return builder.build();
    }

    @Override
    public String getCheapestModel() {
        return "qwen-plus";
    }

    @Override
    public List<ConfigField> getConfigFields() {
        List<ConfigField> fields = new ArrayList<>();
        fields.add(ConfigField.builder()
                .key("modelId").label("模型").type("select")
                .options(List.of(
                        ConfigField.Option.builder().value("qwen-plus").label("Qwen-Plus（推荐）").build(),
                        ConfigField.Option.builder().value("qwen-max").label("Qwen-Max（最强）").build(),
                        ConfigField.Option.builder().value("qwen-turbo").label("Qwen-Turbo（快速）").build(),
                        ConfigField.Option.builder().value("qwen-vl-plus").label("Qwen-VL-Plus（视觉）").build(),
                        ConfigField.Option.builder().value("qwen-vl-max").label("Qwen-VL-Max（视觉增强）").build()
                ))
                .defaultValue("qwen-plus")
                .hint("视觉模型请选用 qwen-vl 系列")
                .build());
        fields.add(ConfigField.builder()
                .key("temperature").label("温度").type("slider")
                .min(0.0).max(2.0).step(0.1).defaultValue(0.7)
                .hint("值越高回答越随机")
                .build());
        fields.add(ConfigField.builder()
                .key("topP").label("核采样").type("slider")
                .min(0.0).max(1.0).step(0.05).defaultValue(0.8)
                .hint("控制词汇选择多样性")
                .build());
        fields.add(ConfigField.builder()
                .key("maxTokens").label("最大 Token").type("number")
                .min(256.0).max(8192.0).step(256.0).defaultValue(2048)
                .hint("单次回答最大长度")
                .build());
        return fields;
    }

    @Override
    public List<ConfigField> getModelCapabilities() {
        return AgentCapabilityConfigFields.dashScopeFields();
    }

    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
    }

    private int toInt(Object val) {
        return val instanceof Number ? ((Number) val).intValue() : Integer.parseInt(val.toString());
    }
}
