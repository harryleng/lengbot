package com.lengbot.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.common.BizException;
import com.lengbot.constant.ConfigKeys;
import com.lengbot.dto.UserPreferenceUpdateDTO;
import com.lengbot.vo.UserPreferenceVO;
import com.lengbot.entity.User;
import com.lengbot.enums.ErrorCode;
import com.lengbot.mapper.UserMapper;
import com.lengbot.service.UserPreferenceService;
import com.lengbot.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 用户个人配置服务实现
 *
 * @author lw
 * @since 2026-07-09
 */
@Service
@RequiredArgsConstructor
public class UserPreferenceServiceImpl implements UserPreferenceService {

    private static final boolean DEFAULT_LONG_MEMORY_ENABLED = false;
    private static final boolean DEFAULT_LONG_MEMORY_AUTO_EXTRACT = false;
    private static final int DEFAULT_LONG_MEMORY_INJECT_LIMIT = 6;
    private static final String DEFAULT_LONG_MEMORY_SCOPE = "user";

    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    @Override
    public UserPreferenceVO getCurrentPreferences() {
        return getPreferences(StpUtil.getLoginIdAsLong());
    }

    @Override
    public UserPreferenceVO updateCurrentPreferences(UserPreferenceUpdateDTO request) {
        long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 1. 读取现有配置，保留头像框/等级等不相关配置
        Map<String, Object> config = JsonUtil.parseJsonToMap(objectMapper, user.getConfig());

        // 2. 只更新用户个人配置字段，避免影响个人资料
        if (request.getLongMemoryEnabled() != null) {
            config.put(ConfigKeys.User.LONG_MEMORY_ENABLED, request.getLongMemoryEnabled());
        }
        if (request.getLongMemoryAutoExtract() != null) {
            config.put(ConfigKeys.User.LONG_MEMORY_AUTO_EXTRACT, request.getLongMemoryAutoExtract());
        }
        if (request.getLongMemoryInjectLimit() != null) {
            int limit = Math.max(1, Math.min(15, request.getLongMemoryInjectLimit()));
            config.put(ConfigKeys.User.LONG_MEMORY_INJECT_LIMIT, limit);
        }
        if (request.getLongMemoryScope() != null) {
            String scope = normalizeScope(request.getLongMemoryScope());
            config.put(ConfigKeys.User.LONG_MEMORY_SCOPE, scope);
        }

        try {
            user.setConfig(objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR);
        }
        userMapper.updateById(user);
        return toVO(config);
    }

    @Override
    public UserPreferenceVO getPreferences(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return toVO(JsonUtil.parseJsonToMap(objectMapper, user.getConfig()));
    }

    @Override
    public boolean isLongMemoryEnabled(Long userId) {
        return Boolean.TRUE.equals(getPreferences(userId).getLongMemoryEnabled());
    }

    private UserPreferenceVO toVO(Map<String, Object> config) {
        UserPreferenceVO vo = new UserPreferenceVO();
        vo.setLongMemoryEnabled(boolVal(config.get(ConfigKeys.User.LONG_MEMORY_ENABLED), DEFAULT_LONG_MEMORY_ENABLED));
        vo.setLongMemoryAutoExtract(boolVal(config.get(ConfigKeys.User.LONG_MEMORY_AUTO_EXTRACT), DEFAULT_LONG_MEMORY_AUTO_EXTRACT));
        vo.setLongMemoryInjectLimit(intVal(config.get(ConfigKeys.User.LONG_MEMORY_INJECT_LIMIT), DEFAULT_LONG_MEMORY_INJECT_LIMIT, 1, 15));
        vo.setLongMemoryScope(normalizeScope(String.valueOf(config.getOrDefault(ConfigKeys.User.LONG_MEMORY_SCOPE, DEFAULT_LONG_MEMORY_SCOPE))));
        return vo;
    }

    private boolean boolVal(Object value, boolean defaultValue) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    private int intVal(Object value, int defaultValue, int min, int max) {
        int parsed = value instanceof Number n ? n.intValue() : defaultValue;
        return Math.max(min, Math.min(max, parsed));
    }

    private String normalizeScope(String scope) {
        return "agent".equalsIgnoreCase(scope) ? "agent" : DEFAULT_LONG_MEMORY_SCOPE;
    }
}
