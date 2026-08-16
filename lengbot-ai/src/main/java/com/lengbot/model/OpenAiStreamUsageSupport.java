package com.lengbot.model;

import io.agentscope.core.model.GenerateOptions;

/**
 * OpenAI 兼容流式调用：开启 usage 统计（stream_options.include_usage）
 * <p>MiMo / DeepSeek / 百炼兼容模式等需在流式请求中显式开启，否则 Trace 无法拿到 Token</p>
 */
public final class OpenAiStreamUsageSupport {

    private OpenAiStreamUsageSupport() {
    }

    /**
     * 为 GenerateOptions 开启流式 Token 统计（对应 OpenAI stream_options.include_usage=true）
     * <p>AgentScope 的 GenerateOptions 无 streamUsage 方法，通过附加请求体参数传递。</p>
     *
     * @param builder GenerateOptions 构建器
     */
    public static void enableStreamUsage(GenerateOptions.Builder builder) {
        builder.additionalBodyParam("stream_options",
                java.util.Map.of("include_usage", true));
    }
}
