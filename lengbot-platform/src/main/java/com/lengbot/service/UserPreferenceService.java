package com.lengbot.service;

import com.lengbot.dto.UserPreferenceUpdateDTO;
import com.lengbot.vo.UserPreferenceVO;

/**
 * 用户个人配置服务
 *
 * @author finch
 * @since 2026-07-09
 */
public interface UserPreferenceService {

    /**
     * 获取当前用户个人配置
     *
     * @return 用户个人配置
     */
    UserPreferenceVO getCurrentPreferences();

    /**
     * 更新当前用户个人配置
     *
     * @param request 更新请求
     * @return 更新后的配置
     */
    UserPreferenceVO updateCurrentPreferences(UserPreferenceUpdateDTO request);

    /**
     * 获取指定用户个人配置
     *
     * @param userId 用户ID
     * @return 用户个人配置
     */
    UserPreferenceVO getPreferences(Long userId);

    /**
     * 判断指定用户是否启用长期记忆
     *
     * @param userId 用户ID
     * @return 是否启用
     */
    boolean isLongMemoryEnabled(Long userId);
}
