package com.lengbot.model;

import io.agentscope.core.model.GenerateOptions;

import java.util.Map;

/**
 * DashScope 模型路由辅助：识别需走 multimodal-generation 端点的模型，并构建原生 GenerateOptions。
 * <p>Qwen3.5 / Qwen3.6 及 VL 系列在 DashScope 原生 SDK 下必须使用 MultiModalConversation 接口，
 * 否则会报 {@code url error, please check url}。</p>
 */
public final class DashScopeModelSupport {

    private static final String COMPATIBLE_MODE_MARKER = "compatible-mode";

    private DashScopeModelSupport() {
    }

    /**
     * 是否使用 OpenAI 兼容模式（走 compatible-mode/v1，无需 multiModel 路由）
     *
     * @param baseUrl 提供商 baseUrl
     * @return true 表示兼容模式
     */
    public static boolean isCompatibleMode(String baseUrl) {
        return baseUrl != null && baseUrl.contains(COMPATIBLE_MODE_MARKER);
    }

    /**
     * 模型是否需走 DashScope multimodal-generation 端点（含纯文本场景的 Qwen3.5/3.6）
     *
     * @param modelId 模型 ID
     * @return true 需设置 multiModel=true
     */
    public static boolean requiresMultimodalApi(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        String m = modelId.toLowerCase();
        // Qwen3.5 / Qwen3.6 全系列（含 flash / plus 快照）均走 MultiModalConversation
        if (m.startsWith("qwen3.5") || m.startsWith("qwen3.6")) {
            return true;
        }
        // 视觉 / 多模态 VL 系列
        if (m.startsWith("qwen-vl") || m.startsWith("qwen2-vl") || m.startsWith("qwen2.5-vl")
                || m.startsWith("qwen3-vl") || m.startsWith("qvq-")) {
            return true;
        }
        if (m.contains("-vl-") || m.endsWith("-vl")) {
            return true;
        }
        return false;
    }

    /**
     * 为 GenerateOptions 设置 DashScope 原生 multimodal 路由参数
     * <p>AgentScope 下通过 GenerateOptions 携带多模型配置，由 DashScopeChatModel 解析</p>
     *
     * @param builder GenerateOptions 构建器
     * @param modelId 模型 ID
     */
    public static void applyMultimodalRouting(GenerateOptions.Builder builder, String modelId) {
        if (!requiresMultimodalApi(modelId)) {
            return;
        }
        // DashScope 多模态路由标记：AgentScope 下通过 GenerateOptions 附加请求体参数传递
        builder.additionalBodyParam("multiModel", true);
        // Qwen3.5/3.6 流式输出要求 incremental_output=true
        builder.additionalBodyParam("incrementalOutput", true);
    }

    /**
     * 构建 DashScope 原生 GenerateOptions，供对话 / SubAgent 流式调用。
     * <p>AgentScope 架构下工具（ToolBase）在模型调用层单独传入（Model.stream(List&lt;Msg&gt;, List&lt;ToolSchema&gt;, GenerateOptions)），
     * 因此本方法仅负责携带模型配置与 DashScope 多模态路由参数，不再通过 GenerateOptions 携带工具。</p>
     *
     * @param modelId    模型 ID
     * @param configMap  模型参数（temperature / topP / maxTokens 等）
     * @return GenerateOptions
     */
    public static GenerateOptions buildNativeChatOptions(String modelId,
                                                         Map<String, Object> configMap) {
        GenerateOptions.Builder builder = GenerateOptions.builder();
        if (modelId != null && !modelId.isBlank()) {
            builder.modelName(modelId);
        }
        applyConfigParams(builder, configMap);
        applyMultimodalRouting(builder, modelId);
        return builder.build();
    }

    private static void applyConfigParams(GenerateOptions.Builder builder,
                                          Map<String, Object> configMap) {
        if (configMap == null) {
            return;
        }
        if (configMap.containsKey("temperature")) {
            builder.temperature(toDouble(configMap.get("temperature")));
        }
        if (configMap.containsKey("topP")) {
            builder.topP(toDouble(configMap.get("topP")));
        }
        if (configMap.containsKey("maxTokens")) {
            builder.maxTokens(toInt(configMap.get("maxTokens")));
        }
    }

    private static double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
    }

    private static int toInt(Object val) {
        return val instanceof Number ? ((Number) val).intValue() : Integer.parseInt(val.toString());
    }
}
