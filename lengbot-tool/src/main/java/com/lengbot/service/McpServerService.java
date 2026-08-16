package com.lengbot.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.lengbot.dto.McpServerRequestDTO;
import com.lengbot.entity.McpServer;
import com.lengbot.vo.McpToolVO;

import java.util.List;

/**
 * MCP Server 服务接口
 *
 * @author finch
 * @since 2026-05-20
 */
public interface McpServerService extends IService<McpServer> {

    /**
     * 创建 MCP Server
     *
     * @param request 创建请求
     * @return MCP Server
     */
    McpServer create(McpServerRequestDTO request);

    /**
     * 更新 MCP Server
     *
     * @param request 更新请求
     * @return MCP Server
     */
    McpServer update(McpServerRequestDTO request);

    /**
     * 分页查询
     *
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 分页结果
     */
    Page<McpServer> listPage(int pageNum, int pageSize, String name);

    /**
     * 删除 MCP Server
     *
     * @param id 主键ID
     */
    void deleteById(Long id);

    /**
     * 启用/禁用 MCP Server
     *
     * @param id      主键ID
     * @param enabled true启用，false禁用
     */
    void setEnabled(Long id, boolean enabled);

    /**
     * 列出 MCP Server 暴露的工具（带 disabled 状态、参数 Schema）
     *
     * @param id MCP Server ID
     * @return 工具 VO 列表
     */
    List<McpToolVO> listTools(Long id);

    /**
     * 刷新 MCP Server 工具缓存并回写 lastSyncTime
     *
     * @param id MCP Server ID
     * @return 刷新后的工具列表
     */
    List<McpToolVO> refreshTools(Long id);

    /**
     * 切换单个 MCP 工具的启用状态（读写 disabled_tools JSONB）
     *
     * @param id       MCP Server ID
     * @param toolName 工具名
     */
    void toggleTool(Long id, String toolName);
}
