package com.lengbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.common.BizException;
import com.lengbot.config.RedisCacheConfig;
import com.lengbot.dto.McpServerRequestDTO;
import com.lengbot.entity.McpServer;
import com.lengbot.enums.CommonStatus;
import com.lengbot.enums.ErrorCode;
import com.lengbot.mapper.McpServerMapper;
import com.lengbot.service.McpClientService;
import com.lengbot.service.McpServerService;
import com.lengbot.vo.McpToolVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * MCP Server 服务实现类
 *
 * @author finch
 * @since 2026-05-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerServiceImpl extends ServiceImpl<McpServerMapper, McpServer>
        implements McpServerService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 客户端服务反向依赖本服务；按实际 MCP 操作延迟解析，避免 Bean 构造期循环依赖。
     */
    private final ObjectProvider<McpClientService> mcpClientServiceProvider;

    /**
     * 获取 MCP 客户端服务。
     *
     * @return MCP 客户端服务
     */
    private McpClientService getMcpClientService() {
        return mcpClientServiceProvider.getObject();
    }

    @Override
    @Cacheable(value = RedisCacheConfig.CACHE_MCP_SERVER, key = "#id", unless = "#result == null")
    public McpServer getById(Serializable id) {
        return super.getById(id);
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_MCP_SERVER, key = "#entity.id")
    public boolean updateById(McpServer entity) {
        return super.updateById(entity);
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_MCP_SERVER, allEntries = true)
    public McpServer create(McpServerRequestDTO request) {
        // 1. 校验名称唯一性
        long count = count(new LambdaQueryWrapper<McpServer>().eq(McpServer::getName, request.getName()));
        if (count > 0) {
            throw new BizException(ErrorCode.MCP_SERVER_NAME_EXISTS);
        }

        // 2. 构建实体并保存
        McpServer server = new McpServer();
        server.setName(request.getName());
        server.setIcon(request.getIcon());
        server.setDescription(request.getDescription());
        server.setInstallType(request.getInstallType());
        server.setDeployConfig(request.getDeployConfig());
        server.setDetailConfig(request.getDetailConfig());
        server.setHost(request.getHost());
        server.setTransport(request.getTransport());
        server.setHeaders(request.getHeaders());
        server.setDisabledTools(request.getDisabledTools());
        server.setIsBuiltin(0);
        server.setStatus(CommonStatus.ACTIVE);
        save(server);
        return server;
    }

    @Override
    public McpServer update(McpServerRequestDTO request) {
        // 1. 校验存在性
        McpServer server = getById(request.getId());
        if (server == null) {
            throw new BizException(ErrorCode.MCP_SERVER_NOT_FOUND);
        }
        // 2. 名称变更时校验唯一性
        if (!server.getName().equals(request.getName())) {
            long count = count(new LambdaQueryWrapper<McpServer>().eq(McpServer::getName, request.getName()));
            if (count > 0) {
                throw new BizException(ErrorCode.MCP_SERVER_NAME_EXISTS);
            }
        }

        // 3. 更新字段
        server.setName(request.getName());
        server.setIcon(request.getIcon());
        server.setDescription(request.getDescription());
        server.setInstallType(request.getInstallType());
        server.setDeployConfig(request.getDeployConfig());
        server.setDetailConfig(request.getDetailConfig());
        server.setHost(request.getHost());
        server.setTransport(request.getTransport());
        server.setHeaders(request.getHeaders());
        server.setDisabledTools(request.getDisabledTools());
        updateById(server);
        // 配置变更后清除 MCP 客户端缓存，下次对话重新拉取工具
        getMcpClientService().clearCache(server.getId());
        return server;
    }

    @Override
    public Page<McpServer> listPage(int pageNum, int pageSize, String name) {
        return baseMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<McpServer>()
                        .like(StringUtils.hasText(name), McpServer::getName, name)
                        .orderByDesc(McpServer::getCreateTime));
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_MCP_SERVER, key = "#id")
    public void setEnabled(Long id, boolean enabled) {
        McpServer server = getById(id);
        if (server == null) {
            throw new BizException(ErrorCode.MCP_SERVER_NOT_FOUND);
        }
        server.setStatus(enabled ? CommonStatus.ACTIVE : CommonStatus.DISABLED);
        updateById(server);
        getMcpClientService().clearCache(id);
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_MCP_SERVER, key = "#id")
    public void deleteById(Long id) {
        // 1. 校验存在性
        McpServer server = getById(id);
        if (server == null) {
            throw new BizException(ErrorCode.MCP_SERVER_NOT_FOUND);
        }
        // 2. 内置 MCP 禁止删除
        if (Objects.equals(server.getIsBuiltin(), 1)) {
            throw new BizException(ErrorCode.MCP_SERVER_BUILTIN_DELETE_FORBIDDEN);
        }
        // 3. 逻辑删除并清理客户端缓存
        removeById(id);
        getMcpClientService().clearCache(id);
    }

    @Override
    public List<McpToolVO> listTools(Long id) {
        McpServer server = getById(id);
        if (server == null) {
            throw new BizException(ErrorCode.MCP_SERVER_NOT_FOUND);
        }
        // 1. 从缓存或运行时获取工具列表
        List<io.modelcontextprotocol.spec.McpSchema.Tool> mcpTools = getMcpClientService().getToolsWithCache(id);
        // 2. 解析 disabled_tools 集合
        Set<String> disabledTools = parseDisabledTools(server.getDisabledTools());
        // 3. 构建 VO 列表，序列化 inputSchema JSON
        List<McpToolVO> voList = new ArrayList<>();
        for (io.modelcontextprotocol.spec.McpSchema.Tool tool : mcpTools) {
            McpToolVO vo = new McpToolVO();
            vo.setName(tool.name());
            vo.setDescription(tool.description() != null ? tool.description() : "");
            vo.setEnabled(!disabledTools.contains(tool.name()));
            if (tool.inputSchema() != null) {
                try {
                    vo.setInputSchema(OBJECT_MAPPER.writeValueAsString(tool.inputSchema()));
                } catch (Exception e) {
                    vo.setInputSchema("{}");
                }
            }
            voList.add(vo);
        }
        return voList;
    }

    @Override
    public List<McpToolVO> refreshTools(Long id) {
        // 1. 清缓存并重新拉取，返回工具数量
        int toolCount = getMcpClientService().refreshServer(id);
        // 2. 同步成功则回写 lastSyncTime
        if (toolCount >= 0) {
            McpServer server = getById(id);
            if (server != null) {
                server.setLastSyncTime(java.time.LocalDateTime.now());
                updateById(server);
            }
        }
        // 3. 返回最新工具列表
        return listTools(id);
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.CACHE_MCP_SERVER, key = "#id")
    public void toggleTool(Long id, String toolName) {
        McpServer server = getById(id);
        if (server == null) {
            throw new BizException(ErrorCode.MCP_SERVER_NOT_FOUND);
        }
        // 1. 解析现有 disabled_tools
        Set<String> disabledTools = parseDisabledTools(server.getDisabledTools());
        // 2. 切换状态：在集合中则移除（启用），否则加入（禁用）
        if (disabledTools.contains(toolName)) {
            disabledTools.remove(toolName);
        } else {
            disabledTools.add(toolName);
        }
        // 3. 回写 JSONB 并清客户端缓存
        try {
            server.setDisabledTools(OBJECT_MAPPER.writeValueAsString(new ArrayList<>(disabledTools)));
            updateById(server);
            getMcpClientService().clearCache(id);
            log.info("[MCP] 工具状态切换: serverId={}, tool={}, enabled={}",
                    id, toolName, !disabledTools.contains(toolName));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, e);
        }
    }

    /**
     * 解析 disabled_tools JSON 数组为 Set，异常或空值返回空集合
     */
    private Set<String> parseDisabledTools(String disabledToolsJson) {
        if (disabledToolsJson == null || disabledToolsJson.isBlank()) {
            return new HashSet<>();
        }
        try {
            List<String> list = OBJECT_MAPPER.readValue(disabledToolsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            return new HashSet<>(list);
        } catch (Exception e) {
            log.warn("[MCP] 解析disabled_tools失败: {}", e.getMessage());
            return new HashSet<>();
        }
    }
}
