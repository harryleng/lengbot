package com.lengbot.config;

import com.lengbot.model.ModelFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope Java 引擎配置。
 * <p>
 * 负责初始化 AgentScope 运行环境，将 LengBot 的 ModelFactory 与 AgentScope 的 Model 接口桥接。
 * 提供 ReActAgent 的工厂方法，供 ChatService 在每次对话时动态创建 Agent 实例。
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentScopeConfig {

    private final ModelFactory modelFactory;

    @PostConstruct
    public void init() {
        log.info("[AgentScopeConfig] AgentScope Java 引擎初始化完成");
    }

    /**
     * 创建 ReActAgent 实例。
     * <p>每次对话时根据 Agent 配置动态创建，不使用全局单例。</p>
     *
     * @param name        Agent 名称（用于日志和 Trace）
     * @param systemPrompt 系统提示词
     * @param providerId  模型提供商 ID
     * @param modelId     模型 ID（可为 null 使用默认）
     * @return ReActAgent 实例
     */
    public ReActAgent createAgent(String name, String systemPrompt, Long providerId, String modelId) {
        Model model = modelFactory.getModel(providerId);

        ReActAgent.Builder builder = ReActAgent.builder()
                .name(name)
                .model(model);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.sysPrompt(systemPrompt);
        }

        log.debug("[AgentScopeConfig] 创建 ReActAgent: name={}, providerId={}, modelId={}",
                name, providerId, modelId);
        return builder.build();
    }

    /**
     * 获取 Model 实例（用于非 Agent 场景的直接模型调用）。
     *
     * @param providerId 模型提供商 ID
     * @return AgentScope Model 实例
     */
    public Model getModel(Long providerId) {
        return modelFactory.getModel(providerId);
    }

    /**
     * 获取 ModelFactory（供其他组件直接使用）。
     *
     * @return ModelFactory 实例
     */
    @Bean
    public ModelFactory modelFactoryBean() {
        return modelFactory;
    }
}
