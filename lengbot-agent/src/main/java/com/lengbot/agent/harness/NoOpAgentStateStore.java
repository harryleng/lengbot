package com.lengbot.agent.harness;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 不落地、不累积的 {@link AgentStateStore}：所有读返回空，所有写为 no-op。
 *
 * <p><b>用途</b>：配合「LengBot 自管会话历史」策略。LengBot 的 {@code MessageMiddleware}
 * 每次调用都组装<b>全量</b>消息列表（含历史/摘要/附件/工具预算提示等）传入 agent；
 * 若 HarnessAgent 再用默认的 {@code JsonFileAgentStateStore} 在 {@code (userId,sessionId)}
 * 槽位累积历史，会造成<b>历史重复</b>（传入的全量 + agent 自己存的）。</p>
 *
 * <p>用本 no-op store 后：每次调用 agent 从空状态开始，仅消费传入的 {@code List<Msg>}，
 * 调用结束不持久化--历史归属完全由 LengBot 侧掌控，与现有 legacy 分支行为一致。</p>
 *
 * <p><b>注意</b>：这放弃了 HarnessAgent 的跨调用记忆压缩、记忆工具长期记忆等能力。
 * LengBot 已有等价机制（{@code MessageMiddleware.summarizeIfNeeded} 上下文压缩、
 * {@code UserMemoryService} 长期记忆），故可接受。Phase 1 接入后需验证 ReActAgent
 * 在空 store 下能正确从传入消息构建单次上下文。</p>
 *
 * @author Senior Developer (LengBot refactor)
 * @since 1.0.0
 */
public class NoOpAgentStateStore implements AgentStateStore {

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        // no-op：不持久化
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        // no-op：不持久化
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        return Optional.empty();
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        return List.of();
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return false;
    }

    @Override
    public void delete(String userId, String sessionId) {
        // no-op
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        // no-op
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return Set.of();
    }
}
