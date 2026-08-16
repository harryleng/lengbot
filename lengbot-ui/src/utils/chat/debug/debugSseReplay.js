/**
 * 解析 Chat SSE 日志并应用到 mock 消息（纯前端，不走网络）
 */

const STATUS_PREFIX = '[STATUS]'
const METADATA_PREFIX = '[METADATA]'

const TOOL_EVENT_TYPES = new Set([
  'tool_call',
  'tool_result',
  'tool_status',
  'tool_complete',
  'skill_active',
  'subagent_call',
  'subagent_result',
  'subagent_token',
  'subagent_tool_call',
  'subagent_tool_result',
  'subagent_error',
  'subagent_error_retry',
  'subagent_batch_start',
  'subagent_task_start',
  'subagent_task_done',
  'subagent_batch_done',
  'subagent_batch_update',
])

const WORKFLOW_EVENT_TYPES = new Set([
  'workflow_node_start',
  'workflow_node_complete',
  'workflow_node_retry',
  'workflow_node_failure',
  'workflow_complete',
  'workflow_llm_chunk',
  'workflow_confirm_required',
  'workflow_suspended',
])

/**
 * 从 SSE 原始文本解析事件列表
 * @param {string} raw
 * @returns {{ chunks: string[], statusEvents: object[], metadata: object|null, entries: object[] }}
 */
export function parseSseDebugLog(raw) {
  const chunks = []
  const statusEvents = []
  const entries = []
  let metadata = null

  for (const line of (raw || '').split('\n')) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith(':') || trimmed.startsWith('id:')) continue
    if (!trimmed.startsWith('data:')) continue

    let payload = trimmed.slice(5).trim()
    if (!payload || payload === '[DONE]') continue

    if (payload.startsWith(STATUS_PREFIX)) {
      try {
        const event = JSON.parse(payload.slice(STATUS_PREFIX.length))
        statusEvents.push(event)
        entries.push({ kind: 'status', event })
      } catch {
        // ignore
      }
      continue
    }

    if (payload.startsWith(METADATA_PREFIX)) {
      try {
        metadata = JSON.parse(payload.slice(METADATA_PREFIX.length))
        entries.push({ kind: 'metadata', metadata })
      } catch {
        // ignore
      }
      continue
    }

    const content = payload.replace(/\\n/g, '\n')
    chunks.push(content)
    entries.push({ kind: 'chunk', content })
  }

  return { chunks, statusEvents, metadata, entries }
}

/**
 * 将 SSE 解析结果合并为 API 形态消息
 * @param {object} [baseMessage]
 * @param {{ chunks: string[], statusEvents: object[], metadata: object|null }} parsed
 */
export function buildApiMessageFromSse(baseMessage = {}, parsed) {
  const msg = JSON.parse(JSON.stringify(baseMessage))
  if (!msg.metadata || typeof msg.metadata !== 'object') {
    msg.metadata = {}
  }
  if (!Array.isArray(msg.metadata.toolEvents)) msg.metadata.toolEvents = []
  if (!Array.isArray(msg.metadata.workflowEvents)) msg.metadata.workflowEvents = []

  let content = msg.content || ''
  let reasoningContent = msg.metadata.reasoningContent || ''

  for (const event of parsed.statusEvents || []) {
    if (!event?.type) continue
    if (event.type === 'reasoning_content') {
      reasoningContent += event.content || ''
      continue
    }
    if (event.type === 'workflow_llm_chunk') {
      content += event.content || ''
      continue
    }
    if (WORKFLOW_EVENT_TYPES.has(event.type)) {
      msg.metadata.workflowEvents.push({ ...event })
      continue
    }
    if (TOOL_EVENT_TYPES.has(event.type)) {
      msg.metadata.toolEvents.push({ ...event })
    }
  }

  if (parsed.chunks?.length) {
    content += parsed.chunks.join('')
  }

  if (parsed.metadata) {
    msg.metadata = {
      ...msg.metadata,
      ...parsed.metadata,
      toolEvents: parsed.metadata.toolEvents ?? msg.metadata.toolEvents,
      workflowEvents: parsed.metadata.workflowEvents ?? msg.metadata.workflowEvents,
    }
  }

  msg.content = content
  msg.metadata.reasoningContent = reasoningContent
  return msg
}
