import { reactive } from 'vue'

// 全局单例：跟踪每个会话是否正在流式输出，供「最近对话」列表显示转圈图标。
// 设计要点：run 在后端已与连接解耦——导航切走 / 刷新时后端 run 仍在跑，
// 此处保持标记，列表持续转圈；仅当流正常完成、用户主动停止、真实错误或已完成（切回时）才清除。
const state = reactive({
  // key = String(sessionId) -> { requestId, status: 'streaming' | 'reconnecting', startedAt }
  map: {},
})

export function markSessionStreaming(sessionId, requestId) {
  if (sessionId == null) return
  const key = String(sessionId)
  state.map[key] = { requestId: requestId ?? null, status: 'streaming', startedAt: Date.now() }
}

export function markSessionReconnecting(sessionId, requestId) {
  if (sessionId == null) return
  const key = String(sessionId)
  const prev = state.map[key] || {}
  state.map[key] = {
    requestId: requestId ?? prev.requestId ?? null,
    status: 'reconnecting',
    startedAt: prev.startedAt || Date.now(),
  }
}

export function clearSessionStreaming(sessionId) {
  if (sessionId == null) return
  delete state.map[String(sessionId)]
}

export function isSessionStreaming(sessionId) {
  if (sessionId == null) return false
  return !!state.map[String(sessionId)]
}

export function sessionStreamStatus(sessionId) {
  if (sessionId == null) return null
  return state.map[String(sessionId)]?.status || null
}
