package com.lengbot.service.chat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话 run 事件总线：将 agent 执行流与 SSE 连接解耦。
 * <p>每个 requestId 对应一个 replay sink（保留最近 REPLAY_LIMIT 条事件），agent run 作为唯一生产者
 * 向 sink 推送事件，SSE 端点只是 sink 的一个冷订阅（tap）。客户端断连仅取消该 tap，run 继续跑完并落库；
 * 客户端重连时通过 {@code /api/chat/stream/reconnect} 用同一 requestId 重新订阅同一 sink，
 * 从断点（含 replay 回放缓冲）继续接收，run 不会被中断。</p>
 *
 * @author lw
 * @since 2026-10-20
 */
@Slf4j
@Component
public class ChatRunBus {

    public enum RunStatus { RUNNING, DONE, CANCELLED }

    /** run 句柄：事件 sink + 上下文（用于归属校验）+ 状态 */
    private record Handle(Sinks.Many<String> sink, ChatContext ctx, AtomicReference<RunStatus> status) {}

    /** replay 缓冲大小：覆盖断连期间的事件缺口（长文本报告也足够） */
    private static final int REPLAY_LIMIT = 512;

    /** requestId → 句柄；6 分钟无访问自动过期回收，避免内存泄漏 */
    private final Cache<String, Handle> runs = Caffeine.newBuilder()
            .expireAfterWrite(6, TimeUnit.MINUTES)
            .maximumSize(5000)
            .build();

    /**
     * 注册一个 run，返回其事件 sink（带最近 REPLAY_LIMIT 条事件回放，供断线后重连补差）。
     *
     * @param requestId 请求ID（即 runId）
     * @param ctx       对话上下文（用于归属校验）
     * @return 事件 sink
     */
    public Sinks.Many<String> create(String requestId, ChatContext ctx) {
        Sinks.Many<String> sink = Sinks.many().replay().limit(REPLAY_LIMIT);
        runs.put(requestId, new Handle(sink, ctx, new AtomicReference<>(RunStatus.RUNNING)));
        return sink;
    }

    /**
     * 取 run 的事件 sink 用于 SSE 订阅（断线重连）。
     *
     * @param requestId 请求ID
     * @param userId    当前用户ID（非空时校验归属，避免越权订阅他人 run）
     * @return sink；不存在或无权限时返回 null
     */
    public Sinks.Many<String> sinkOf(String requestId, Long userId) {
        Handle h = runs.getIfPresent(requestId);
        if (h == null) {
            return null;
        }
        if (userId != null && h.ctx().getUserId() != null && !h.ctx().getUserId().equals(userId)) {
            log.warn("[ChatRunBus] 越权订阅被拒: requestId={}, userId={}, owner={}",
                    requestId, userId, h.ctx().getUserId());
            return null;
        }
        return h.sink();
    }

    public RunStatus statusOf(String requestId) {
        Handle h = runs.getIfPresent(requestId);
        return h == null ? null : h.status().get();
    }

    public void markDone(String requestId) {
        Handle h = runs.getIfPresent(requestId);
        if (h != null) {
            h.status().set(RunStatus.DONE);
        }
    }

    public void markCancelled(String requestId) {
        Handle h = runs.getIfPresent(requestId);
        if (h != null) {
            h.status().set(RunStatus.CANCELLED);
        }
    }
}
