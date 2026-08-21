package com.lengbot.service.port;

/**
 * 默认 Agent 解析端口
 * <p>工具测试执行时，若参数未携带 agentId，需要兜底取当前用户的默认 Agent。
 * tool 模块位于 agent 之下，不能反向依赖 AgentService，故定义此端口由 agent 层实现，
 * 保持依赖方向永远向下。</p>
 *
 * @author lw
 * @since 2026-07-12
 */
public interface DefaultAgentIdProvider {

    /**
     * 获取指定用户的默认 Agent ID
     *
     * @param userId 用户ID
     * @return 默认 Agent ID，无默认 Agent 时返回 null
     */
    Long getDefaultAgentId(long userId);
}
