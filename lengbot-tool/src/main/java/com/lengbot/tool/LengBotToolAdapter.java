package com.lengbot.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Function;

/**
 * AgentScope ToolBase 适配器，用于将 LengBot 现有工具逻辑桥接到 AgentScope 工具系统。
 * <p>替代 Spring AI 的 ToolCallback / MethodToolCallback 模式。</p>
 *
 * <p>使用方式：
 * <pre>{@code
 * LengBotToolAdapter adapter = LengBotToolAdapter.builder()
 *     .name("my_tool")
 *     .description("...")
 *     .inputSchema(inputSchemaJson)
 *     .executor(params -> ToolResultBlock.text("result"))
 *     .build();
 * }</pre>
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
public class LengBotToolAdapter extends ToolBase {

    private final Function<ToolCallParam, ToolResultBlock> executor;

    private LengBotToolAdapter(LengBotBuilder builder) {
        super(ToolBase.builder()
                .name(builder.name)
                .description(builder.description)
                .inputSchema(builder.inputSchema)
                .readOnly(builder.readOnly)
                .concurrencySafe(builder.concurrencySafe)
                .mcp(builder.mcpName)
                .externalTool(builder.externalTool)
                .stateInjected(builder.stateInjected));
        this.executor = builder.executor;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        if (executor == null) {
            return Mono.just(ToolResultBlock.error("工具执行器未设置"));
        }
        try {
            return Mono.fromCallable(() -> executor.apply(param));
        } catch (Exception e) {
            return Mono.just(ToolResultBlock.error("工具执行异常: " + e.getMessage()));
        }
    }

    public static LengBotBuilder lengBotBuilder() {
        return new LengBotBuilder();
    }

    public static class LengBotBuilder {
        private String name;
        private String description = "";
        private Map<String, Object> inputSchema = Map.of();
        private boolean concurrencySafe = true;
        private boolean readOnly = false;
        private boolean externalTool = false;
        private boolean stateInjected = false;
        private boolean mcp = false;
        private String mcpName;
        private Function<ToolCallParam, ToolResultBlock> executor;

        public LengBotBuilder name(String name) { this.name = name; return this; }
        public LengBotBuilder description(String description) { this.description = description; return this; }
        public LengBotBuilder inputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; return this; }
        public LengBotBuilder concurrencySafe(boolean concurrencySafe) { this.concurrencySafe = concurrencySafe; return this; }
        public LengBotBuilder readOnly(boolean readOnly) { this.readOnly = readOnly; return this; }
        public LengBotBuilder externalTool(boolean externalTool) { this.externalTool = externalTool; return this; }
        public LengBotBuilder stateInjected(boolean stateInjected) { this.stateInjected = stateInjected; return this; }
        public LengBotBuilder mcp(boolean mcp) { this.mcp = mcp; return this; }
        public LengBotBuilder mcpName(String mcpName) { this.mcpName = mcpName; return this; }
        public LengBotBuilder executor(Function<ToolCallParam, ToolResultBlock> executor) {
            this.executor = executor;
            return this;
        }

        public LengBotToolAdapter build() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Tool name is required");
            }
            if (executor == null) {
                throw new IllegalArgumentException("Tool executor is required");
            }
            return new LengBotToolAdapter(this);
        }
    }
}
