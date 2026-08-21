package com.lengbot.service.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chat 流式 SSE 订阅编排辅助类
 * <p>封装 SseEmitter 创建、Reactor 订阅、心跳/事件 ID/事件缓冲、断连清理等公共编排，
 * 让 Controller 仅做参数透传 + 返回 SseEmitter。</p>
 *
 * @author lw
 * @since 2026-07-19
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStreamSseHelper {

    /** SSE 超时时间：5 分钟（长文本生成可能较慢） */
    public static final long SSE_TIMEOUT = 5 * 60 * 1000L;

    /** SSE 心跳前缀（协议注释行） */
    private static final String HEARTBEAT_PREFIX = ":heartbeat";

    /** [REQUEST_ID] 前缀长度，用于提取 requestId */
    private static final int REQUEST_ID_PREFIX_LEN = "[REQUEST_ID]".length();

    private final SseEventBuffer eventBuffer;

    /**
     * 订阅 Chat Flux 并桥接到 SSE
     * <p>内部完成：SseEmitter 创建、boundedElastic 调度、背压缓冲、心跳识别、换行转义、
     * 事件 ID 递增、断线重连缓冲、disposal 协调。</p>
     *
     * @param flux  Chat 流（每个 chunk 为一段文本或 [STATUS]/[METADATA]/[DONE]/[REQUEST_ID] 协议事件）
     * @param userId 当前登录用户 ID（断线重连鉴权用，可空）
     * @return 已完成订阅编排的 SseEmitter
     */
    public SseEmitter subscribe(Flux<String> flux, Long userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        // 递增事件 ID，前端可基于 Last-Event-ID 断线重连
        AtomicInteger eventIdCounter = new AtomicInteger(0);
        // 从首个 [REQUEST_ID] 事件提取，用于缓冲归属
        final String[] activeRequestId = {null};
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        AtomicBoolean disposed = new AtomicBoolean(false);

        // 单次生效的释放闭包：取消订阅，避免 emitter 回调链多次触发重复 dispose
        Runnable disposeSubscription = () -> {
            if (disposed.compareAndSet(false, true)) {
                Disposable subscription = subscriptionRef.getAndSet(null);
                if (subscription != null && !subscription.isDisposed()) {
                    subscription.dispose();
                }
                log.debug("[Chat] SSE subscription disposed: requestId={}", activeRequestId[0]);
            }
        };

        // boundedElastic 调度避免阻塞 Servlet 线程，背压 DROP_OLDEST 兜底防上游堵塞
        Disposable subscription = flux.publishOn(Schedulers.boundedElastic(), 256)
                .onBackpressureBuffer(512,
                        dropped -> log.warn("[Chat] SSE 背压丢弃: requestId={}", activeRequestId[0]),
                        BufferOverflowStrategy.DROP_OLDEST)
                .subscribe(
                        chunk -> {
                            try {
                                // 心跳注释行：SSE 协议以冒号开头为注释，客户端忽略
                                if (HEARTBEAT_PREFIX.equals(chunk)) {
                                    emitter.send(SseEmitter.event().comment("heartbeat"));
                                    return;
                                }
                                // 文本内容中换行需转义，否则 SSE 解析截断；STATUS/METADATA/DONE/REQUEST_ID 协议事件无换行
                                String safe = chunk.startsWith("[STATUS]") || chunk.startsWith("[METADATA]")
                                        || chunk.startsWith("[DONE]") || chunk.startsWith("[REQUEST_ID]")
                                        ? chunk
                                        : chunk.replace("\n", "\\n");
                                String eventId = String.valueOf(eventIdCounter.incrementAndGet());
                                emitter.send(SseEmitter.event().id(eventId).data(safe));

                                // 首个 [REQUEST_ID] 事件提取 requestId，后续事件写入断线重连缓冲
                                if (activeRequestId[0] == null && safe.startsWith("[REQUEST_ID]")) {
                                    activeRequestId[0] = safe.substring(REQUEST_ID_PREFIX_LEN);
                                }
                                if (activeRequestId[0] != null) {
                                    eventBuffer.bufferEvent(activeRequestId[0],
                                            Integer.parseInt(eventId), safe, userId);
                                }
                            } catch (IOException e) {
                                disposeSubscription.run();
                                emitter.complete();
                                log.debug("[Chat] 客户端断开连接: {}", e.getMessage());
                            }
                        },
                        error -> {
                            disposeSubscription.run();
                            emitter.completeWithError(error);
                        },
                        () -> {
                            // [DONE] 已发送，标记缓冲完成便于断线重连快速判定
                            if (activeRequestId[0] != null) {
                                eventBuffer.markCompleted(activeRequestId[0]);
                            }
                            disposeSubscription.run();
                            emitter.complete();
                        }
                );
        subscriptionRef.set(subscription);
        // 并发兜底：subscribe 返回前若 emitter 已被异常 dispose，立即取消刚拿到的 subscription
        if (disposed.get() && !subscription.isDisposed()) {
            subscription.dispose();
        }

        // emitter 生命周期回调统一对接 disposeSubscription，保证任何一侧先触发都能释放订阅
        emitter.onCompletion(disposeSubscription);
        emitter.onTimeout(() -> {
            log.debug("[Chat] SSE timeout: requestId={}", activeRequestId[0]);
            disposeSubscription.run();
            emitter.complete();
        });
        emitter.onError(e -> disposeSubscription.run());
        emitter.onError(e -> log.warn("[Chat] SSE连接异常: {}", e.getMessage()));

        return emitter;
    }
}
