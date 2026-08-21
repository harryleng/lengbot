package com.lengbot.config;

import cn.dev33.satoken.stp.StpInterface;
import com.lengbot.entity.User;
import com.lengbot.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 角色/权限数据源：从 UserService 读取角色，供 {@code StpUtil.checkRole} 使用。
 *
 * @author lw
 * @since 2026-07-07
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final UserService userService;

    /**
     * 返回账号拥有的角色列表
     *
     * @param loginId   账号 id
     * @param loginType 账号类型
     * @return 角色 code 列表（如 admin / user）
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        User user = userService.getById(parseLoginId(loginId));
        if (user == null || user.getRole() == null) {
            return Collections.emptyList();
        }
        return List.of(user.getRole().getCode());
    }

    /**
     * 返回账号拥有的权限列表（当前未使用细粒度权限）
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }

    private Long parseLoginId(Object loginId) {
        if (loginId instanceof Long id) {
            return id;
        }
        if (loginId instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(loginId));
    }
}
