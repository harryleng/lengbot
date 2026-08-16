import { processSseLines } from './chat'
import { streamFetch } from '../utils/sseFetch'

const WORKFLOW_SSE_EVENT_TYPES = new Set([
  'workflow_node_start',
  'workflow_node_complete',
  'workflow_node_retry',
  'workflow_node_failure',
  'workflow_complete',
  'workflow_llm_chunk',
  'workflow_confirm_required',
  'workflow_suspended',
  'error',
])

/**
 * 读取工作流调试 SSE 流（格式与 Chat 一致：[STATUS] + JSON、[DONE] + 最终结果）
 *
 * @param {string} url 请求地址
 * @param {Object} payload 请求体
 * @param {{ onEvent?: Function, onDone?: Function, signal?: AbortSignal }} callbacks
 */
export async function readWorkflowTestSseStream(url, payload, { onEvent, onDone, signal } = {}) {
  // SSE 场景必须直读 localStorage：fetch 早于 Pinia store 水合，从 store 取 token 可能为 null
  const token = localStorage.getItem('token')
  let doneFired = false

  const fireDone = (meta) => {
    if (!doneFired) {
      doneFired = true
      onDone?.(meta)
    }
  }

  const handleToolEvent = (parsed) => {
    if (!WORKFLOW_SSE_EVENT_TYPES.has(parsed?.type)) return
    if (parsed.type === 'error') {
      onEvent?.(parsed)
      throw new Error(parsed.message || '执行失败')
    }
    onEvent?.(parsed)
  }

  await streamFetch(url, {
    method: 'POST',
    token,
    body: payload,
    signal,
    onLines: (text) => {
      processSseLines(text, {
        onToolEvent: handleToolEvent,
        onDone: fireDone,
      })
    },
  })
}

export function testWorkflowStream(agentId, payload, callbacks) {
  return readWorkflowTestSseStream(`/api/agents/${agentId}/workflow/test/stream`, payload, callbacks)
}

export function resumeWorkflowStream(agentId, payload, callbacks) {
  return readWorkflowTestSseStream(`/api/agents/${agentId}/workflow/resume/stream`, payload, callbacks)
}
