package com.lengbot.agent.harness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.entity.SubAgent;
import com.lengbot.entity.Tool;
import com.lengbot.model.ModelFactory;
import com.lengbot.model.ProviderResolver;
import com.lengbot.service.ToolService;
import com.lengbot.service.chat.ChatContext;
import com.lengbot.subagent.SubAgentPermissionPolicy;
import com.lengbot.subagent.spi.SubAgentDefinition;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生产级 {@link HarnessAgent} 工厂：按 LengBot agent 配置构建可承载真实会话的 HarnessAgent。
 *
 * <p>接受调用方（{@code ChatServiceImpl.streamViaHarness}）从 {@code ChatContext} 解析出的模型、
 * 系统提示词、工具集、迭代上限，组装出能带工具/会话的生产 agent。</p>
 *
 * <h3>关键配置决策</h3>
 * <ul>
 *   <li><b>工具</b>：经 {@link Toolkit#registerTool(Object)} 原生注册（{@link ToolBase} 走
 *       {@code AgentTool} 分支）。替代 legacy 分支「工具不当 ToolSchema 传给模型、手动解
 *       {@code ToolUseBlock} 派发」的 hack，让模型走真正的 function-calling。
 *       <br><b>Phase 1 待验证</b>：所有 provider（OpenAI/DashScope/Ollama/MiMo/DeepSeek）
 *       对原生工具调用的支持度与行为差异。</li>
 *   <li><b>历史</b>：{@link NoOpAgentStateStore}--LengBot 自管历史（每次传全量消息），
 *       agent 不在槽位累积，避免历史重复。{@code disableSessionPersistence()} 是 no-op，
 *       真正的开关是 {@code stateStore(...)}。</li>
 *   <li><b>副作用能力全禁</b>：fs/shell/skill/memory/workspace/compaction/toolResultEviction
 *       全关，对应职责由 LengBot 中间件体系（{@code SkillPrepMiddleware}/
 *       {@code UserMemoryService}/{@code MessageMiddleware.summarizeIfNeeded} 等）接管，
 *       避免两套机制行为漂移。</li>
 *   <li><b>子 agent（C3 Phase 1）</b>：{@code disableDynamicSubagents()} 保留——它只禁
 *       workspace {@code subagents/*.md} 的动态扫描；显式 {@code subagentFactory(name, fn)}
 *       注册走静态 {@code SubagentsMiddleware}，照常挂载 {@code agent_spawn} 工具。每个
 *       LengBot 绑定的 SubAgent 定义注册一个 factory，fn 内按「路径 A」解析模型
 *       （子 agent 独立 providerId → 继承主 agent → 系统默认）并构建子 {@link HarnessAgent}。</li>
 *   <li><b>中间件</b>：{@link MiddlewareBase} 链由调用方注入（Phase 1 把敏感词/trace 等
 *       {@code ChatMiddleware} 职责映射过来）。</li>
 * </ul>
 *
 * <h3>生命周期</h3>
 * 每次调用构建新 agent。构建是纯内存对象组装，相对一次 LLM 调用可忽略。
 * 调用方须用 {@code Flux.using} 保证 {@link HarnessAgent#close()}
 * 在完成/异常/取消时释放（参考 {@code ChatServiceImpl.streamViaHarness}）。
 *
 * <p><b>状态</b>：已接入主链路。{@code ChatServiceImpl.streamViaHarness} 的 harness 分支
 * （{@code lengbot.chat.engine=harness} 时激活）调用 {@link #build} 构建带工具的 agent，
 * 替代 legacy {@code processToolCallsRecursively}。默认 legacy，可回滚。</p>
 *
 * @author Senior Developer (LengBot refactor)
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HarnessAgentFactory {

    private final ModelFactory modelFactory;
    private final ProviderResolver providerResolver;
    private final SubAgentPermissionPolicy subAgentPermissionPolicy;
    private final ToolService toolService;
    private final ObjectMapper objectMapper;

    /** 工具循环迭代上限兜底（纯对话不触发；带工具时由调用方按 agent 配置覆盖）。 */
    private static final int DEFAULT_MAX_ITERS = 12;

    /** 子 agent 工具循环深度上限（对齐 SubAgentRuntime.MAX_LOOP_DEPTH）。 */
    private static final int MAX_SUBAGENT_LOOP_DEPTH = 6;

    /** LengBot 自管历史：agent 状态不落盘、不跨调用累积。单例即可（无状态）。 */
    private final NoOpAgentStateStore ephemeralStore = new NoOpAgentStateStore();

    /**
     * 按 providerId 解析模型后构建 agent。
     *
     * @param providerId 模型提供商 ID（null 走 ModelFactory 默认 provider）
     */
    public HarnessAgent build(Long providerId, String sysPrompt, Collection<ToolBase> tools,
                              int maxIters, List<MiddlewareBase> middlewares) {
        return build(modelFactory.getModel(providerId), sysPrompt, tools, maxIters, middlewares);
    }

    /**
     * 构建一个生产级配置的 HarnessAgent（无子 agent 注册）。
     *
     * @param model       已解析的模型（来自 {@code ctx.getChatModel()}，null 将抛异常）
     * @param sysPrompt   系统提示词（null/空则不设置，依赖 messages 内已含的 system 消息）
     * @param tools       工具集（{@link ToolBase}，来自 {@code ctx.toolCallbackMap}；null/空则无工具）
     * @param maxIters    工具循环上限（&le;0 用默认）
     * @param middlewares {@link MiddlewareBase} 链（null 则无）
     * @return 已 build 的 HarnessAgent，调用方负责 close()
     */
    public HarnessAgent build(Model model, String sysPrompt, Collection<ToolBase> tools,
                              int maxIters, List<MiddlewareBase> middlewares) {
        return build(model, sysPrompt, tools, maxIters, middlewares, null, null, null);
    }

    /**
     * 构建一个生产级配置的 HarnessAgent，并注册 LengBot 绑定的子 agent（C3 Phase 1）。
     *
     * @param model              已解析的模型（null 将抛异常）
     * @param sysPrompt          系统提示词（null/空则不设置）
     * @param tools              工具集（null/空则无工具）
     * @param maxIters           工具循环上限（&le;0 用默认）
     * @param middlewares        {@link MiddlewareBase} 链（null 则无）
     * @param options            GenerateOptions（null 则用模型默认参数）
     * @param subAgentDefinitions 绑定的子 agent 定义（name → 定义；null/空则不注册）
     * @param chatContext        当前会话上下文（供子 agent 继承 providerId/configMap；可 null）
     * @return 已 build 的 HarnessAgent，调用方负责 close()
     */
    public HarnessAgent build(Model model, String sysPrompt, Collection<ToolBase> tools,
                              int maxIters, List<MiddlewareBase> middlewares,
                              GenerateOptions options,
                              Map<String, SubAgentDefinition> subAgentDefinitions,
                              ChatContext chatContext) {
        if (model == null) {
            throw new IllegalStateException("HarnessAgentFactory: model 为空，无法构建 agent");
        }

        Toolkit toolkit = new Toolkit();
        if (tools != null) {
            for (ToolBase t : tools) {
                if (t != null) {
                    toolkit.registerTool(t);
                }
            }
        }

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("lengbot-chat")
                .agentId("lengbot-chat")
                .description("LengBot 生产 HarnessAgent（工具/历史由 LengBot 中间件体系管理）")
                .model(model)
                .toolkit(toolkit)
                .stateStore(ephemeralStore)
                .maxIters(maxIters > 0 ? maxIters : DEFAULT_MAX_ITERS)
                // ---- 禁用 harness 自带副作用能力，由 LengBot 中间件体系接管 ----
                .disableFilesystemTools()
                .disableShellTool()
                .disableDynamicSkills()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableCompaction()            // LengBot 自管上下文压缩
                .disableToolResultEviction()    // LengBot 自管工具结果裁剪
                // 只禁 workspace subagents/*.md 动态扫描；subagentFactory 显式注册走静态 SubagentsMiddleware，
                // agent_spawn 工具照常挂载（C3 Phase 1 子 agent 原生调度依赖它）
                .disableDynamicSubagents()
                .enableMetaTool(false)
                .stopOnReject(false);

        if (options != null) {
            builder.generateOptions(options);
        }
        // 能力边界：按实际装配的工具集声明缺失能力，避免 agent 反复探测/加载技能获取不存在的能力
        // （复现场景：present_artifacts 只校验已存在文件 + load_skill_through_path 反复探测全失败）
        String capabilityBoundary = buildCapabilityBoundaryAppendix(tools);
        if (capabilityBoundary != null && !capabilityBoundary.isBlank()) {
            sysPrompt = (sysPrompt == null || sysPrompt.isBlank()) ? capabilityBoundary : sysPrompt + capabilityBoundary;
        }
        if (sysPrompt != null && !sysPrompt.isBlank()) {
            builder.sysPrompt(sysPrompt);
        }
        if (middlewares != null) {
            for (MiddlewareBase m : middlewares) {
                if (m != null) {
                    builder.middleware(m);
                }
            }
        }
        if (subAgentDefinitions != null) {
            for (SubAgentDefinition def : subAgentDefinitions.values()) {
                if (def != null && def.name() != null) {
                    // fn 入参是注册名（harness 桥接时固定 apply(custom.name())），按定义构建即可
                    builder.subagentFactory(def.name(), name -> buildSubAgent(def, chatContext));
                }
            }
        }

        HarnessAgent agent = builder.build();
        log.debug("[HarnessAgentFactory] 构建 agent: tools={}, maxIters={}, middlewares={}, sysPromptLen={}, boundaryLen={}, subAgents={}",
                toolkit.getToolNames().size(), maxIters > 0 ? maxIters : DEFAULT_MAX_ITERS,
                middlewares != null ? middlewares.size() : 0,
                sysPrompt != null ? sysPrompt.length() : 0,
                capabilityBoundary != null ? capabilityBoundary.length() : 0,
                subAgentDefinitions != null ? subAgentDefinitions.size() : 0);
        return agent;
    }

    /**
     * 按实际装配的工具集生成能力边界声明（追加到系统提示词）。
     *
     * <p>当环境中缺失文件写入/代码执行能力时，明确告知 agent 不要通过探测/加载技能等方式
     * 尝试获取不存在的能力，避免「发现没能力后仍反复调工具」的无效循环
     * （复现场景：present_artifacts 只校验已存在文件，无法创建文件；load_skill_through_path 反复探测全失败）。</p>
     *
     * <p>能力判定与实现解耦：此处仅声明缺失项，不强制 agent 用特定工具补能力——
     * 缺失时给出「说明 + 替代方案」而不是继续试错。</p>
     *
     * @param tools 实际装配给该 agent 的工具集
     * @return 能力边界附录（无缺失能力时返回空串）
     */
    private static String buildCapabilityBoundaryAppendix(Collection<ToolBase> tools) {
        Set<String> names = new HashSet<>();
        if (tools != null) {
            for (ToolBase t : tools) {
                if (t != null && t.getName() != null) {
                    names.add(t.getName());
                }
            }
        }

        boolean hasWrite = names.contains("sandbox_write_file") || names.contains("sandbox_append_file");
        boolean hasCodeExec = names.contains("execute_code");

        // 有代码执行能力时 agent 可经代码写文件，故「缺文件写入」只在写工具与代码执行都缺失时才声明
        StringBuilder missing = new StringBuilder();
        if (!hasWrite && !hasCodeExec) {
            missing.append("- 文件写入：无法在环境中生成或保存文件");
        }
        if (!hasCodeExec) {
            if (missing.length() > 0) {
                missing.append("\n");
            }
            missing.append("- 代码执行：无法在环境中运行脚本或代码");
        }
        if (missing.length() == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n\n【能力边界·重要】\n你的当前环境不具备以下能力：\n");
        sb.append(missing).append("\n");
        sb.append("因此请遵守：\n");
        sb.append("1. 用户要求生成文件、运行代码时，直接说明你无法在环境中完成，并提供替代方案")
                .append("（如给出完整脚本或步骤让用户自行执行），不要反复尝试探测或加载技能来获取这些能力。\n");
        if (!hasWrite && !hasCodeExec && names.contains("present_artifacts")) {
            sb.append("2. 不要调用 present_artifacts 交付不存在的文件")
                    .append("（该工具只校验/展示已存在文件，无法创建文件）。\n");
        }
        return sb.toString();
    }

    /**
     * 构建一个 LengBot 子 agent（harness agent_spawn 的 factory 目标）。
     *
     * <p>Model 融合路径 A：子 agent 独立 providerId/llmModel 优先，否则继承主 agent
     * （闭包捕获的 {@code chatContext}），再落到系统默认。工具经
     * {@link SubAgentPermissionPolicy} 权限过滤后原生注册。子 agent 不再递归注册 LengBot
     * 子 agent（但 harness 静态 SubagentsMiddleware 会为其挂载 general-purpose/agent_spawn）。</p>
     */
    private HarnessAgent buildSubAgent(SubAgentDefinition def, ChatContext chatContext) {
        SubAgent sa = def.source();
        Long providerId = resolveSubAgentProviderId(sa, chatContext);
        String modelId = resolveSubAgentModelId(sa, chatContext);
        ModelFactory.ModelContext mc = modelFactory.getModelWithContext(providerId, modelId, null);
        List<ToolBase> tools = resolveSubAgentTools(sa);
        String sysPrompt = sa.getSystemPrompt();
        HarnessAgent child = build(mc.model(), sysPrompt, tools, MAX_SUBAGENT_LOOP_DEPTH, List.of(),
                mc.options(), null, null);
        log.debug("[HarnessAgentFactory] 构建子 agent: name={}, providerId={}, modelId={}, tools={}",
                sa.getName(), providerId, modelId, tools.size());
        return child;
    }

    /** 子 agent providerId：独立配置优先 → 继承主 agent → 系统默认。 */
    private Long resolveSubAgentProviderId(SubAgent sa, ChatContext chatContext) {
        if (sa.getModelId() != null) {
            return sa.getModelId();
        }
        if (chatContext != null && chatContext.getProviderId() != null) {
            return chatContext.getProviderId();
        }
        return providerResolver.resolve();
    }

    /** 子 agent 模型名覆盖：独立 llmModel 优先 → 继承主 agent configMap.modelId → null（provider 默认）。 */
    private String resolveSubAgentModelId(SubAgent sa, ChatContext chatContext) {
        if (sa.getModelId() != null) {
            return (sa.getLlmModel() != null && !sa.getLlmModel().isBlank()) ? sa.getLlmModel() : null;
        }
        if (chatContext != null && chatContext.getConfigMap() != null) {
            Object m = chatContext.getConfigMap().get("modelId");
            return m != null ? m.toString() : null;
        }
        return null;
    }

    /** 解析并权限过滤子 agent 的工具集。 */
    private List<ToolBase> resolveSubAgentTools(SubAgent sa) {
        try {
            List<String> idStrings = parseToolIds(sa.getToolIds());
            if (idStrings.isEmpty()) {
                return List.of();
            }
            List<Long> toolIds = idStrings.stream().map(Long::valueOf).toList();
            List<Tool> boundTools = toolService.listByIds(toolIds);
            List<Long> executableIds = subAgentPermissionPolicy.filterExecutableToolIds(sa, boundTools);
            if (executableIds.isEmpty()) {
                return List.of();
            }
            return toolService.resolveToolCallbacksByIds(executableIds);
        } catch (Exception e) {
            log.warn("[HarnessAgentFactory] 解析子 agent 工具失败: name={}, error={}",
                    sa.getName(), e.getMessage());
            return List.of();
        }
    }

    private List<String> parseToolIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[HarnessAgentFactory] 解析 toolIds JSON 失败: {}", e.getMessage());
            return List.of();
        }
    }
}
