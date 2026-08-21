package com.lengbot.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.lengbot.dto.ToolRequestDTO;
import com.lengbot.entity.Tool;
import io.agentscope.core.tool.ToolBase;

import java.util.List;
import java.util.Map;

/**
 * Tool 服务接口
 *
 * @author lw
 * @since 2026-05-20
 */
public interface ToolService extends IService<Tool> {

    Tool create(ToolRequestDTO request);

    Tool update(ToolRequestDTO request);

    Page<Tool> listPage(int pageNum, int pageSize, String name);

    Page<Tool> listTools(int pageNum, int pageSize, String toolType);

    Page<Tool> listToolsWithFilter(int pageNum, int pageSize, String keyword, String toolType, String tag);

    void deleteById(Long id);

    /**
     * 启用/禁用 Tool
     *
     * @param id      主键ID
     * @param enabled true启用，false禁用
     */
    void setEnabled(Long id, boolean enabled);

    /**
     * 按工具名列表解析为 AgentScope ToolBase
     *
     * @param toolNames 工具名列表
     * @return ToolBase 列表
     */
    List<ToolBase> resolveToolCallbacks(List<String> toolNames);

    /**
     * 按工具ID列表解析为 AgentScope ToolBase
     *
     * @param toolIds 工具ID列表
     * @return ToolBase 列表
     */
    List<ToolBase> resolveToolCallbacksByIds(List<Long> toolIds);

    /**
     * 测试执行单个工具
     *
     * @param toolId 工具ID
     * @param args   JSON格式的工具参数
     * @return 执行结果
     */
    String testTool(Long toolId, String args);

    /**
     * 获取工具示例参数
     *
     * @param toolId 工具ID
     * @return 示例参数Map
     */
    Map<String, Object> getExampleParams(Long toolId);

    /**
     * 从 inputSchema 解析必填参数名（工作流执行时仅对必填项补 example）
     *
     * @param toolId 工具ID
     * @return 必填参数名集合
     */
    java.util.Set<String> getRequiredParamKeys(Long toolId);

    /**
     * 获取工具 IO Schema（工作流 tool 节点参数映射）
     *
     * @param toolId 工具ID
     * @return inputs / outputs 列表
     */
    Map<String, Object> getIoSchema(Long toolId);

    /**
     * 清理悬空工具ID：过滤掉已被删除的工具
     *
     * @param toolIds 工具ID列表（字符串形式）
     * @return 过滤后仍存在的工具ID列表
     */
    List<String> cleanStaleToolIds(List<String> toolIds);
}
