package com.lengbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lengbot.common.BizException;
import com.lengbot.config.RedisCacheConfig;
import com.lengbot.dto.ToolRequestDTO;
import com.lengbot.entity.Tool;
import com.lengbot.enums.CommonStatus;
import com.lengbot.constant.ToolResultPrefixes;
import com.lengbot.enums.ErrorCode;
import com.lengbot.enums.ToolType;
import org.springframework.util.StringUtils;
import com.lengbot.mapper.ToolMapper;
import com.lengbot.service.ToolService;
import com.lengbot.service.port.DefaultAgentIdProvider;
import com.lengbot.util.ToolInputSchemaValidator;
import com.lengbot.util.ToolIoSchemaUtil;
import com.lengbot.util.ValidatingToolCallback;
import com.lengbot.util.ToolRateLimiter;
import com.lengbot.util.RateLimitedToolCallback;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tool 服务实现类
 *
 * @author finch
 * @since 2026-05-20
 */
@Slf4j
@Service
public class ToolServiceImpl extends ServiceImpl<ToolMapper, Tool>
        implements ToolService {

    private final ApplicationContext applicationContext;
    private final com.lengbot.util.ToolArgsSanitizer toolArgsSanitizer;
    private final ApiToolExecutionService apiToolExecutionService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ObjectProvider<DefaultAgentIdProvider> defaultAgentIdProvider;
    private final ToolInputSchemaValidator toolInputSchemaValidator;
    private final ToolRateLimiter toolRateLimiter;

    /** 启动时扫描缓存的内置 ToolBase 列表 */
    private volatile List<ToolBase> cachedBuiltinCallbacks;

    public ToolServiceImpl(ApplicationContext applicationContext,
                           com.lengbot.util.ToolArgsSanitizer toolArgsSanitizer,
                           ApiToolExecutionService apiToolExecutionService,
                           com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                           ObjectProvider<DefaultAgentIdProvider> defaultAgentIdProvider,
                           ToolInputSchemaValidator toolInputSchemaValidator,
                           ToolRateLimiter toolRateLimiter) {
        this.applicationContext = applicationContext;
        this.toolArgsSanitizer = toolArgsSanitizer;
        this.apiToolExecutionService = apiToolExecutionService;
        this.objectMapper = objectMapper;
        this.defaultAgentIdProvider = defaultAgentIdProvider;
        this.toolInputSchemaValidator = toolInputSchemaValidator;
        this.toolRateLimiter = toolRateLimiter;
    }

    @Override
    @Cacheable(value = RedisCacheConfig.CACHE_TOOL, key = "#id", unless = "#result == null")
    public Tool getById(Serializable id) {
        return super.getById(id);
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_TOOL, key = "#entity.id")
    public boolean updateById(Tool entity) {
        return super.updateById(entity);
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_TOOL, allEntries = true)
    public Tool create(ToolRequestDTO request) {
        // 1. 校验名称唯一性
        long count = count(new LambdaQueryWrapper<Tool>().eq(Tool::getName, request.getName()));
        if (count > 0) {
            throw new BizException(ErrorCode.TOOL_NAME_EXISTS);
        }
        // 2. 构建实体并保存
        Tool tool = new Tool();
        tool.setName(request.getName());
        tool.setDisplayName(request.getDisplayName());
        tool.setIcon(request.getIcon());
        tool.setDescription(request.getDescription());
        tool.setToolType(request.getToolType());
        tool.setInputSchema(request.getInputSchema());
        tool.setOutputSchema(request.getOutputSchema());
        tool.setOutputExample(request.getOutputExample());
        tool.setConfig(request.getConfig());
        tool.setEndpointUrl(request.getEndpointUrl());
        tool.setAuthType(request.getAuthType());
        tool.setAuthConfig(request.getAuthConfig());
        tool.setTags(request.getTags());
        tool.setRateLimitEnabled(Boolean.TRUE.equals(request.getRateLimitEnabled()));
        tool.setRateLimitConfig(request.getRateLimitConfig());
        tool.setStatus(CommonStatus.ACTIVE);
        save(tool);
        return tool;
    }

    @Override
    public Tool update(ToolRequestDTO request) {
        // 1. 校验存在性
        Tool tool = getById(request.getId());
        if (tool == null) {
            throw new BizException(ErrorCode.TOOL_NOT_FOUND);
        }
        // 2. 知识库工具不可编辑（由注册器自动管理）
        if (tool.getToolType() == ToolType.KNOWLEDGE) {
            throw new BizException(ErrorCode.TOOL_NOT_EDITABLE);
        }
        // 3. 内置工具仅允许编辑限流相关字段（由注册器自动管理其他字段，用户改动会被覆盖）
        if (tool.getToolType() == ToolType.BUILTIN) {
            tool.setRateLimitEnabled(Boolean.TRUE.equals(request.getRateLimitEnabled()));
            tool.setRateLimitConfig(request.getRateLimitConfig());
            updateById(tool);
            return tool;
        }
        // 4. 自定义 API 工具：全字段更新
        // 4.1 名称变更时校验唯一性
        if (!tool.getName().equals(request.getName())) {
            long count = count(new LambdaQueryWrapper<Tool>().eq(Tool::getName, request.getName()));
            if (count > 0) {
                throw new BizException(ErrorCode.TOOL_NAME_EXISTS);
            }
        }
        // 4.2 更新字段
        tool.setName(request.getName());
        tool.setDisplayName(request.getDisplayName());
        tool.setIcon(request.getIcon());
        tool.setDescription(request.getDescription());
        tool.setToolType(request.getToolType());
        tool.setInputSchema(request.getInputSchema());
        tool.setOutputSchema(request.getOutputSchema());
        tool.setOutputExample(request.getOutputExample());
        tool.setConfig(request.getConfig());
        tool.setEndpointUrl(request.getEndpointUrl());
        tool.setAuthType(request.getAuthType());
        tool.setAuthConfig(request.getAuthConfig());
        tool.setTags(request.getTags());
        tool.setRateLimitEnabled(Boolean.TRUE.equals(request.getRateLimitEnabled()));
        tool.setRateLimitConfig(request.getRateLimitConfig());
        tool.setStatus(request.getStatus());
        updateById(tool);
        return tool;
    }

    @Override
    public Page<Tool> listPage(int pageNum, int pageSize, String name) {
        return baseMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<Tool>()
                        .like(StringUtils.hasText(name), Tool::getName, name)
                        .orderByDesc(Tool::getCreateTime));
    }

    @Override
    public Page<Tool> listTools(int pageNum, int pageSize, String toolType) {
        return baseMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<Tool>()
                        .eq(StringUtils.hasText(toolType), Tool::getToolType, toolType)
                        .orderByDesc(Tool::getCreateTime));
    }

    @Override
    public Page<Tool> listToolsWithFilter(int pageNum, int pageSize, String keyword, String toolType, String tag) {
        // 标签过滤需要 JSONB @> 操作符
        if (StringUtils.hasText(tag)) {
            return baseMapper.selectPage(new Page<>(pageNum, pageSize),
                    new LambdaQueryWrapper<Tool>()
                            .orderByDesc(Tool::getCreateTime)
                            .and(StringUtils.hasText(keyword), w -> w
                                    .like(Tool::getName, keyword)
                                    .or()
                                    .like(Tool::getDisplayName, keyword))
                            .eq(StringUtils.hasText(toolType), Tool::getToolType, toolType)
                            .apply("tags @> '[\"{0}\"]'", tag));
        }

        LambdaQueryWrapper<Tool> wrapper = new LambdaQueryWrapper<Tool>()
                .orderByDesc(Tool::getCreateTime);

        // 关键字搜索（name 或 displayName）
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                    .like(Tool::getName, keyword)
                    .or()
                    .like(Tool::getDisplayName, keyword));
        }

        // 工具类型过滤
        if (StringUtils.hasText(toolType)) {
            wrapper.eq(Tool::getToolType, toolType);
        }

        return baseMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_TOOL, key = "#id")
    public void setEnabled(Long id, boolean enabled) {
        Tool tool = getById(id);
        if (tool == null) {
            throw new BizException(ErrorCode.TOOL_NOT_FOUND);
        }
        // 知识库工具不可禁用（由注册器自动管理）
        if (tool.getToolType() == ToolType.KNOWLEDGE) {
            throw new BizException(ErrorCode.TOOL_NOT_EDITABLE);
        }
        tool.setStatus(enabled ? CommonStatus.ACTIVE : CommonStatus.DISABLED);
        updateById(tool);
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_TOOL, key = "#id")
    public void deleteById(Long id) {
        Tool tool = getById(id);
        if (tool == null) {
            throw new BizException(ErrorCode.TOOL_NOT_FOUND);
        }
        // 知识库工具不可删除（由注册器自动管理）
        if (tool.getToolType() == ToolType.KNOWLEDGE) {
            throw new BizException(ErrorCode.TOOL_NOT_DELETABLE);
        }
        removeById(id);
    }

    @Override
    public List<ToolBase> resolveToolCallbacks(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }

        // 1. 从DB查询工具记录
        List<Tool> tools = list(new LambdaQueryWrapper<Tool>()
                .in(Tool::getName, toolNames)
                .eq(Tool::getStatus, CommonStatus.ACTIVE));
        if (tools.isEmpty()) {
            return List.of();
        }

        // 2. 收集所有 @Tool Bean 的 ToolBase
        List<ToolBase> allCallbacks = getAllBuiltinToolCallbacks();
        Set<String> allCallbackNames = allCallbacks.stream()
                .map(ToolBase::getName)
                .collect(Collectors.toSet());

        // 3. 过滤出 Agent 绑定的工具，按 name 索引 Tool 元数据（用于限流装饰）
        Map<String, Tool> toolByName = tools.stream()
                .collect(Collectors.toMap(Tool::getName, t -> t, (a, b) -> a));
        List<ToolBase> result = new ArrayList<>();
        for (Tool tool : tools) {
            if (allCallbackNames.contains(tool.getName())) {
                allCallbacks.stream()
                        .filter(cb -> cb.getName().equals(tool.getName()))
                        .findFirst()
                        .ifPresent(result::add);
            } else if (tool.getToolType() == ToolType.API) {
                result.add(new ApiToolCallback(tool, apiToolExecutionService, objectMapper));
            }
        }
        // 4. 包装 Schema 校验 + 限流装饰器
        return wrapDecorators(result, toolByName);
    }

    @Override
    public List<ToolBase> resolveToolCallbacksByIds(List<Long> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            return List.of();
        }

        // 1. 从DB查询工具记录
        List<Tool> tools = listByIds(toolIds);
        if (tools.isEmpty()) {
            return List.of();
        }

        // 2. 收集所有 @Tool Bean 的 ToolBase
        List<ToolBase> allCallbacks = getAllBuiltinToolCallbacks();
        Set<String> allCallbackNames = allCallbacks.stream()
                .map(ToolBase::getName)
                .collect(Collectors.toSet());

        // 3. 过滤出 Agent 绑定的工具，按 name 索引 Tool 元数据（用于限流装饰）
        Map<String, Tool> toolByName = tools.stream()
                .collect(Collectors.toMap(Tool::getName, t -> t, (a, b) -> a));
        List<ToolBase> result = new ArrayList<>();
        for (Tool tool : tools) {
            if (allCallbackNames.contains(tool.getName())) {
                allCallbacks.stream()
                        .filter(cb -> cb.getName().equals(tool.getName()))
                        .findFirst()
                        .ifPresent(result::add);
            } else if (tool.getToolType() == ToolType.API) {
                result.add(new ApiToolCallback(tool, apiToolExecutionService, objectMapper));
            }
        }
        // 4. 包装 Schema 校验 + 限流装饰器
        return wrapDecorators(result, toolByName);
    }

    /**
     * 为每个 ToolBase 包装 Schema 校验装饰器，并在开启限流时再包一层限流装饰器
     * <p>装饰顺序：限流（外）→ Schema 校验 → 原始回调（内）。
     * 限流先于参数校验拦截，避免被限流的请求仍触发参数解析消耗资源。</p>
     */
    private List<ToolBase> wrapDecorators(List<ToolBase> callbacks, Map<String, Tool> toolByName) {
        if (callbacks == null || callbacks.isEmpty()) {
            return List.of();
        }
        return callbacks.stream()
                .map(cb -> {
                    ToolBase validating = new ValidatingToolCallback(cb, toolInputSchemaValidator);
                    Tool tool = toolByName.get(cb.getName());
                    if (tool != null && Boolean.TRUE.equals(tool.getRateLimitEnabled())
                            && tool.getRateLimitConfig() != null && !tool.getRateLimitConfig().isBlank()) {
                        return (ToolBase) new RateLimitedToolCallback(validating, toolRateLimiter, tool.getRateLimitConfig());
                    }
                    return validating;
                })
                .toList();
    }

    /**
     * 获取所有内置 @Tool Bean 的 ToolBase（首次调用时扫描并缓存，避免每次对话扫描全量 Bean）
     */
    private List<ToolBase> getAllBuiltinToolCallbacks() {
        List<ToolBase> cached = cachedBuiltinCallbacks;
        if (cached != null) {
            return cached;
        }
        cached = scanBuiltinToolCallbacks();
        cachedBuiltinCallbacks = cached;
        return cached;
    }

    /**
     * 扫描所有 @Component Bean 中的 @Tool 方法，通过 Toolkit.registerTool() 构建 ToolBase 列表
     */
    private List<ToolBase> scanBuiltinToolCallbacks() {
        try {
            List<ToolBase> callbacks = new ArrayList<>();
            Map<String, Object> beans = applicationContext.getBeansWithAnnotation(org.springframework.stereotype.Component.class);

            for (Object bean : beans.values()) {
                // 解包 CGLIB 代理，获取真实类
                Class<?> clazz = getTargetClass(bean);
                // 直接遍历类声明的方法（跳过编译器生成的桥方法和synthetic方法）
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.isSynthetic() || method.isBridge()) continue;
                    io.agentscope.core.tool.Tool toolAnnotation =
                            method.getAnnotation(io.agentscope.core.tool.Tool.class);
                    if (toolAnnotation != null) {
                        method.setAccessible(true);
                        // 通过 Toolkit.registerTool() 注册 @Tool 方法，再从注册表取回 ToolBase
                        io.agentscope.core.tool.Toolkit toolkit = new io.agentscope.core.tool.Toolkit();
                        toolkit.registerTool(bean);
                        io.agentscope.core.tool.AgentTool registered =
                                toolkit.getTool(toolAnnotation.name());
                        if (registered instanceof io.agentscope.core.tool.ToolBase tb) {
                            callbacks.add(tb);
                        }
                    }
                }
            }

            log.info("[ToolService] 发现内置ToolBase: {}", callbacks.stream()
                    .map(ToolBase::getName).toList());
            return callbacks;
        } catch (Exception e) {
            log.warn("[ToolService] 获取内置ToolBase失败: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public String testTool(Long toolId, String args) {
        // 1. 解析工具回调
        List<ToolBase> callbacks = resolveToolCallbacksByIds(List.of(toolId));
        if (callbacks.isEmpty()) {
            throw new BizException(ErrorCode.TOOL_NOT_FOUND);
        }

        // 2. 执行工具
        ToolBase callback = callbacks.get(0);
        String toolName = callback.getName();
        log.info("[ToolService] 测试工具: toolId={}, name={}, args={}", toolId, toolName, args);

        try {
            // 从 args 中提取 agentId（如有），供 query_knowledge 等需要上下文的工具使用
            long agentId = 0L;
            if (args != null && !args.isBlank()) {
                try {
                    var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(args);
                    if (node.has("agentId") && !node.get("agentId").isNull()) {
                        var idNode = node.get("agentId");
                        if (idNode.isNumber()) {
                            agentId = idNode.asLong(0);
                        } else if (idNode.isTextual() && !idNode.asText().isBlank()) {
                            agentId = Long.parseLong(idNode.asText().trim());
                        }
                    }
                } catch (Exception ignored) {}
            }
            // agentId 为空时，使用当前用户的默认 Agent（便于测试 query_knowledge 等工具）
            if (agentId == 0) {
                try {
                    long userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
                    DefaultAgentIdProvider provider = defaultAgentIdProvider.getIfAvailable();
                    Long defaultAgentId = provider != null ? provider.getDefaultAgentId(userId) : null;
                    if (defaultAgentId != null) {
                        agentId = defaultAgentId;
                        log.info("[ToolService] 测试工具自动使用默认Agent: agentId={}", agentId);
                    }
                } catch (cn.dev33.satoken.exception.NotWebContextException ignored) {
                    log.debug("[ToolService] 非Web上下文，跳过默认Agent查找");
                }
            }
            // 构建 ToolCallParam 上下文（传递 agentId、requestId 等运行时信息）
            String callArgs = toolArgsSanitizer.forTestCall(args != null ? args : "{}");
            Map<String, Object> inputMap = Map.of();
            try {
                inputMap = objectMapper.readValue(callArgs,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {}
            io.agentscope.core.agent.RuntimeContext rc = io.agentscope.core.agent.RuntimeContext.builder()
                    .build();
            rc.put("agentId", agentId);
            rc.put("requestId", "test-" + System.nanoTime());
            ToolCallParam param = ToolCallParam.builder()
                    .input(inputMap)
                    .runtimeContext(rc)
                    .build();
            ToolResultBlock resultBlock = callback.callAsync(param).block();
            String result = extractResultText(resultBlock);
            log.info("[ToolService] 工具测试完成: name={}, resultLength={}", toolName, result.length());
            return result;
        } catch (Exception e) {
            log.error("[ToolService] 工具测试失败: name={}, error={}", toolName, e.getMessage(), e);
            return ToolResultPrefixes.failureJson(ToolResultPrefixes.FAILURE + ": " + e.getMessage());
        }
    }

    /**
     * 从 ToolResultBlock 提取纯文本结果
     */
    private String extractResultText(ToolResultBlock resultBlock) {
        if (resultBlock == null) return "";
        // 从内容块中提取文本
        try {
            StringBuilder sb = new StringBuilder();
            for (io.agentscope.core.message.ContentBlock block : resultBlock.getOutput()) {
                if (block instanceof TextBlock tb) {
                    sb.append(tb.getText());
                }
            }
            return sb.toString();
        } catch (Exception ex) {
            return resultBlock.toString();
        }
    }

    /**
     * 获取 Bean 的真实类（处理 CGLIB 代理）
     */
    private Class<?> getTargetClass(Object bean) {
        if (bean instanceof Advised advised) {
            try {
                return advised.getTargetSource().getTarget().getClass();
            } catch (Exception e) {
                // fall through
            }
        }
        return bean.getClass();
    }

    @Override
    public Map<String, Object> getExampleParams(Long toolId) {
        Tool tool = getById(toolId);
        if (tool == null) {
            throw new BizException(ErrorCode.TOOL_NOT_FOUND);
        }
        // 从 config 字段解析 exampleParams
        if (tool.getConfig() != null && !tool.getConfig().isBlank()) {
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(tool.getConfig());
                if (node.has("exampleParams")) {
                    var exampleNode = node.get("exampleParams");
                    Map<String, Object> example = new java.util.HashMap<>();
                    exampleNode.fields().forEachRemaining(entry ->
                            example.put(entry.getKey(), jsonNodeToValue(entry.getValue())));
                    return example;
                }
            } catch (Exception e) {
                log.warn("[ToolService] 解析示例参数失败: toolId={}, error={}", toolId, e.getMessage());
            }
        }
        return Map.of();
    }

    @Override
    public java.util.Set<String> getRequiredParamKeys(Long toolId) {
        Tool tool = getById(toolId);
        if (tool == null) {
            throw new BizException(ErrorCode.TOOL_NOT_FOUND);
        }
        if (tool.getInputSchema() == null || tool.getInputSchema().isBlank()) {
            return java.util.Set.of();
        }
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(tool.getInputSchema());
            var requiredNode = root.get("required");
            if (requiredNode == null || !requiredNode.isArray()) {
                return java.util.Set.of();
            }
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            for (var item : requiredNode) {
                if (item != null && item.isTextual() && !item.asText().isBlank()) {
                    keys.add(item.asText());
                }
            }
            return keys;
        } catch (Exception e) {
            log.warn("[ToolService] 解析 inputSchema.required 失败: toolId={}, error={}", toolId, e.getMessage());
            return java.util.Set.of();
        }
    }

    private static Object jsonNodeToValue(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    @Override
    public Map<String, Object> getIoSchema(Long toolId) {
        Tool tool = getById(toolId);
        if (tool == null) {
            throw new BizException(ErrorCode.TOOL_NOT_FOUND);
        }
        return ToolIoSchemaUtil.buildSchema(tool);
    }

    @Override
    public List<String> cleanStaleToolIds(List<String> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            return toolIds;
        }
        List<Long> ids = toolIds.stream().map(Long::parseLong).toList();
        Set<String> existing = listByIds(ids).stream()
                .map(t -> String.valueOf(t.getId()))
                .collect(Collectors.toSet());
        return toolIds.stream().filter(existing::contains).toList();
    }
}
