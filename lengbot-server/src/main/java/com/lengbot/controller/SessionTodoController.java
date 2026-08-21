package com.lengbot.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lengbot.common.Result;
import com.lengbot.service.ChatSessionService;
import com.lengbot.service.SessionTodoService;
import com.lengbot.vo.TodoItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话待办查询接口（只读）。
 *
 * @author lw
 * @since 2026-07-17
 */
@Tag(name = "会话待办", description = "AI 写入的待办列表查询")
@RestController
@RequestMapping("/api/sessions/{sessionId}/todos")
@RequiredArgsConstructor
public class SessionTodoController {

    private final SessionTodoService sessionTodoService;
    private final ChatSessionService chatSessionService;

    @Operation(summary = "按请求获取待办（流式期间轮询兜底）")
    @GetMapping
    public Result<List<TodoItemVO>> listByRequest(
            @PathVariable Long sessionId,
            @RequestParam String parentRequestId) {
        chatSessionService.ensureOwnedByUser(sessionId, StpUtil.getLoginIdAsLong());
        return Result.ok(sessionTodoService.listByRequest(sessionId, parentRequestId));
    }

    @Operation(summary = "获取会话最新待办（重进/刷新时调用）")
    @GetMapping("/latest")
    public Result<List<TodoItemVO>> latest(@PathVariable Long sessionId) {
        chatSessionService.ensureOwnedByUser(sessionId, StpUtil.getLoginIdAsLong());
        return Result.ok(sessionTodoService.listLatestBySession(sessionId));
    }
}
