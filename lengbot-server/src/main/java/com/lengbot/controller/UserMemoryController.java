package com.lengbot.controller;

import com.lengbot.common.Result;
import com.lengbot.dto.UserMemoryRequestDTO;
import com.lengbot.vo.UserMemoryVO;
import com.lengbot.service.UserMemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户长期记忆接口
 *
 * @author finch
 * @since 2026-07-09
 */
@Tag(name = "用户长期记忆", description = "查看、编辑和管理用户长期记忆")
@RestController
@RequestMapping("/api/user/memories")
@RequiredArgsConstructor
public class UserMemoryController {

    private final UserMemoryService userMemoryService;

    /**
     * 查询当前用户长期记忆
     *
     * @param keyword 内容关键词
     * @param status 记忆状态
     * @return 长期记忆列表
     */
    @Operation(summary = "查询当前用户长期记忆")
    @GetMapping
    public Result<List<UserMemoryVO>> listMemories(@RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String status) {
        return Result.ok(userMemoryService.listCurrentUserMemories(keyword, status));
    }

    /**
     * 新增当前用户长期记忆
     *
     * @param request 新增请求
     * @return 新增后的长期记忆
     */
    @Operation(summary = "新增当前用户长期记忆")
    @PostMapping
    public Result<UserMemoryVO> createMemory(@Valid @RequestBody UserMemoryRequestDTO request) {
        return Result.ok(userMemoryService.createCurrentUserMemory(request));
    }

    /**
     * 更新当前用户长期记忆
     *
     * @param id 记忆ID
     * @param request 更新请求
     * @return 更新后的长期记忆
     */
    @Operation(summary = "更新当前用户长期记忆")
    @PutMapping("/{id}")
    public Result<UserMemoryVO> updateMemory(@PathVariable Long id,
                                             @Valid @RequestBody UserMemoryRequestDTO request) {
        return Result.ok(userMemoryService.updateCurrentUserMemory(id, request));
    }

    /**
     * 更新当前用户长期记忆状态
     *
     * @param id 记忆ID
     * @param request 状态请求
     * @return 更新后的长期记忆
     */
    @Operation(summary = "更新当前用户长期记忆状态")
    @PutMapping("/{id}/status")
    public Result<UserMemoryVO> updateMemoryStatus(@PathVariable Long id,
                                                   @Valid @RequestBody UserMemoryStatusRequest request) {
        return Result.ok(userMemoryService.updateCurrentUserMemoryStatus(id, request.getStatus()));
    }

    /**
     * 删除当前用户长期记忆
     *
     * @param id 记忆ID
     * @return 空响应
     */
    @Operation(summary = "删除当前用户长期记忆")
    @DeleteMapping("/{id}")
    public Result<Void> deleteMemory(@PathVariable Long id) {
        userMemoryService.deleteCurrentUserMemory(id);
        return Result.ok();
    }

    @Data
    public static class UserMemoryStatusRequest {
        private String status;
    }
}
