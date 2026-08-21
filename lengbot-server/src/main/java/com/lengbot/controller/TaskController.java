package com.lengbot.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lengbot.common.Result;
import com.lengbot.entity.Task;
import com.lengbot.service.TaskService;
import com.lengbot.task.ProgressSnapshot;
import com.lengbot.task.TaskQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 任务管理接口
 *
 * @author lw
 * @since 2026-05-21
 */
@Tag(name = "任务管理", description = "任务查询、取消")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskQueueService taskQueueService;

    @Operation(summary = "分页查询当前用户的任务列表")
    @GetMapping
    public Result<Page<Task>> list(@RequestParam(defaultValue = "1") int pageNum,
                                    @RequestParam(defaultValue = "10") int pageSize,
                                    @RequestParam(required = false) String name,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String type) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(taskService.listByUserId(userId, pageNum, pageSize, name, status, type));
    }

    @Operation(summary = "统计当前用户的运行中+待处理任务数")
    @GetMapping("/running-count")
    public Result<Long> runningCount() {
        long userId = StpUtil.getLoginIdAsLong();
        Long running = taskService.countByStatus(userId, "running");
        Long pending = taskService.countByStatus(userId, "pending");
        Long pendingRetry = taskService.countByStatus(userId, "pending_retry");
        return Result.ok(running + pending + pendingRetry);
    }

    @Operation(summary = "按类型统计当前用户的进行中任务数")
    @GetMapping("/type-counts")
    public Result<Map<String, Long>> typeCounts() {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(taskService.countByType(userId));
    }

    @Operation(summary = "获取任务详情（进度优先读 Redis Hash，毫秒级可达）")
    @GetMapping("/{taskId}")
    public Result<Task> getById(@PathVariable Long taskId) {
        long userId = StpUtil.getLoginIdAsLong();
        Task task = taskService.getTaskById(taskId, userId);
        // 用 Hash 缓存覆盖 progress/message，前端可秒级感知进度变化
        ProgressSnapshot snapshot = taskQueueService.getProgress(taskId);
        if (snapshot != null && snapshot.getProgress() > task.getProgress()) {
            task.setProgress(snapshot.getProgress());
            if (snapshot.getMessage() != null && !snapshot.getMessage().isEmpty()) {
                task.setMessage(snapshot.getMessage());
            }
        }
        return Result.ok(task);
    }

    @Operation(summary = "获取任务实时进度快照（Redis Hash 直读）")
    @GetMapping("/{taskId}/progress")
    public Result<ProgressSnapshot> getProgress(@PathVariable Long taskId) {
        long userId = StpUtil.getLoginIdAsLong();
        // 校验归属
        taskService.getTaskById(taskId, userId);
        return Result.ok(taskQueueService.getProgress(taskId));
    }

    @Operation(summary = "取消任务")
    @PostMapping("/{taskId}/cancel")
    public Result<Void> cancel(@PathVariable Long taskId) {
        long userId = StpUtil.getLoginIdAsLong();
        // 先校验归属
        taskService.getTaskById(taskId, userId);
        boolean success = taskService.requestCancel(taskId);
        if (!success) {
            return Result.fail(61002, "任务无法取消（可能已完成或已取消）");
        }
        return Result.ok();
    }

    @Operation(summary = "删除任务")
    @DeleteMapping("/{taskId}")
    public Result<Void> delete(@PathVariable Long taskId) {
        long userId = StpUtil.getLoginIdAsLong();
        taskService.deleteTask(taskId, userId);
        return Result.ok();
    }
}
