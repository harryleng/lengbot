package com.lengbot.service;

import com.lengbot.vo.TodoItemVO;

import java.util.List;

/**
 * 会话待办服务：负责待办的读取。
 * <p>事实源仍是 message.metadata.toolEvents（write_todos 工具结果），不维护独立表。</p>
 *
 * @author finch
 * @since 2026-07-17
 */
public interface SessionTodoService {

    /**
     * 拉取指定请求（parentRequestId）的 todos 快照。
     *
     * @param sessionId       会话ID
     * @param parentRequestId 请求ID
     * @return todos 列表，无数据返回空列表
     */
    List<TodoItemVO> listByRequest(Long sessionId, String parentRequestId);

    /**
     * 拉取会话最新一次 write_todos 的 todos 快照（重进会话/刷新时使用）。
     *
     * @param sessionId 会话ID
     * @return todos 列表，无数据返回空列表
     */
    List<TodoItemVO> listLatestBySession(Long sessionId);
}
