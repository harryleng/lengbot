/**
 * SubAgent 事件关联工具（从 events 数组中提取 call 对应的步骤/结果）
 */

/** 同 offset 多次委派合并为一块时的分组 key */
export function getSubagentBlockKey(call) {
  if (!call) return 'subagent-block'
  if (call.batch_id || call.batchId) return `batch:${call.batch_id || call.batchId}`
  if (call.task_id || call.taskId) return `task:${call.task_id || call.taskId}`
  const name = call.subagentName || ''
  const offset = call.contentOffset
  return `${name}@${offset ?? 'top'}`
}

/**
 * 解析委派序号：优先 metadata 中的 delegationIndex，旧数据按同 block 内出现顺序回退。
 */
export function resolveDelegationIndex(call, fallbackIndex = null) {
  if (call?.delegationIndex != null) return Number(call.delegationIndex)
  if (fallbackIndex != null) return fallbackIndex
  return null
}

function matchSubagentScope(event, call, delegationIndex = null) {
  if (!event || !call) return false
  const callBatchId = call.batch_id || call.batchId
  const eventBatchId = event.batch_id || event.batchId
  if (callBatchId && eventBatchId && callBatchId !== eventBatchId) return false
  const callTaskId = call.task_id || call.taskId
  const eventTaskId = event.task_id || event.taskId
  if (callTaskId && eventTaskId && callTaskId !== eventTaskId) return false
  if (event.subagentName !== call.subagentName) return false
  const eventOffset = event.contentOffset
  const callOffset = call.contentOffset
  if (eventOffset != null && callOffset != null && Number(eventOffset) !== Number(callOffset)) {
    return false
  }
  const di = delegationIndex ?? resolveDelegationIndex(call)
  if (di != null && event.delegationIndex != null) {
    return Number(event.delegationIndex) === Number(di)
  }
  return eventOffset == callOffset
}

/** 旧 metadata：按 subagent_call 出现顺序切分同 block 事件 */
function buildLegacyBlockSegments(events, blockCalls) {
  if (!blockCalls?.length) return []
  const blockKey = getSubagentBlockKey(blockCalls[0])
  const segments = blockCalls.map(() => [])
  let attemptIdx = -1

  for (const e of events || []) {
    if (e.type === 'subagent_call' && getSubagentBlockKey(e) === blockKey) {
      attemptIdx++
      continue
    }
    if (attemptIdx < 0) continue
    if (!matchSubagentScope(e, blockCalls[0], null)) continue
    const bucket = Math.min(attemptIdx, segments.length - 1)
    segments[bucket].push(e)
  }
  return segments
}

function resolveLegacyAttemptIndex(call, blockCalls) {
  if (!blockCalls?.length) return 0
  const idx = blockCalls.findIndex((c) => c === call)
  return idx >= 0 ? idx : 0
}

function filterScopedEvents(events, call, delegationIndex = null, blockCalls = null) {
  const di = delegationIndex ?? resolveDelegationIndex(call)
  const hasExplicitIndex =
    di != null &&
    (call?.delegationIndex != null ||
      (events || []).some((e) => matchSubagentScope(e, call, null) && e.delegationIndex != null))
  if (hasExplicitIndex) {
    return (events || []).filter((e) => matchSubagentScope(e, call, di))
  }
  if (blockCalls && blockCalls.length > 1) {
    const segments = buildLegacyBlockSegments(events, blockCalls)
    const idx = resolveLegacyAttemptIndex(call, blockCalls)
    return segments[idx] || []
  }
  return (events || []).filter((e) => matchSubagentScope(e, call, di))
}

/** 同 block 内按出现顺序归一化 delegationIndex（兼容旧 metadata） */
export function normalizeSubagentCalls(calls) {
  const counters = new Map()
  return (calls || []).map((call) => {
    const key = getSubagentBlockKey(call)
    const idx = counters.get(key) ?? 0
    counters.set(key, idx + 1)
    return {
      call,
      delegationIndex: resolveDelegationIndex(call, idx),
    }
  })
}

export function collectSubagentSteps(events, call, delegationIndex = null, blockCalls = null) {
  if (!call || call.type !== 'subagent_call') return []
  return filterScopedEvents(events, call, delegationIndex, blockCalls).filter(
    (e) => e.type === 'subagent_tool_call' || e.type === 'subagent_tool_result' || e.type === 'subagent_token'
  )
}

/**
 * 将连续的 subagent_token 合并为一段流式文本，避免每个 token 独占一行
 */
export function groupSubagentSteps(steps) {
  const grouped = []
  let tokenBuffer = ''

  for (const step of steps || []) {
    if (step.type === 'subagent_token') {
      tokenBuffer += step.content || ''
      continue
    }
    if (tokenBuffer) {
      grouped.push({ type: 'subagent_token_stream', content: tokenBuffer })
      tokenBuffer = ''
    }
    grouped.push(step)
  }

  if (tokenBuffer) {
    grouped.push({ type: 'subagent_token_stream', content: tokenBuffer })
  }
  return grouped
}

export function findSubagentResultEvent(events, call, delegationIndex = null, blockCalls = null) {
  if (!call || call.type !== 'subagent_call') return null
  return filterScopedEvents(events, call, delegationIndex, blockCalls).find((e) => e.type === 'subagent_result') || null
}

export function findSubagentResult(events, call, delegationIndex = null) {
  const resultEvt = findSubagentResultEvent(events, call, delegationIndex)
  return resultEvt?.result || null
}

/** 从委派结果 JSON 中解析 reply 展示文本 */
export function parseSubagentReplyText(result) {
  if (!result) return ''
  if (typeof result === 'object' && result.reply != null) {
    return String(result.reply)
  }
  if (typeof result === 'object' && Array.isArray(result.results)) {
    return formatSubagentResultsText(result)
  }
  const raw = String(result)
  try {
    const obj = JSON.parse(raw)
    if (obj && typeof obj.reply === 'string') return obj.reply
    if (obj && Array.isArray(obj.results)) return formatSubagentResultsText(obj)
  } catch {
    // 非 JSON 直接展示
  }
  return raw
}

function formatSubagentResultsText(obj) {
  const lines = []
  if (obj.batch_id) lines.push(`批次：${obj.batch_id}`)
  if (obj.mode) lines.push(`模式：${obj.mode}`)
  if (obj.background) {
    lines.push('后台任务已提交，可通过任务查询工具获取最新结果。')
  }
  for (const item of obj.results || []) {
    const name = item.display_name || item.subagent_name || 'SubAgent'
    const status = item.status || 'unknown'
    const title = `### ${name}（${status}）`
    lines.push(title)
    if (item.task_id) lines.push(`任务ID：${item.task_id}`)
    if (item.reply) lines.push(String(item.reply))
    else if (item.error) lines.push(`错误：${item.error}`)
  }
  return lines.join('\n\n')
}

export function findSubagentResultReply(events, call, delegationIndex = null, blockCalls = null) {
  const evt = findSubagentResultEvent(events, call, delegationIndex, blockCalls)
  if (!evt) return ''
  if (evt.replyText) return evt.replyText
  return parseSubagentReplyText(evt.result)
}

export function findSubagentResultRawJson(events, call, delegationIndex = null, blockCalls = null) {
  const evt = findSubagentResultEvent(events, call, delegationIndex, blockCalls)
  if (!evt?.result) return ''
  try {
    const obj = JSON.parse(String(evt.result))
    if (obj && typeof obj === 'object') {
      const full = { ...obj }
      if (!full.reply && evt.replyText) {
        full.reply = evt.replyText
      }
      return JSON.stringify(full, null, 2)
    }
  } catch {
    // 非 JSON
  }
  return String(evt.result)
}

export function hasSubagentResultJson(events, call, delegationIndex = null, blockCalls = null) {
  const evt = findSubagentResultEvent(events, call, delegationIndex, blockCalls)
  if (!evt?.result) return false
  try {
    const obj = JSON.parse(String(evt.result))
    return obj != null && typeof obj === 'object'
  } catch {
    return false
  }
}

export function mergeSubagentModelOutput(events, call, delegationIndex = null, blockCalls = null) {
  const steps = groupSubagentSteps(collectSubagentSteps(events, call, delegationIndex, blockCalls))
  return steps
    .filter((s) => s.type === 'subagent_token_stream')
    .map((s) => s.content || '')
    .join('')
}

export function resolveSubagentModelOutput(events, call, delegationIndex = null, blockCalls = null) {
  const streamed = mergeSubagentModelOutput(events, call, delegationIndex, blockCalls)
  if (streamed?.trim()) return streamed
  return findSubagentResultReply(events, call, delegationIndex, blockCalls)
}

export function findSubagentError(events, call, delegationIndex = null, blockCalls = null) {
  if (!call || call.type !== 'subagent_call') return null
  return filterScopedEvents(events, call, delegationIndex, blockCalls).find((e) => e.type === 'subagent_error') || null
}

export function findSubagentErrorRetries(events, call, delegationIndex = null, blockCalls = null) {
  if (!call || call.type !== 'subagent_call') return []
  return filterScopedEvents(events, call, delegationIndex, blockCalls).filter((e) => e.type === 'subagent_error_retry')
}

export function findSubagentErrorRetry(events, call, delegationIndex = null, blockCalls = null) {
  const retries = findSubagentErrorRetries(events, call, delegationIndex, blockCalls)
  return retries.length ? retries[retries.length - 1] : null
}

/** 单次委派是否失败（含超时：subagent_error 与 subagent_result 可能同时存在） */
export function isSubagentAttemptFailed(events, call, delegationIndex = null, blockCalls = null) {
  return !!findSubagentError(events, call, delegationIndex, blockCalls)
}

/** 单次委派是否成功完成 */
export function isSubagentAttemptSuccessful(events, call, delegationIndex = null, blockCalls = null) {
  if (isSubagentAttemptFailed(events, call, delegationIndex, blockCalls)) return false
  const scoped = filterScopedEvents(events, call, delegationIndex, blockCalls)
  if (scoped.some((e) => e.type === 'subagent_result')) return true
  return !!resolveSubagentModelOutput(events, call, delegationIndex, blockCalls)?.trim()
}

/** 单次委派是否已结束（成功/失败/无流式进行中） */
export function isSubagentAttemptDone(events, call, delegationIndex = null, streaming = false, blockCalls = null) {
  const scoped = filterScopedEvents(events, call, delegationIndex, blockCalls)
  if (scoped.some((e) => e.type === 'subagent_result')) return true
  if (scoped.some((e) => e.type === 'subagent_error')) return true
  const terminal = [...scoped]
    .reverse()
    .find((e) => e.type === 'subagent_result' || e.type === 'subagent_error' || e.type === 'subagent_error_retry')
  if (!terminal) return !streaming
  if (terminal.type === 'subagent_result') return true
  if (terminal.type === 'subagent_error') return true
  if (terminal.type === 'subagent_error_retry') return false
  return !streaming
}

/** 同 offset 多块委派是否全部结束 */
export function isSubagentBlockDone(events, calls, streaming = false) {
  if (!calls?.length) return !streaming
  const normalized = normalizeSubagentCalls(calls)
  return normalized.every(({ call, delegationIndex }) =>
    isSubagentAttemptDone(events, call, delegationIndex, streaming, calls)
  )
}

/**
 * 构建单次委派的重试/状态时间线（历史与流式共用）
 */
export function buildSubagentAttemptTimeline(
  events,
  call,
  delegationIndex = null,
  streaming = false,
  blockCalls = null
) {
  const scoped = filterScopedEvents(events, call, delegationIndex, blockCalls)
  const timeline = []
  const error = scoped.find((e) => e.type === 'subagent_error')
  for (const evt of scoped) {
    if (evt.type === 'subagent_error_retry') {
      // 终态失败后不再保留重试记录，避免与失败 banner 重复展示
      if (error) continue
      timeline.push({
        kind: 'retry',
        attempt: evt.attempt,
        maxRetries: evt.maxRetries,
        message: evt.message,
        code: evt.code,
        key: `retry-${evt.attempt}-${evt.maxRetries}`,
      })
    }
  }
  const result = scoped.find((e) => e.type === 'subagent_result')
  const hasOutput = !!resolveSubagentModelOutput(events, call, delegationIndex, blockCalls)?.trim()

  // 超时/失败时 subagent_error 与 subagent_result 会同时存在，必须以 error 为准
  if (error) {
    timeline.push({
      kind: 'error',
      message: error.message,
      code: error.code,
      key: `error-${error.code || 'unknown'}`,
    })
  } else if (result || hasOutput) {
    timeline.push({ kind: 'success', message: '执行完成', key: 'success' })
  } else if (!timeline.length && streaming) {
    timeline.push({ kind: 'running', message: '执行中...', key: 'running' })
  }
  return timeline
}

export function mapSubagentToolsToStandardEvents(events, call, delegationIndex = null, blockCalls = null) {
  if (!call || call.type !== 'subagent_call') return []
  const steps = collectSubagentSteps(events, call, delegationIndex, blockCalls)
  const mapped = []
  for (const step of steps) {
    if (step.type === 'subagent_tool_call') {
      mapped.push({
        type: 'tool_call',
        toolName: step.toolName,
        displayName: step.toolDisplayName || step.toolName,
        args: step.args || '{}',
      })
    } else if (step.type === 'subagent_tool_result') {
      mapped.push({
        type: 'tool_result',
        toolName: step.toolName,
        displayName: step.toolDisplayName || step.toolName,
        result: step.result ?? step.content ?? '',
      })
    }
  }
  return mapped
}

export function formatSubagentErrorLabel(code) {
  if (code === 'CONNECT_TIMEOUT') return '连接超时'
  if (code === 'READ_TIMEOUT') return '响应超时'
  return null
}

/**
 * 从实时 SSE 事件构建并行 SubAgent 任务状态徽标（用于流式占位条）
 * @param {Array} events
 * @returns {Array<{ key: string, label: string, status: string }>}
 */
export function buildSubagentLiveStatusBadges(events) {
  const states = new Map()

  for (const event of events || []) {
    if (event?.type === 'subagent_batch_start') {
      for (const task of event.tasks || []) {
        states.set(task.task_id, {
          key: task.task_id,
          label:
            task.display_name || task.displayName || task.subagent_name || `任务 ${Number(task.task_index ?? 0) + 1}`,
          status: 'pending',
        })
      }
      continue
    }
    if (!event?.task_id) continue

    const state = states.get(event.task_id) || {
      key: event.task_id,
      label: event.display_name || event.displayName || event.subagentName || 'SubAgent',
      status: 'pending',
    }

    if (event.type === 'subagent_task_start' || event.type === 'subagent_token') {
      state.status = 'running'
    } else if (event.type === 'subagent_tool_call') {
      state.status = 'running'
      state.label = event.displayName || event.subagentName || state.label
    } else if (event.type === 'subagent_error') {
      state.status = 'failed'
    } else if (event.type === 'subagent_task_done') {
      state.status = event.status === 'failed' ? 'failed' : 'completed'
    }

    if (event.display_name || event.displayName) {
      state.label = event.display_name || event.displayName
    }
    states.set(event.task_id, state)
  }

  const badges = [...states.values()]
  if (badges.length <= 1) return []
  return badges
}
