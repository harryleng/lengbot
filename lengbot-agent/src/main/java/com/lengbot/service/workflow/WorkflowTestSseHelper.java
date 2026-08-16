package com.lengbot.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.vo.WorkflowTestResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 工作流调试运行 SSE 推送辅助类（格式与 Chat 流式一致：[STATUS] + JSON、[DONE] + 最终结果）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowTestSseHelper {

    public static final String STATUS_PREFIX = "[STATUS]";
    public static final String DONE_PREFIX = "[DONE]";
    public static final long SSE_TIMEOUT = 5 * 60 * 1000L;

    private final ObjectMapper objectMapper;

    /**
     * 创建带超时与错误回调的 SSE 连接
     *
     * @return SseEmitter
     */
    public SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.warn("[WorkflowTest] SSE异常: {}", e.getMessage()));
        return emitter;
    }

    /**
     * 构建实时节点事件推送回调
     *
     * @param emitter SSE 连接
     * @param counter 事件序号
     * @return onEvent 回调
     */
    public Consumer<Map<String, Object>> eventSender(SseEmitter emitter, AtomicInteger counter) {
        Object sendLock = new Object();
        return event -> {
            synchronized (sendLock) {
                sendStatusEvent(emitter, counter, event);
            }
        };
    }

    /**
     * 推送单条 [STATUS] 结构化事件
     *
     * @param emitter SSE 连接
     * @param counter 事件序号
     * @param event   事件体
     */
    public void sendStatusEvent(SseEmitter emitter, AtomicInteger counter, Map<String, Object> event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(counter.incrementAndGet()))
                    .data(STATUS_PREFIX + json));
        } catch (IOException e) {
            log.debug("[WorkflowTest] 客户端断开: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[WorkflowTest] 推送事件失败: {}", e.getMessage());
        }
    }

    /**
     * 推送 [DONE] 并结束 SSE
     *
     * @param emitter SSE 连接
     * @param result  完整调试结果
     */
    public void sendDone(SseEmitter emitter, WorkflowTestResultVO result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            emitter.send(SseEmitter.event().data(DONE_PREFIX + json));
            emitter.complete();
        } catch (IOException e) {
            log.debug("[WorkflowTest] 发送 DONE 失败: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    /**
     * 推送错误事件并结束 SSE
     *
     * @param emitter SSE 连接
     * @param counter 事件序号
     * @param message 错误信息
     */
    public void sendErrorAndComplete(SseEmitter emitter, AtomicInteger counter, String message) {
        sendStatusEvent(emitter, counter, Map.of(
                "type", "error",
                "message", message != null ? message : "执行失败"));
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // ignore
        }
    }
}
