package com.lengbot.service.impl;

import com.lengbot.entity.Agent;
import com.lengbot.service.AgentService;
import com.lengbot.service.port.DefaultAgentIdProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 默认 Agent 解析端口实现
 * <p>由 agent 层实现 tool 层定义的 {@link DefaultAgentIdProvider} 端口，
 * 供工具测试执行时兜底解析当前用户默认 Agent，依赖方向向下。</p>
 *
 * @author lw
 * @since 2026-07-12
 */
@Component
@RequiredArgsConstructor
public class DefaultAgentIdProviderImpl implements DefaultAgentIdProvider {

    private final AgentService agentService;

    @Override
    public Long getDefaultAgentId(long userId) {
        Agent defaultAgent = agentService.getDefaultAgent(userId);
        return defaultAgent != null ? defaultAgent.getId() : null;
    }
}
