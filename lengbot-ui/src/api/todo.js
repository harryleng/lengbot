import request from '../utils/request'

/**
 * 会话待办 API（只读查询）。
 * 事实源为 message.metadata.toolEvents 中的 write_todos 结果，前端状态栏实时层由 SSE todos_updated 提供。
 */

/** 按请求拉取 todos（流式期间轮询兜底，对应后端 GET /sessions/{sessionId}/todos） */
export function getSessionTodosByRequest(sessionId, parentRequestId) {
  return request.get(`/sessions/${sessionId}/todos`, { params: { parentRequestId } })
}

/** 拉取会话最新 todos（重进/刷新时调用，对应后端 GET /sessions/{sessionId}/todos/latest） */
export function getLatestSessionTodos(sessionId) {
  return request.get(`/sessions/${sessionId}/todos/latest`)
}
