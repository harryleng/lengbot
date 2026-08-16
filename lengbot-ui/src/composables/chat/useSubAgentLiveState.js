/**
 * SubAgent 实时状态增量维护：替代每事件 O(N) 重算的 computed
 *
 * 核心语义：
 * - 维护 Map<taskId, state> 增量状态表
 * - 监听 liveEvents.length 增长，用 RAF 批处理合并单帧内的多事件
 * - 每事件 O(1) 应用到对应 task 的 state 上
 * - 会话切换/组件卸载时清空
 */
import { ref, watch, onBeforeUnmount } from 'vue'

export function useSubAgentLiveState(liveEventsRef) {
  // taskId -> 状态对象（增量维护）
  const stateMap = ref(new Map())
  // 已处理到 liveEvents 的下标
  let lastIdx = -1
  // RAF 批处理缓冲
  let pending = []
  let scheduled = false

  function applyEvent(event) {
    if (!event) return
    const map = stateMap.value
    if (event.type === 'subagent_batch_start') {
      for (const task of event.tasks || []) {
        const key = String(task.task_id)
        if (!map.has(key)) {
          map.set(key, {
            task_id: task.task_id,
            batch_id: event.batch_id,
            subagent_name: task.subagent_name,
            display_name: task.display_name || task.displayName || task.subagent_name,
            task: task.task,
            status: 'pending',
            progress_summary: '等待调度',
            liveOutput: '',
            reply: '',
          })
        }
      }
      return
    }
    if (!event.task_id) return
    const key = String(event.task_id)
    const state = map.get(key) || {
      task_id: event.task_id,
      batch_id: event.batch_id,
      subagent_name: event.subagentName,
      display_name: event.display_name || event.displayName || event.subagentName,
      task: '',
      status: 'pending',
      progress_summary: '等待调度',
      liveOutput: '',
      reply: '',
    }
    if (event.type === 'subagent_task_start') {
      state.status = 'running'
      state.progress_summary = '正在执行'
    } else if (event.type === 'subagent_tool_call') {
      state.progress_summary = `正在调用 ${event.toolDisplayName || event.toolName || '工具'}`
    } else if (event.type === 'subagent_tool_result') {
      state.progress_summary = '工具执行完成，继续处理'
    } else if (event.type === 'subagent_token') {
      state.status = 'running'
      state.progress_summary = '正在生成输出'
      state.liveOutput = `${state.liveOutput || ''}${event.content || ''}`.slice(-8000)
    } else if (event.type === 'subagent_error') {
      state.status = 'failed'
      state.progress_summary = event.message || '任务执行异常'
      state.error = event.message || '任务执行异常'
    } else if (event.type === 'subagent_task_done') {
      state.status = event.status || 'completed'
      state.progress_summary = state.status === 'completed' ? '任务已完成' : '任务执行结束'
      const reply = event.result?.reply
      if (event.result?.error) state.error = event.result.error
      if (reply) state.reply = String(reply)
      if (!state.liveOutput && reply) state.liveOutput = String(reply)
    }
    if (event.display_name || event.displayName) {
      state.display_name = event.display_name || event.displayName
    }
    if (event.task) state.task = event.task
    if (event.status_label) state.status_label = event.status_label
    // 用新对象触发 Map 的响应式更新；state 已含最新字段
    map.set(key, { ...state })
  }

  function flush() {
    scheduled = false
    if (pending.length === 0) return
    const events = pending
    pending = []
    for (const event of events) {
      applyEvent(event)
    }
  }

  function reset() {
    lastIdx = -1
    pending = []
    stateMap.value = new Map()
  }

  watch(
    () => liveEventsRef.value?.length ?? 0,
    (total, prev) => {
      const arr = liveEventsRef.value
      if (!arr || arr.length === 0) {
        if (prev > 0) reset()
        return
      }
      // 长度收缩（数组重置）：重置后再处理
      if (total <= lastIdx) {
        reset()
      }
      for (let i = lastIdx + 1; i < total; i++) {
        pending.push(arr[i])
      }
      lastIdx = total - 1
      if (pending.length > 0 && !scheduled) {
        scheduled = true
        requestAnimationFrame(flush)
      }
    }
  )

  onBeforeUnmount(() => {
    pending = []
    stateMap.value = new Map()
  })

  return {
    stateMap,
    reset,
  }
}
