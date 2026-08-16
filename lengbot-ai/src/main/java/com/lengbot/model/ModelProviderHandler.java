package com.lengbot.model;

import com.lengbot.entity.ModelProvider;
import com.lengbot.enums.ModelProviderType;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;

import java.util.List;
import java.util.Map;

/**
 * 模型提供商处理器接口。
 * <p>
 * 每个模型提供商（OpenAI、DashScope、Ollama 等）实现此接口，
 * 负责基于数据库中的凭证信息创建 AgentScope Model 实例、
 * 构建 GenerateOptions（temperature、topP 等调参）、
 * 以及拉取可用模型列表。
 * </p>
 * <p>
 * 与 LengBot（Spring AI 版）的核心差异：
 * <ul>
 *   <li>返回类型从 {@code ChatModel} (Spring AI) 改为 {@code Model} (AgentScope)</li>
 *   <li>配置参数从 {@code ChatOptions} 改为 {@code GenerateOptions}</li>
 *   <li>工具调用选项从 {@code ToolCallingChatOptions} 改为 AgentScope 原生工具注册机制</li>
 * </ul>
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
public interface ModelProviderHandler {

    /**
     * 获取处理器对应的提供商类型。
     *
     * @return 提供商类型枚举
     */
    ModelProviderType getProviderType();

    /**
     * 根据提供商凭证创建 AgentScope Model 实例。
     * <p>Model 实例由 AgentScope 的 {@code ModelRegistry} 统一管理。</p>
     *
     * @param provider 提供商实体（含 apiKey、baseUrl 等凭证信息）
     * @return AgentScope Model 实例
     */
    Model createModel(ModelProvider provider);

    /**
     * 创建 Model 并设置默认 modelId。
     * <p>用于避免 API 调用时出现 "unknown-model" 错误。</p>
     *
     * @param provider       提供商实体
     * @param defaultModelId 默认模型 ID
     * @return AgentScope Model 实例
     */
    default Model createModel(ModelProvider provider, String defaultModelId) {
        return createModel(provider);
    }

    /**
     * 根据 Agent 配置构建 GenerateOptions。
     * <p>包括 temperature、topP、maxTokens 等模型调参。</p>
     *
     * @param provider 提供商实体
     * @param config   Agent 配置（从数据库 JSONB 字段解析）
     * @return GenerateOptions 实例
     */
    GenerateOptions buildGenerateOptions(ModelProvider provider, Map<String, Object> config);

    /**
     * 获取该提供商支持的模型调参字段定义。
     * <p>用于前端 Agent 配置页面动态渲染表单（temperature、topP 等滑块）。</p>
     *
     * @return 配置字段列表
     */
    List<ConfigField> getConfigFields();

    /**
     * 获取该提供商的模型能力字段定义。
     * <p>包括多模态支持、联网搜索、深度思考等能力开关。</p>
     *
     * @return 能力字段列表
     */
    List<ConfigField> getModelCapabilities();

    /**
     * 联网拉取该提供商下可用的模型列表。
     *
     * @param provider 提供商实体
     * @return 模型信息列表（含模型 ID 和类型推断）
     */
    default List<FetchedModel> fetchModels(ModelProvider provider) {
        return List.of();
    }

    /**
     * 获取该提供商最便宜的模型 ID。
     * <p>用于连通性检查、标题生成等低成本场景的默认模型选择。</p>
     *
     * @return 模型 ID
     */
    String getCheapestModel();

    /**
     * 判断当前提供商 + 模型组合是否支持原生 API 工具调用（function calling）。
     * <p>默认返回 {@code true}；Ollama 等需按模型能力判断。</p>
     *
     * @param provider 提供商实体
     * @param config   模型配置
     * @return 是否支持原生工具调用
     */
    default boolean supportsApiToolCalling(ModelProvider provider, Map<String, Object> config) {
        return true;
    }

    /**
     * 按提供商能力调整 GenerateOptions 以适配底层 API 约束。
     * <p>默认原样返回；各 Handler 可覆写（如 Ollama 剔除不支持的参数）。</p>
     *
     * @param provider 提供商实体
     * @param config   Agent 配置
     * @param options  已构建的 GenerateOptions
     * @return 适配后的 GenerateOptions
     */
    default GenerateOptions adaptGenerateOptions(ModelProvider provider,
                                                  Map<String, Object> config,
                                                  GenerateOptions options) {
        return options;
    }
}
