package com.lengbot.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat 流式会话中止注册表。
 * <p>维护 requestId → ChatContext 映射，供停止端点显式触发 in-flight 对话的中断。</p>
 *
 * @author lw
 * @since 2026-07-13
 */
@Slf4j
@Component
public class ChatAbortRegistry {

    private final ConcurrentHashMap<String, ChatContext> registry = new ConcurrentHashMap<>();

    /** 注册一个进行中的对话上下文。 */
    public void register(String requestId, ChatContext ctx) {
        if (requestId == null || ctx == null) {
            return;
        }
        registry.put(requestId, ctx);
    }

    /** 移除已结束的对话上下文（doFinally 中调用）。 */
    public void remove(String requestId) {
        if (requestId == null) {
            return;
        }
        registry.remove(requestId);
    }

    /**
     * 请求中断指定对话。校验归属后置 aborted 标记，使 in-flight LLM 轮次立即停止。
     *
     * @param requestId 对话请求 ID
     * @param userId    发起停止的用户 ID（用于归属校验）
     * @return true 表示命中并已请求中断，false 表示未找到或无权
     */
    public boolean abort(String requestId, Long userId) {
        if (requestId == null) {
            return false;
        }
        ChatContext ctx = registry.get(requestId);
        if (ctx == null) {
            return false;
        }
        if (userId != null && ctx.getUserId() != null && !userId.equals(ctx.getUserId())) {
            log.warn("[Chat] 停止请求归属校验失败: requestId=[{}], userId=[{}], ownerId=[{}]",
                    requestId, userId, ctx.getUserId());
            return false;
        }
        ctx.requestAbort("USER_STOP");
        log.info("[Chat] 用户请求停止对话: requestId=[{}], userId=[{}]", requestId, userId);
        return true;
    }
}
