package com.lengbot.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lengbot.common.Result;
import com.lengbot.dto.McpServerRequestDTO;
import com.lengbot.vo.McpToolVO;
import com.lengbot.entity.McpServer;
import com.lengbot.service.McpClientService;
import com.lengbot.service.McpServerService;
import io.modelcontextprotocol.spec.McpSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "MCP Server管理", description = "MCP Server的增删改查")
@RestController
@RequestMapping("/api/mcp-servers")
@RequiredArgsConstructor
public class McpServerController {

    private final McpServerService mcpServerService;
    private final McpClientService mcpClientService;

    @Operation(summary = "新增MCP Server")
    @PostMapping
    public Result<McpServer> create(@Valid @RequestBody McpServerRequestDTO request) {
        return Result.ok(mcpServerService.create(request));
    }

    @Operation(summary = "更新MCP Server")
    @PutMapping
    public Result<McpServer> update(@Valid @RequestBody McpServerRequestDTO request) {
        return Result.ok(mcpServerService.update(request));
    }

    @Operation(summary = "删除MCP Server")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        mcpServerService.deleteById(id);
        return Result.ok();
    }

    @Operation(summary = "分页查询MCP Server")
    @GetMapping
    public Result<Page<McpServer>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String name) {
        return Result.ok(mcpServerService.listPage(pageNum, pageSize, name));
    }

    @Operation(summary = "获取单个MCP Server")
    @GetMapping("/{id}")
    public Result<McpServer> getById(@PathVariable Long id) {
        return Result.ok(mcpServerService.getById(id));
    }

    @Operation(summary = "测试MCP Server连接")
    @PostMapping("/{id}/test")
    public Result<List<McpSchema.Tool>> testConnection(@PathVariable Long id) {
        return Result.ok(mcpClientService.testConnection(id));
    }

    @Operation(summary = "获取MCP Server的工具列表（运行时，含参数详情）")
    @GetMapping("/{id}/tools")
    public Result<List<McpToolVO>> listTools(@PathVariable Long id) {
        return Result.ok(mcpServerService.listTools(id));
    }

    @Operation(summary = "刷新MCP Server工具（清除缓存重新获取）")
    @PostMapping("/{id}/tools/refresh")
    public Result<List<McpToolVO>> refreshTools(@PathVariable Long id) {
        return Result.ok(mcpServerService.refreshTools(id));
    }

    @Operation(summary = "启用/禁用MCP Server")
    @PutMapping("/{id}/enabled")
    public Result<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        mcpServerService.setEnabled(id, enabled);
        return Result.ok();
    }

    @Operation(summary = "启用/禁用MCP工具")
    @PutMapping("/{id}/tools/{toolName}/toggle")
    public Result<Void> toggleTool(@PathVariable Long id, @PathVariable String toolName) {
        mcpServerService.toggleTool(id, toolName);
        return Result.ok();
    }
}
