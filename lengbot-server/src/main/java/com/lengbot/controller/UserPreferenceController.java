package com.lengbot.controller;

import com.lengbot.common.Result;
import com.lengbot.dto.UserPreferenceUpdateDTO;
import com.lengbot.vo.UserPreferenceVO;
import com.lengbot.service.UserPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户个人配置接口
 *
 * @author finch
 * @since 2026-07-09
 */
@Tag(name = "用户个人配置", description = "用户偏好与长期记忆开关")
@RestController
@RequestMapping("/api/user/preferences")
@RequiredArgsConstructor
public class UserPreferenceController {

    private final UserPreferenceService userPreferenceService;

    /**
     * 获取当前用户个人配置
     *
     * @return 当前用户个人配置
     */
    @Operation(summary = "获取当前用户个人配置")
    @GetMapping
    public Result<UserPreferenceVO> getPreferences() {
        return Result.ok(userPreferenceService.getCurrentPreferences());
    }

    /**
     * 更新当前用户个人配置
     *
     * @param request 更新请求
     * @return 更新后的个人配置
     */
    @Operation(summary = "更新当前用户个人配置")
    @PutMapping
    public Result<UserPreferenceVO> updatePreferences(@Valid @RequestBody UserPreferenceUpdateDTO request) {
        return Result.ok(userPreferenceService.updateCurrentPreferences(request));
    }
}
