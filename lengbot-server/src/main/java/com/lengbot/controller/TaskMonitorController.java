package com.lengbot.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lengbot.common.Result;
import com.lengbot.enums.UserRole;
import com.lengbot.task.TaskQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务队列监控指标接口（仅管理员）
 *
 * <p>暴露 Stream/ZSet/PEL 关键计数，便于线上排队堆积、消费延迟、死信累积的运维观测。
 * 通过 Sa-Token {@code StpUtil.checkRole} 强制 ADMIN 角色访问。</p>
 *
 * @author finch
 * @since 2026-07-18
 */
@Tag(name = "任务队列监控", description = "Stream / PEL / 死信 / 延迟队列运行时指标（仅管理员）")
@RestController
@RequestMapping("/api/admin/task-monitor")
@RequiredArgsConstructor
public class TaskMonitorController {

    private final TaskQueueService taskQueueService;

    @Operation(summary = "采集任务队列运行时指标（main XLEN / 死信 XLEN / PEL 大小 / 延迟 ZSet 大小）")
    @GetMapping("/metrics")
    public Result<Map<String, Object>> metrics() {
        StpUtil.checkRole(UserRole.ADMIN.getCode());

        TaskQueueService.QueueMetrics m = taskQueueService.snapshotMetrics();
        Map<String, Object> body = new LinkedHashMap<>(5);
        body.put("mainStreamLen", m.mainStreamLen());
        body.put("deadLetterStreamLen", m.deadLetterStreamLen());
        body.put("defaultPendingEntries", m.defaultPendingEntries());
        body.put("heavyPendingEntries", m.heavyPendingEntries());
        body.put("delayedSize", m.delayedSize());
        return Result.ok(body);
    }
}
