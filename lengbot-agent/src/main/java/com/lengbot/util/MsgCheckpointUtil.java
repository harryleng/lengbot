package com.lengbot.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * AgentScope {@link Msg} 列表与 JSON 的互转工具（Checkpointer 状态快照用）。
 * <p>采用 best-effort 策略：序列化 / 反序列化失败时返回 null / 空列表并打印 warn 日志，
 * 绝不影响主流程（检查点落盘与续跑恢复都依赖此健壮性）。</p>
 * <p>注意：AgentScope 的 Msg 多态类型若缺少 Jackson 多态注解，反序列化回具体子类可能失败，
 * 此时续跑将退回"从数据库历史消息重建"的降级路径（已在 ChatServiceImpl 续跑逻辑中处理）。</p>
 *
 * @author lw
 * @since 2026-10-19
 */
@Slf4j
public final class MsgCheckpointUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MsgCheckpointUtil() {
    }

    /**
     * 将消息列表序列化为 JSON。
     *
     * @param messages 消息列表（可为 null）
     * @return JSON 字符串；失败返回 null
     */
    public static String toJson(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(messages);
        } catch (Exception e) {
            log.warn("[Checkpointer][Msg] 序列化 messages 失败（检查点仅保留标量快照）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 JSON 反序列化为消息列表（用于续跑恢复 in-flight 对话）。
     *
     * @param json JSON 字符串
     * @return 消息列表；为空或失败返回空列表
     */
    public static List<Msg> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Msg>>() {});
        } catch (Exception e) {
            log.warn("[Checkpointer][Msg] 反序列化 messages 失败（续跑将退回数据库历史消息）: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
