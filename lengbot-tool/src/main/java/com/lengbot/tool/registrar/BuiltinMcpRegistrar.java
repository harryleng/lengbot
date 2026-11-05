package com.lengbot.tool.registrar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.entity.McpServer;
import com.lengbot.enums.CommonStatus;
import com.lengbot.enums.McpInstallType;
import com.lengbot.enums.McpTransportType;
import com.lengbot.service.McpServerService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 内置 MCP Server 自动注册器
 * <p>启动时检查数据库，缺失的内置 MCP Server 自动插入（仅 DB 记录，不做工具发现）</p>
 *
 * @author lw
 * @since 2026-05-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinMcpRegistrar {

    private final McpServerService mcpServerService;
    private final ObjectMapper objectMapper;

    /** 旧内置 sequentialthinking 的远程地址；该公共托管域名已下线（DNS NXDOMAIN） */
    private static final String LEGACY_SEQUENTIAL_THINKING_HOST =
            "https://remote.mcpservers.org/sequentialthinking/mcp";

    /**
     * 锁定 1.0.0：2.x 声明 engines node&gt;=24，当前部署环境为 Node 22，
     * 启动时 npx 会持续报 EBADENGINE；日期版 2025.7.1 已 deprecated。
     * 1.0.0 在 Node 22 实测可完成 initialize 握手且无告警。
     */
    private static final String SEQUENTIAL_THINKING_PACKAGE =
            "@modelcontextprotocol/server-sequential-thinking@1.0.0";

    /** 内置 MCP Server 定义 */
    private static final List<BuiltInMcp> BUILT_IN_MCPS = List.of(
            new BuiltInMcp(
                    "sequentialthinking",
                    "顺序思考工具，帮助AI将复杂问题分解为多个步骤",
                    "BranchesOutlined",
                    McpInstallType.NPX,
                    McpTransportType.STDIO,
                    null,
                    Map.of("packageName", SEQUENTIAL_THINKING_PACKAGE),
                    LEGACY_SEQUENTIAL_THINKING_HOST
            ),
            new BuiltInMcp(
                    "mcp-server-chart",
                    "图表生成工具，支持生成柱状图、折线图、饼图等各类图表",
                    "BarChartOutlined",
                    McpInstallType.NPX,
                    McpTransportType.STDIO,
                    null,
                    Map.of("packageName", "@antv/mcp-server-chart"),
                    null
            )
    );

    @PostConstruct
    public void registerBuiltInMcpServers() {
        int imported = 0;
        for (BuiltInMcp def : BUILT_IN_MCPS) {
            try {
                // 1. 检查是否已存在
                McpServer existing = mcpServerService.getOne(
                        new LambdaQueryWrapper<McpServer>()
                                .eq(McpServer::getName, def.name)
                                .last("LIMIT 1"));

                if (existing == null) {
                    // 2. 不存在 → 插入
                    McpServer server = new McpServer();
                    server.setName(def.name);
                    server.setDescription(def.description);
                    server.setIcon(def.icon);
                    server.setInstallType(def.installType);
                    server.setTransport(def.transport);
                    server.setHost(def.host);
                    server.setIsBuiltin(1);
                    server.setDeployConfig(def.deployConfig != null
                            ? objectMapper.writeValueAsString(def.deployConfig) : null);
                    server.setStatus(CommonStatus.DISABLED);
                    mcpServerService.save(server);
                    imported++;
                    log.info("[BuiltinMcpRegistrar] 导入内置 MCP Server: name={}, id={}", def.name, server.getId());
                } else {
                    boolean changed = false;
                    // 历史版本已注册的内置 MCP 没有 is_builtin 字段，启动时补齐身份以便前端统一展示。
                    if (!Integer.valueOf(1).equals(existing.getIsBuiltin())) {
                        existing.setIsBuiltin(1);
                        changed = true;
                        log.info("[BuiltinMcpRegistrar] 补齐内置 MCP 标识: name={}, id={}", def.name, existing.getId());
                    }
                    // 旧内置 sequentialthinking 指向已下线的远程域名，迁移到本地 npx/stdio。
                    // 精确匹配该旧地址，避免覆盖用户自行改过的配置；迁移后 host 置空，条件不再命中 → 幂等。
                    if (def.legacyHost != null && def.legacyHost.equals(existing.getHost())) {
                        existing.setInstallType(def.installType);
                        existing.setTransport(def.transport);
                        // 用空串而非 null：MyBatis-Plus 默认策略不会更新 null 字段，host 会残留旧地址
                        existing.setHost("");
                        existing.setDeployConfig(def.deployConfig != null
                                ? objectMapper.writeValueAsString(def.deployConfig) : null);
                        changed = true;
                        log.info("[BuiltinMcpRegistrar] 迁移失效远程内置 MCP 为本地 stdio: name={}, id={}",
                                def.name, existing.getId());
                    }
                    if (changed) {
                        // 走 Service 的 updateById，触发 @CacheEvict 清掉 Redis 里的旧 MCP 配置缓存
                        mcpServerService.updateById(existing);
                    }
                }
            } catch (Exception e) {
                log.warn("[BuiltinMcpRegistrar] 处理内置 MCP Server 失败: name={}, error={}", def.name, e.getMessage());
            }
        }
        log.info("[BuiltinMcpRegistrar] 内置 MCP Server 扫描完成: total={}, imported={}", BUILT_IN_MCPS.size(), imported);
    }

    /**
     * 内置 MCP Server 定义
     */
    private record BuiltInMcp(
            String name,
            String description,
            String icon,
            McpInstallType installType,
            McpTransportType transport,
            String host,
            Map<String, Object> deployConfig,
            /** 该内置项历史上使用过的失效地址，非空时启动会自动迁移；null 表示无需迁移 */
            String legacyHost
    ) {}
}
