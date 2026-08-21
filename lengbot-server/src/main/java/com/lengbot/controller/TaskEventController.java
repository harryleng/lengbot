package com.lengbot.controller;

import com.lengbot.common.Result;
import com.lengbot.enums.TaskStatus;
import com.lengbot.service.TaskService;
import com.lengbot.service.port.TaskCountNotifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务事件 SSE 推送，实时通知前端运行中任务数量变化
 * <p>任务创建/状态变更时由 TaskServiceImpl 主动触发推送</p>
 *
 * @author lw
 * @since 2026-05-21
 */
@Slf4j
@Tag(name = "任务事件", description = "任务状态实时推送")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskEventController implements TaskCountNotifier {

    private final TaskService taskService;

    /** 每个用户一个 SSE 连接 */
    private static final Map<Long, SseEmitter> USER_EMITTERS = new ConcurrentHashMap<>();

    @Operation(summary = "任务计数实时推送（SSE）")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        Long userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        // 关闭同一用户的旧连接
        SseEmitter old = USER_EMITTERS.put(userId, emitter);
        if (old != null) {
            old.complete();
        }
        emitter.onCompletion(() -> USER_EMITTERS.remove(userId, emitter));
        emitter.onTimeout(() -> USER_EMITTERS.remove(userId, emitter));
        emitter.onError(e -> USER_EMITTERS.remove(userId, emitter));

        // 立即推送当前计数
        sendCount(userId, emitter);

        return emitter;
    }

    @Operation(summary = "任务计数查询（HTTP 兜底）", description = "SSE 断线或首次进入任务中心时拉取一次纠正徽标")
    @GetMapping("/count")
    public Result<Map<String, Long>> count() {
        Long userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        return Result.ok(buildCounts(userId));
    }

    /**
     * 供 TaskServiceImpl 在任务创建/状态变更时调用，立即推送最新计数给对应用户
     */
    @Override
    public void notifyUser(Long userId) {
        SseEmitter emitter = USER_EMITTERS.get(userId);
        if (emitter != null) {
            sendCount(userId, emitter);
        }
    }

    private void sendCount(Long userId, SseEmitter emitter) {
        try {
            // 推送分状态计数 JSON，前端同时用于导航角标和任务中心
            emitter.send(SseEmitter.event().name("count").data(buildCounts(userId)));
        } catch (IOException | IllegalStateException e) {
            USER_EMITTERS.remove(userId, emitter);
        } catch (Exception e) {
            log.warn("[TaskEvent] 推送失败, userId={}", userId, e);
        }
    }

    /**
     * 汇总用户各未完结状态任务数，SSE 推送与 HTTP 查询共用，避免双写逻辑漂移
     *
     * @param userId 当前登录用户ID
     * @return active=未完结总数；pending/running/pendingRetry 为分状态计数
     */
    private Map<String, Long> buildCounts(Long userId) {
        long pending = taskService.countByStatus(userId, TaskStatus.PENDING.getCode());
        long running = taskService.countByStatus(userId, TaskStatus.RUNNING.getCode());
        long pendingRetry = taskService.countByStatus(userId, TaskStatus.PENDING_RETRY.getCode());
        // active 涵盖所有未完结状态：等待中 + 执行中 + 等待重试
        long active = pending + running + pendingRetry;
        return Map.of(
                "active", active,
                "pending", pending,
                "running", running,
                "pendingRetry", pendingRetry);
    }
}
