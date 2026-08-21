package com.lengbot.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.common.BizException;
import com.lengbot.entity.Agent;
import com.lengbot.enums.AgentStatus;
import com.lengbot.enums.ErrorCode;
import org.springframework.util.StringUtils;
import com.lengbot.mapper.AgentMapper;
import com.lengbot.model.ModelFactory;
import com.lengbot.model.ProviderResolver;
import com.lengbot.entity.McpServer;
import com.lengbot.dto.AgentChatCapabilitiesDTO;
import com.lengbot.dto.AgentSaveDTO;
import com.lengbot.service.AgentService;
import com.lengbot.util.AgentChatCapabilitiesUtil;
import com.lengbot.util.AgentChatRuntimeConfigUtil;
import com.lengbot.workflow.WorkflowConfigParser;
import com.lengbot.service.McpServerService;
import com.lengbot.service.SystemConfigService;
import com.lengbot.service.ToolService;
import com.lengbot.entity.Tool;
import com.lengbot.util.LlmTraceContext;
import com.lengbot.util.MinioUtil;
import com.lengbot.util.WorkflowExampleTemplates;
import com.lengbot.vo.WorkflowExampleVO;
import com.lengbot.dto.WorkflowGraphDTO;
import com.lengbot.vo.MentionGroupVO;
import com.lengbot.vo.MentionOptionsVO;
import com.lengbot.dto.MentionResourceDTO;
import com.lengbot.enums.AgentType;
import com.lengbot.enums.CommonStatus;
import com.lengbot.enums.MentionResourceType;
import com.lengbot.service.AgentVersionService;
import com.lengbot.service.ChatSessionService;
import com.lengbot.service.KnowledgeService;
import com.lengbot.service.SkillService;
import com.lengbot.service.SubAgentService;
import com.lengbot.config.RedisCacheConfig;
import org.springframework.beans.factory.ObjectProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.model.Model;
import com.lengbot.util.ModelCalls;
import io.agentscope.core.model.ChatResponse;
import com.lengbot.util.Msgs;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Agent服务实现类
 *
 * @author lw
 * @since 2026-05-19
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl extends ServiceImpl<AgentMapper, Agent>
        implements AgentService {

    private final ModelFactory modelFactory;
    private final ObjectMapper objectMapper;
    private final MinioUtil minioUtil;
    private final ToolService toolService;
    private final McpServerService mcpServerService;
    private final SystemConfigService systemConfigService;
    private final AgentVersionService agentVersionService;
    private final ObjectProvider<ChatSessionService> chatSessionServiceProvider;
    private final ProviderResolver providerResolver;
    private final KnowledgeService knowledgeService;
    private final SkillService skillService;
    private final SubAgentService subAgentService;

    private static final String GENERATE_PROMPT_SYSTEM = """
            你是一个AI助手提示词生成专家。根据用户提供的Agent名称和描述，生成一段专业的系统提示词。
            要求：
            - 提示词应定义Agent的角色、能力范围和行为规范
            - 语言简洁专业，不超过500字
            - 直接输出提示词内容，不要加任何前缀或解释
            """;

    private static final String GENERATE_QUESTIONS_SYSTEM = """
            你是一个推荐问题生成助手。根据用户提供的Agent名称和描述，生成3个推荐问题。
            严格规则：
            - 只输出JSON数组，不要任何其他文字
            - 恰好3个问题，每个问题不超过30个字
            - 问题应与Agent的功能和领域相关
            - 格式：["问题1","问题2","问题3"]
            """;

    @Override
    @Cacheable(value = RedisCacheConfig.CACHE_AGENT, key = "#id", unless = "#result == null")
    public Agent getById(Serializable id) {
        return super.getById(id);
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_AGENT, key = "#entity.id")
    public boolean updateById(Agent entity) {
        return super.updateById(entity);
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_AGENT, allEntries = true)
    public Agent create(AgentSaveDTO request) {
        // 1. 校验名称唯一性
        long count = count(new LambdaQueryWrapper<Agent>().eq(Agent::getName, request.getName()));
        if (count > 0) {
            throw new BizException(ErrorCode.AGENT_NAME_EXISTS);
        }

        // 2. DTO → Entity，仅设置用户可编辑字段
        Agent agent = new Agent();
        agent.setName(request.getName());
        agent.setDescription(request.getDescription());
        agent.setSystemPrompt(request.getSystemPrompt());
        agent.setWelcomeMessage(request.getWelcomeMessage());
        agent.setRecommendedQuestions(request.getRecommendedQuestions());
        agent.setAvatar(request.getAvatar());
        agent.setIcon(request.getIcon());
        agent.setAgentType(request.getAgentType());
        agent.setConfig(request.getConfig());

        // 3. 初始化内部字段
        agent.setUserId(StpUtil.getLoginIdAsLong());
        agent.setStatus(AgentStatus.DRAFT);
        agent.setVersion(0);
        save(agent);
        agentVersionService.initDraftOnCreate(agent);
        return agent;
    }

    /**
     * 校验当前登录用户对 Agent 的访问权（owner / 默认 Agent / 管理员）
     *
     * @param id Agent ID
     * @return 已通过校验的 Agent 实体
     */
    private Agent checkOwnership(Long id) {
        Agent agent = getById(id);
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        long currentUserId = StpUtil.getLoginIdAsLong();
        boolean isAdmin = StpUtil.hasRole("admin");
        boolean isOwner = Objects.equals(agent.getUserId(), currentUserId);
        boolean isDefault = Boolean.TRUE.equals(agent.getIsDefault());
        if (!isAdmin && !isOwner && !isDefault) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return agent;
    }

    @Override
    public Agent ensureAccessible(Long id) {
        return checkOwnership(id);
    }

    @Override
    public Agent clone(Long id) {
        // 1. 校验源Agent存在性 + 所有权
        Agent source = checkOwnership(id);

        // 2. 生成唯一名称：原名 + "(副本)"，冲突时追加序号
        String baseName = source.getName() + "(副本)";
        String cloneName = baseName;
        int seq = 2;
        while (count(new LambdaQueryWrapper<Agent>().eq(Agent::getName, cloneName)) > 0) {
            cloneName = baseName + seq;
            seq++;
        }

        // 3. 深拷贝Agent（config JSONB 已包含所有绑定关系）
        Agent clone = new Agent();
        clone.setName(cloneName);
        clone.setDescription(source.getDescription());
        clone.setSystemPrompt(source.getSystemPrompt());
        clone.setWelcomeMessage(source.getWelcomeMessage());
        clone.setRecommendedQuestions(source.getRecommendedQuestions());
        clone.setIcon(source.getIcon());
        clone.setAgentType(source.getAgentType());
        clone.setConfig(source.getConfig());
        clone.setUserId(StpUtil.getLoginIdAsLong());
        clone.setStatus(AgentStatus.DRAFT);
        clone.setVersion(0);
        save(clone);

        // 4. 初始化草稿版本快照（initDraftOnCreate 对工作流型创建空图）
        agentVersionService.initDraftOnCreate(clone);

        // 5. 工作流型Agent：从源Agent草稿复制工作流图（节点/边/全局配置），覆盖上面创建的空图
        if (source.getAgentType() == AgentType.WORKFLOW) {
            agentVersionService.cloneWorkflowDraft(source.getId(), clone.getId());
        }

        log.info("[Agent] 克隆成功: sourceId={}, cloneId={}, name={}", id, clone.getId(), cloneName);
        return clone;
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_AGENT_BINDING, allEntries = true)
    public Agent update(AgentSaveDTO request) {
        // 1. 校验存在性 + 所有权
        Agent existing = checkOwnership(request.getId());

        // 2. 名称变更时校验唯一性
        if (!existing.getName().equals(request.getName())) {
            long count = count(new LambdaQueryWrapper<Agent>().eq(Agent::getName, request.getName()));
            if (count > 0) {
                throw new BizException(ErrorCode.AGENT_NAME_EXISTS);
            }
        }

        // 3. 更新允许修改的字段
        existing.setName(request.getName());
        existing.setDescription(request.getDescription());
        existing.setSystemPrompt(request.getSystemPrompt());
        existing.setWelcomeMessage(request.getWelcomeMessage());
        existing.setRecommendedQuestions(request.getRecommendedQuestions());
        existing.setAvatar(request.getAvatar());
        existing.setIcon(request.getIcon());
        existing.setAgentType(request.getAgentType());
        existing.setConfig(request.getConfig());
        updateById(existing);
        if (existing.getAgentType() != null && existing.getAgentType() != com.lengbot.enums.AgentType.WORKFLOW) {
            agentVersionService.saveChatDraft(existing.getId());
        }
        return existing;
    }

    @Override
    public Page<Agent> listMyAgents(int pageNum, int pageSize, String name, String agentType, boolean includeDefault) {
        long userId = StpUtil.getLoginIdAsLong();
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<Agent>()
                .and(includeDefault
                        ? w -> w.eq(Agent::getUserId, userId).or().eq(Agent::getIsDefault, true)
                        : w -> w.eq(Agent::getUserId, userId))
                .like(StringUtils.hasText(name), Agent::getName, name)
                .orderByDesc(Agent::getIsDefault)
                .orderByDesc(Agent::getCreateTime);
        if (StringUtils.hasText(agentType)) {
            wrapper.eq(Agent::getAgentType, AgentType.fromValue(agentType));
        }
        return baseMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public Map<String, Object> getAgentDetail(Long id) {
        // 1. 获取 Agent 信息 + 所有权校验
        Agent agent = checkOwnership(id);

        // 2. 获取绑定的知识库 ID 列表（转为字符串避免前端 Long 精度丢失）
        List<Long> knowledgeIds = getKnowledgeIds(id);
        List<String> knowledgeIdStrs = knowledgeIds.stream()
                .map(String::valueOf)
                .toList();

        // 3. 获取绑定的 MCP Server ID 列表（转为字符串避免前端 Long 精度丢失）
        List<Long> mcpServerIds = getMcpServerIds(id);
        List<String> mcpServerIdStrs = mcpServerIds.stream()
                .map(String::valueOf)
                .toList();

        // 4. 获取绑定的 SubAgent ID 列表（转为字符串避免前端 Long 精度丢失）
        List<Long> subAgentIds = getSubAgentIds(id);
        List<String> subAgentIdStrs = subAgentIds.stream()
                .map(String::valueOf)
                .toList();

        // 5. 获取绑定的 Skill ID 列表
        List<Long> skillIds = getSkillIds(id);
        List<String> skillIdStrs = skillIds.stream()
                .map(String::valueOf)
                .toList();

        // 6. 对话能力（多模态/联网等）
        Map<String, Object> configMap = WorkflowConfigParser.parseConfigMap(agent.getConfig(), objectMapper);
        AgentChatCapabilitiesDTO chatCapabilities = AgentChatCapabilitiesUtil.fromConfigMap(configMap);

        // 7. 组装返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("agent", agent);
        result.put("knowledgeIds", knowledgeIdStrs);
        result.put("mcpServerIds", mcpServerIdStrs);
        result.put("subAgentIds", subAgentIdStrs);
        result.put("skillIds", skillIdStrs);
        result.put("chatCapabilities", chatCapabilities);
        return result;
    }

    @Override
    public AgentChatCapabilitiesDTO getChatCapabilities(Long id, Integer configVersion) {
        Agent agent = getById(id);
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        Map<String, Object> runtimeConfig = AgentChatRuntimeConfigUtil.resolveForChat(
                agent, configVersion, agentVersionService, objectMapper);
        return AgentChatCapabilitiesUtil.fromConfigMap(runtimeConfig);
    }

    @Override
    @CacheEvict(value = {RedisCacheConfig.CACHE_AGENT, RedisCacheConfig.CACHE_AGENT_BINDING}, allEntries = true)
    public void deleteById(Long id) {
        Agent agent = checkOwnership(id);
        // 1. 级联删除版本记录
        safeRemove(() -> agentVersionService.deleteByAgentId(id), "AgentVersion");
        // 2. 级联删除会话（含消息、ToolCall、Trace）
        safeRemove(() -> chatSessionServiceProvider.getObject().deleteByAgentId(id), "ChatSession");
        // 3. 删除 MinIO 中的头像文件
        safeRemove(() -> deleteOldAvatar(agent.getAvatar()), "MinIO头像");
        // 4. 删除 Agent
        removeById(id);
    }

    @Override
    public String generateSystemPrompt(Long id) {
        // 1. 校验Agent存在性 + 所有权
        Agent agent = checkOwnership(id);

        // 2. 构建提示词
        String userMessage = String.format("Agent名称：%s\nAgent描述：%s",
                agent.getName(),
                agent.getDescription() != null ? agent.getDescription() : "暂无描述");

        List<io.agentscope.core.message.Msg> messages = new ArrayList<>();
        messages.add(Msgs.system(GENERATE_PROMPT_SYSTEM));
        messages.add(Msgs.user(userMessage));

        // 3. 调用AI生成
        Long providerId = providerResolver.resolve();
        Model model = modelFactory.getModel(providerId);
        String result;
        try {
            ChatResponse response = LlmTraceContext.callWithoutTrace(() -> ModelCalls.call(model, messages));
            result = Msgs.extractText(response).trim();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Agent] AI生成系统提示词失败: agentId={}, error={}", id, e.getMessage());
            throw new BizException(ErrorCode.AI_GENERATE_FAILED);
        }

        log.info("[Agent] AI生成系统提示词: agentId={}", id);
        return result;
    }

    @Override
    public String generateRecommendedQuestions(Long id) {
        // 1. 校验Agent存在性
        Agent agent = getById(id);
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }

        // 2. 构建提示词
        String userMessage = String.format("Agent名称：%s\nAgent描述：%s",
                agent.getName(),
                agent.getDescription() != null ? agent.getDescription() : "暂无描述");

        List<io.agentscope.core.message.Msg> messages = new ArrayList<>();
        messages.add(Msgs.system(GENERATE_QUESTIONS_SYSTEM));
        messages.add(Msgs.user(userMessage));

        // 3. 调用AI生成
        Long providerId = providerResolver.resolve();
        Model model = modelFactory.getModel(providerId);
        String json;
        try {
            ChatResponse response = LlmTraceContext.callWithoutTrace(() -> ModelCalls.call(model, messages));
            json = Msgs.extractText(response).trim();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Agent] AI生成推荐问题失败: agentId={}, error={}", id, e.getMessage());
            throw new BizException(ErrorCode.AI_GENERATE_FAILED);
        }

        // 4. 清理可能的markdown代码块标记
        json = json.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();

        // 5. 验证JSON格式
        try {
            List<String> questions = objectMapper.readValue(json, new TypeReference<>() {});
            // 验证约束：最多3个，每个不超过30字
            if (questions.size() > 3) {
                questions = questions.subList(0, 3);
            }
            json = objectMapper.writeValueAsString(questions);
        } catch (Exception e) {
            log.warn("[Agent] 推荐问题JSON解析失败: {}", e.getMessage());
            throw new BizException(ErrorCode.AI_GENERATE_FAILED);
        }

        log.info("[Agent] AI生成推荐问题: agentId={}", id);
        return json;
    }

    @Override
    @Cacheable(value = RedisCacheConfig.CACHE_AGENT_BINDING, key = "#agentId + ':knowledgeIds'")
    public List<Long> getKnowledgeIds(Long agentId) {
        Agent agent = getById(agentId);
        if (agent == null || agent.getConfig() == null || agent.getConfig().isBlank()) {
            return new ArrayList<>();
        }
        try {
            var configNode = objectMapper.readTree(agent.getConfig());
            if (!configNode.has("knowledges")) {
                return new ArrayList<>();
            }
            // 逐个用 JsonNode.longValue() 转换，避免 ObjectMapper.convertValue 对大数精度丢失
            var knowledgesNode = configNode.get("knowledges");
            List<Long> ids = new ArrayList<>();
            for (var node : knowledgesNode) {
                if (node.isNumber()) {
                    ids.add(node.longValue());
                } else if (node.isTextual()) {
                    String text = node.asText();
                    if (text != null && !text.isBlank()) {
                        ids.add(Long.parseLong(text));
                    }
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("[Agent] 解析config.knowledges失败: agentId={}, error={}", agentId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_AGENT_BINDING, key = "#agentId + ':knowledgeIds'")
    public void updateKnowledgeBindings(Long agentId, List<Long> knowledgeIds) {
        // 0. 数量上限校验（业务规则，下沉到 Service）
        if (knowledgeIds != null && knowledgeIds.size() > 10) {
            throw new BizException(ErrorCode.AGENT_KNOWLEDGE_LIMIT);
        }
        Agent agent = checkOwnership(agentId);
        try {
            // 1. 解析现有config
            var configNode = objectMapper.readTree(
                    agent.getConfig() != null ? agent.getConfig() : "{}");

            // 2. 更新knowledges字段
            List<String> idStrs = knowledgeIds != null
                    ? knowledgeIds.stream().map(String::valueOf).toList()
                    : List.of();
            var configMap = objectMapper.convertValue(configNode, new TypeReference<Map<String, Object>>() {});
            configMap.put("knowledges", idStrs);

            // 3. 保存回agent
            agent.setConfig(objectMapper.writeValueAsString(configMap));
            updateById(agent);
        } catch (Exception e) {
            log.error("[Agent] 更新知识库绑定失败: agentId={}, error={}", agentId, e.getMessage(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    @Cacheable(value = RedisCacheConfig.CACHE_AGENT_BINDING, key = "#agentId + ':toolIds'")
    public List<Long> getToolIds(Long agentId) {
        return readBindingIdsFromConfig(agentId, "tools");
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_AGENT_BINDING, key = "#agentId + ':toolIds'")
    public void updateToolBindings(Long agentId, List<Long> toolIds) {
        writeBindingIdsToConfig(agentId, "tools", toolIds, 10, ErrorCode.AGENT_TOOL_LIMIT);
    }

    @Override
    public List<Tool> getToolDetails(Long agentId) {
        List<Long> toolIds = getToolIds(agentId);
        if (toolIds.isEmpty()) {
            return new ArrayList<>();
        }
        return toolService.listByIds(toolIds);
    }

    @Override
    @Cacheable(value = RedisCacheConfig.CACHE_AGENT_BINDING, key = "#agentId + ':mcpServerIds'")
    public List<Long> getMcpServerIds(Long agentId) {
        return readBindingIdsFromConfig(agentId, "mcpServers");
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_AGENT_BINDING, key = "#agentId + ':mcpServerIds'")
    public void updateMcpServerBindings(Long agentId, List<Long> mcpServerIds) {
        writeBindingIdsToConfig(agentId, "mcpServers", mcpServerIds, 5, ErrorCode.AGENT_MCP_LIMIT);
    }

    @Override
    public List<McpServer> getMcpServerDetails(Long agentId) {
        List<Long> serverIds = getMcpServerIds(agentId);
        if (serverIds.isEmpty()) {
            return new ArrayList<>();
        }
        return mcpServerService.listByIds(serverIds);
    }

    @Override
    @Cacheable(value = RedisCacheConfig.CACHE_AGENT_BINDING, key = "#agentId + ':subAgentIds'")
    public List<Long> getSubAgentIds(Long agentId) {
        return readBindingIdsFromConfig(agentId, "subagents");
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_AGENT_BINDING, key = "#agentId + ':subAgentIds'")
    public void updateSubAgentBindings(Long agentId, List<Long> subAgentIds) {
        writeBindingIdsToConfig(agentId, "subagents", subAgentIds, 5, ErrorCode.AGENT_SUBAGENT_LIMIT);
    }

    @Override
    @Cacheable(value = RedisCacheConfig.CACHE_AGENT_BINDING, key = "#agentId + ':skillIds'")
    public List<Long> getSkillIds(Long agentId) {
        return readBindingIdsFromConfig(agentId, "skills");
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_AGENT_BINDING, key = "#agentId + ':skillIds'")
    public void updateSkillBindings(Long agentId, List<Long> skillIds) {
        writeBindingIdsToConfig(agentId, "skills", skillIds, 10, ErrorCode.AGENT_SKILL_LIMIT);
    }

    /**
     * 从 config JSONB 读取绑定 ID（字符串存储，避免大整数精度丢失）
     */
    private List<Long> readBindingIdsFromConfig(Long agentId, String field) {
        Agent agent = getById(agentId);
        if (agent == null || agent.getConfig() == null || agent.getConfig().isBlank()) {
            return new ArrayList<>();
        }
        try {
            var configNode = objectMapper.readTree(agent.getConfig());
            if (!configNode.has(field)) {
                return new ArrayList<>();
            }
            List<Long> ids = new ArrayList<>();
            for (var node : configNode.get(field)) {
                if (node.isNumber()) {
                    ids.add(node.longValue());
                } else if (node.isTextual()) {
                    String text = node.asText();
                    if (text != null && !text.isBlank()) {
                        ids.add(Long.parseLong(text.trim()));
                    }
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("[Agent] 解析config.{}失败: agentId={}, error={}", field, agentId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 写入绑定 ID 到 config（统一存字符串，与 knowledges 字段一致）
     * <p>所有 update*Bindings 入口共享此方法，集中做数量上限校验 + 所有权校验，避免 4 处重复</p>
     *
     * @param agentId Agent ID
     * @param field   config JSONB 字段名（tools / mcpServers / subagents / skills）
     * @param ids     待写入的 ID 列表
     * @param max     业务允许的最大数量
     * @param limitErrorCode 超限时抛出的错误码
     */
    private void writeBindingIdsToConfig(Long agentId, String field, List<Long> ids,
                                          int max, ErrorCode limitErrorCode) {
        if (ids != null && ids.size() > max) {
            throw new BizException(limitErrorCode);
        }
        Agent agent = checkOwnership(agentId);
        try {
            var configNode = objectMapper.readTree(
                    agent.getConfig() != null ? agent.getConfig() : "{}");
            var configMap = objectMapper.convertValue(configNode, new TypeReference<Map<String, Object>>() {});
            List<String> idStrs = ids != null
                    ? ids.stream().map(String::valueOf).distinct().toList()
                    : List.of();
            configMap.put(field, idStrs);
            agent.setConfig(objectMapper.writeValueAsString(configMap));
            updateById(agent);
        } catch (Exception e) {
            log.error("[Agent] 更新config.{}失败: agentId={}, error={}", field, agentId, e.getMessage(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public String uploadAvatar(Long id, MultipartFile file) {
        // 1. 校验Agent存在性 + 所有权
        Agent agent = checkOwnership(id);

        // 2. 校验文件格式（仅允许图片格式）
        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null && originalName.lastIndexOf('.') > 0) {
            ext = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase();
        }
        List<String> allowedExts = List.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
        if (!allowedExts.contains(ext)) {
            throw new BizException(ErrorCode.AVATAR_UNSUPPORTED_TYPE, "支持格式: jpg/jpeg/png/gif/webp/bmp");
        }

        // 3. 生成存储路径：agent/{agentId}/avatar/{uuid}.{ext}
        String filePath = String.format("agent/%d/avatar/%s.%s", id, UUID.randomUUID().toString().replace("-", ""), ext);

        // 4. 删除旧头像（如果有）
        deleteOldAvatar(agent.getAvatar());

        // 5. 上传新头像
        minioUtil.upload(file, filePath);

        // 6. 构建永久URL并更新Agent的avatar字段
        String fullUrl = minioUtil.getPublicUrl(filePath);
        agent.setAvatar(fullUrl);
        updateById(agent);

        log.info("[Agent] 头像上传成功: agentId={}, url={}", id, fullUrl);
        return fullUrl;
    }

    private void deleteOldAvatar(String avatar) {
        minioUtil.deleteAvatar(avatar);
    }

    @Override
    public Agent getDefaultAgent(long userId) {
        return getOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getUserId, userId)
                .eq(Agent::getIsDefault, true)
                .last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefaultAgent(long agentId) {
        // 1. 校验Agent存在性 + 所有权
        Agent agent = checkOwnership(agentId);

        // 2. 清除该用户其他Agent的默认标记（1 条 SQL）
        long userId = agent.getUserId();
        lambdaUpdate()
                .eq(Agent::getUserId, userId)
                .eq(Agent::getIsDefault, true)
                .set(Agent::getIsDefault, false)
                .update();

        // 3. 设置当前Agent为默认
        agent.setIsDefault(true);
        updateById(agent);
    }

    @Override
    public List<WorkflowExampleVO> listWorkflowExamples() {
        return WorkflowExampleTemplates.listExamples();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Agent createFromWorkflowExample(String key) {
        // 1. 校验示例 key 有效性
        String exampleName = WorkflowExampleTemplates.getExampleName(key);
        if (exampleName == null) {
            throw new BizException("无效的示例标识: " + key);
        }

        long userId = StpUtil.getLoginIdAsLong();
        Map<String, Long> toolIds = resolveExampleToolIds();
        Map<String, Long> subAgentIds = createPublishedSubWorkflowExamples(key, userId, toolIds);

        // 2. 构建 Agent 实体
        Agent agent = new Agent();
        agent.setUserId(userId);
        agent.setName(exampleName);
        agent.setDescription("内置示例工作流，帮助学习工作流节点使用");
        agent.setAgentType(AgentType.WORKFLOW);
        agent.setWelcomeMessage(WorkflowExampleTemplates.getWelcomeMessage(key));
        agent.setRecommendedQuestions(WorkflowExampleTemplates.getRecommendedQuestions(key));
        agent.setConfig("{}");
        agent.setStatus(AgentStatus.DRAFT);
        agent.setVersion(0);
        save(agent);

        // 3. 解析占位符并初始化草稿版本
        Map<String, Object> snapshot = WorkflowExampleTemplates.buildResolvedSnapshot(key, subAgentIds, toolIds);
        if (snapshot == null) {
            throw new BizException("示例工作流快照不存在: " + key);
        }
        agentVersionService.initDraftWithWorkflow(agent, snapshot);
        return agent;
    }

    /** 按工具名解析内置工具 ID，供示例 tool 节点绑定 */
    private Map<String, Long> resolveExampleToolIds() {
        Map<String, Long> toolIds = new LinkedHashMap<>();
        for (String toolName : List.of("web_search", "calculator", "ask_user")) {
            Tool tool = toolService.getOne(new LambdaQueryWrapper<Tool>()
                    .eq(Tool::getName, toolName)
                    .last("LIMIT 1"));
            if (tool != null && tool.getId() != null) {
                toolIds.put(toolName, tool.getId());
            }
        }
        return toolIds;
    }

    /** 创建并发布示例子工作流，返回 subKey → agentId */
    private Map<String, Long> createPublishedSubWorkflowExamples(String key, long userId, Map<String, Long> toolIds) {
        Map<String, Long> subAgentIds = new HashMap<>();
        for (String subKey : WorkflowExampleTemplates.getSubWorkflowKeys(key)) {
            Map<String, Object> subSnapshot = WorkflowExampleTemplates.buildResolvedSubSnapshot(subKey, toolIds);
            if (subSnapshot == null) {
                throw new BizException("示例子工作流不存在: " + subKey);
            }

            Agent subAgent = new Agent();
            subAgent.setUserId(userId);
            subAgent.setName(WorkflowExampleTemplates.getSubWorkflowName(subKey));
            subAgent.setDescription("内置示例子工作流，由「" + WorkflowExampleTemplates.getExampleName(key) + "」自动创建");
            subAgent.setAgentType(AgentType.WORKFLOW);
            subAgent.setWelcomeMessage("示例子工作流模块");
            subAgent.setRecommendedQuestions("[]");
            subAgent.setConfig("{}");
            subAgent.setStatus(AgentStatus.DRAFT);
            subAgent.setVersion(0);
            save(subAgent);

            agentVersionService.initDraftWithWorkflow(subAgent, subSnapshot);
            publishExampleWorkflowSnapshot(subAgent.getId(), subSnapshot);
            subAgentIds.put(subKey, subAgent.getId());
        }
        return subAgentIds;
    }

    private void publishExampleWorkflowSnapshot(Long agentId, Map<String, Object> workflowSnapshot) {
        Object graphObj = workflowSnapshot.get("graph");
        if (graphObj == null) {
            return;
        }
        WorkflowGraphDTO graph = objectMapper.convertValue(graphObj, WorkflowGraphDTO.class);
        agentVersionService.publishWorkflow(agentId, graph);
    }

    @Override
    public List<Agent> listByUserId(Long userId) {
        return list(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getUserId, userId)
                .orderByDesc(Agent::getCreateTime));
    }

    private void safeRemove(Runnable action, String label) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[Agent删除] {}清理失败, 跳过: {}", label, e.getMessage());
        }
    }

    @Override
    public MentionOptionsVO getMentionOptions(Long agentId, Long agentVersionId, String types) {
        // 0. 所有权校验（agentId 非空时；agentVersionId 单独走快照路径不直接落地 agent 表）
        if (agentId != null) {
            checkOwnership(agentId);
        }

        // 1. 解析绑定 ID：agentVersionId 非空优先走版本快照，payload 缺失时 fallback 到 agent 表当前绑定。
        // 草稿版本的 agent_version.config 可能未同步最新绑定（updateKnowledgeBindings 等只写 agent.config），
        // 因此必须 fallback，否则候选会误返回空。
        List<Long> knowledgeIds = null;
        List<Long> subAgentIds = null;
        List<Long> skillIds = null;
        List<Long> toolIds = null;
        if (agentVersionId != null) {
            Map<String, Object> payload = agentVersionService.loadVersionPayloadById(agentVersionId);
            knowledgeIds = parseLongListFromPayload(payload, "knowledgeIds");
            subAgentIds = parseLongListFromPayload(payload, "subAgentIds");
            skillIds = parseLongListFromPayload(payload, "skillIds");
            toolIds = parseLongListFromPayload(payload, "toolIds");
        }
        if (knowledgeIds == null) knowledgeIds = getKnowledgeIds(agentId);
        if (subAgentIds == null) subAgentIds = getSubAgentIds(agentId);
        if (skillIds == null) skillIds = getSkillIds(agentId);
        if (toolIds == null) toolIds = getToolIds(agentId);

        // 2. 确定返回类型集合：默认知识库/子智能体/Skill/工具四类
        java.util.Set<MentionResourceType> typeSet = parseTypes(types);

        // 3. 按类型分组构建候选
        List<MentionGroupVO> groups = new java.util.ArrayList<>();
        if (typeSet.contains(MentionResourceType.KNOWLEDGE)) {
            groups.add(buildKnowledgeGroup(knowledgeIds));
        }
        if (typeSet.contains(MentionResourceType.SUBAGENT)) {
            groups.add(buildSubAgentGroup(subAgentIds));
        }
        if (typeSet.contains(MentionResourceType.SKILL)) {
            groups.add(buildSkillGroup(skillIds));
        }
        if (typeSet.contains(MentionResourceType.TOOL)) {
            groups.add(buildToolGroup(toolIds));
        }

        return MentionOptionsVO.builder()
                .agentId(agentId == null ? null : agentId.toString())
                .agentVersionId(agentVersionId == null ? null : agentVersionId.toString())
                .groups(groups)
                .build();
    }

    private java.util.Set<MentionResourceType> parseTypes(String types) {
        if (types == null || types.isBlank()) {
            return java.util.EnumSet.of(MentionResourceType.KNOWLEDGE, MentionResourceType.SUBAGENT,
                    MentionResourceType.SKILL, MentionResourceType.TOOL);
        }
        java.util.Set<MentionResourceType> set = java.util.EnumSet.noneOf(MentionResourceType.class);
        for (String t : types.split(",")) {
            String trimmed = t.trim();
            if (trimmed.isEmpty()) continue;
            try {
                set.add(MentionResourceType.fromValue(trimmed));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return set.isEmpty()
                ? java.util.EnumSet.of(MentionResourceType.KNOWLEDGE, MentionResourceType.SUBAGENT,
                        MentionResourceType.SKILL, MentionResourceType.TOOL)
                : set;
    }

    private MentionGroupVO buildKnowledgeGroup(List<Long> knowledgeIds) {
        List<MentionResourceDTO> items = new java.util.ArrayList<>();
        if (knowledgeIds != null && !knowledgeIds.isEmpty()) {
            for (com.lengbot.entity.Knowledge k : knowledgeService.listByIds(knowledgeIds)) {
                boolean enabled = k.getStatus() == CommonStatus.ACTIVE;
                items.add(MentionResourceDTO.builder()
                        .type(MentionResourceType.KNOWLEDGE)
                        .resourceId(String.valueOf(k.getId()))
                        .name(k.getName())
                        .description(k.getDescription())
                        .token("@knowledge:" + k.getId())
                        .enabled(enabled)
                        .disabledReason(enabled ? null : "知识库已禁用或删除")
                        .build());
            }
        }
        return MentionGroupVO.builder()
                .type(MentionResourceType.KNOWLEDGE)
                .label("知识库")
                .items(items)
                .build();
    }

    private MentionGroupVO buildSubAgentGroup(List<Long> subAgentIds) {
        List<MentionResourceDTO> items = new java.util.ArrayList<>();
        if (subAgentIds != null && !subAgentIds.isEmpty()) {
            for (com.lengbot.entity.SubAgent s : subAgentService.listByIds(subAgentIds)) {
                boolean enabled = Integer.valueOf(1).equals(s.getEnabled());
                items.add(MentionResourceDTO.builder()
                        .type(MentionResourceType.SUBAGENT)
                        .resourceId(String.valueOf(s.getId()))
                        .name(s.getDisplayName() != null && !s.getDisplayName().isBlank() ? s.getDisplayName() : s.getName())
                        .description(s.getDescription())
                        .token("@subagent:" + s.getId())
                        .enabled(enabled)
                        .disabledReason(enabled ? null : "子智能体已禁用")
                        .build());
            }
        }
        return MentionGroupVO.builder()
                .type(MentionResourceType.SUBAGENT)
                .label("SubAgents")
                .items(items)
                .build();
    }

    private MentionGroupVO buildSkillGroup(List<Long> skillIds) {
        List<MentionResourceDTO> items = new java.util.ArrayList<>();
        if (skillIds != null && !skillIds.isEmpty()) {
            for (com.lengbot.entity.Skill s : skillService.listByIds(skillIds)) {
                boolean enabled = s.getStatus() == CommonStatus.ACTIVE;
                items.add(MentionResourceDTO.builder()
                        .type(MentionResourceType.SKILL)
                        .resourceId(String.valueOf(s.getId()))
                        .name(s.getDisplayName() != null && !s.getDisplayName().isBlank() ? s.getDisplayName() : s.getName())
                        .description(s.getDescription())
                        .token("@skill:" + s.getId())
                        .enabled(enabled)
                        .disabledReason(enabled ? null : "Skill已禁用或删除")
                        .build());
            }
        }
        return MentionGroupVO.builder()
                .type(MentionResourceType.SKILL)
                .label("Skill")
                .items(items)
                .build();
    }

    private MentionGroupVO buildToolGroup(List<Long> toolIds) {
        List<MentionResourceDTO> items = new java.util.ArrayList<>();
        if (toolIds != null && !toolIds.isEmpty()) {
            for (com.lengbot.entity.Tool t : toolService.listByIds(toolIds)) {
                boolean enabled = t.getStatus() == CommonStatus.ACTIVE;
                items.add(MentionResourceDTO.builder()
                        .type(MentionResourceType.TOOL)
                        .resourceId(String.valueOf(t.getId()))
                        .name(t.getDisplayName() != null && !t.getDisplayName().isBlank() ? t.getDisplayName() : t.getName())
                        .description(t.getDescription())
                        .token("@tool:" + t.getId())
                        .enabled(enabled)
                        .disabledReason(enabled ? null : "工具已禁用或删除")
                        .build());
            }
        }
        return MentionGroupVO.builder()
                .type(MentionResourceType.TOOL)
                .label("工具")
                .items(items)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Long> parseLongListFromPayload(Map<String, Object> payload, String key) {
        if (payload == null) return null;
        Object raw = payload.get(key);
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        List<Long> ids = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number n) {
                ids.add(n.longValue());
            } else if (item != null && !String.valueOf(item).isBlank()) {
                try {
                    ids.add(Long.parseLong(String.valueOf(item).trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return ids.isEmpty() ? null : ids;
    }
}
