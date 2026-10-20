import request from '../utils/request'
import { streamFetch } from '../utils/sseFetch'

export function chat(data) {
  return request.post('/chat', data)
}

/**
 * 带自动重连的流式对话
 * @param {Object} data - 对话请求数据
 * @param {Object} callbacks - 回调函数 { onChunk, onStatus, onMetadata, onToolEvent, onRequestId, onDone }
 * @param {AbortSignal} signal - 取消信号
 * @param {Object} options - 配置项 { maxRetries, retryDelay, onReconnecting }
 */
export async function chatStream(
  data,
  { onChunk, onStatus, onMetadata, onToolEvent, onRequestId, onDone, onEventId },
  signal,
  options = {}
) {
  const { maxRetries = 3, retryDelay = 2000, onReconnecting } = options
  // SSE 场景必须直读 localStorage：fetch 早于 Pinia store 水合，从 store 取 token 可能为 null
  const token = localStorage.getItem('token')
  let retries = 0
  // 1.2 断线重连：追踪请求状态
  let currentRequestId = null
  let lastEventId = null
  let receivedDone = false

  // 包装回调：同步追踪 requestId / eventId / done 状态
  const trackingCallbacks = {
    onChunk,
    onStatus,
    onMetadata,
    onToolEvent,
    onRequestId: (rid) => {
      currentRequestId = rid
      onRequestId?.(rid)
    },
    onDone: (meta) => {
      receivedDone = true
      onDone?.(meta)
    },
    onEventId: (id) => {
      lastEventId = id
      onEventId?.(id)
    },
  }

  async function attempt() {
    let doneFired = false
    const fireDone = (meta) => {
      if (!doneFired) {
        doneFired = true
        trackingCallbacks.onDone?.(meta)
      }
    }

    // demo 端点已下线（Phase 3 切流 harness 为默认，主链路 /api/chat/stream 即走 HarnessAgent）

    try {
      await streamFetch('/api/chat/stream', {
        method: 'POST',
        token,
        body: data,
        signal,
        onLines: (text) => {
          processSseLines(text, { ...trackingCallbacks, onDone: fireDone })
        },
      })
      fireDone()
    } catch (err) {
      // 用户主动停止：不触发 onDone，由 Chat.vue 统一处理中断收尾
      if (err.name === 'AbortError') {
        throw err
      }
      throw err
    }
  }

  while (retries <= maxRetries) {
    try {
      await attempt()
      return
    } catch (err) {
      if (err.name === 'AbortError') throw err
      retries++
      if (retries > maxRetries || signal?.aborted) {
        throw err
      }

      // 1.2 断线重连：如果有 requestId 且未收到 [DONE]，先尝试从缓冲恢复
      if (currentRequestId && !receivedDone) {
        onReconnecting?.()
        try {
          const reconnected = await tryReconnect(currentRequestId, lastEventId, trackingCallbacks, token)
          if (reconnected) return
        } catch {
          // 重连失败，退避后全量重启
        }
      }

      const delay = retryDelay * Math.pow(2, retries - 1)
      await new Promise((resolve) => setTimeout(resolve, delay))
    }
  }
}

/**
 * 1.2 断线重连：从服务端事件缓冲恢复
 * @returns {boolean} 是否恢复成功
 */
async function tryReconnect(requestId, lastEventId, callbacks, token) {
  const response = await fetch('/api/chat/reconnect', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: token || '',
    },
    body: JSON.stringify({ requestId, lastEventId: lastEventId ? Number(lastEventId) : null }),
  })

  if (!response.ok) return false

  const result = await response.json()
  if (result.code !== 200) return false

  const { status, events } = result.data || {}

  if (status === 'already_delivered') {
    // 前端已收到全部事件，只需触发 onDone
    callbacks.onDone?.()
    return true
  }

  if (status === 'not_found' || status === 'cancelled') return false

  // 重放缓存事件
  if (events && events.length > 0) {
    for (const event of events) {
      processSseLines(`data:${event.data}\n`, callbacks)
    }
  }

  if (status === 'completed') {
    return true
  }

  return false
}

/**
 * 页面刷新/重进后的断点恢复：重放服务端缓冲的 SSE 事件，重建本轮助手消息。
 * <p>与 {@link tryReconnect}（服务于同页面内网络掉线）不同：本函数对 <b>cancelled</b>
 * （服务端在客户端断开时已取消本轮）也重放已缓冲片段——此时没有"实时续流"可接，
 * 重放是拿回已生成内容的唯一途径。调用方需根据返回的 completed 标志决定如何收尾
 * （cancelled 无 [DONE]，需自行标记中断）。</p>
 *
 * @returns {Promise<{replayed: boolean, completed: boolean}>} replayed=是否拿到缓冲事件；completed=缓冲是否以 [DONE] 结束
 */
export async function replayStreamOnReload(requestId, lastEventId, callbacks, token) {
  let response
  try {
    response = await fetch('/api/chat/reconnect', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: token || '',
      },
      body: JSON.stringify({ requestId, lastEventId: lastEventId ? Number(lastEventId) : null }),
    })
  } catch {
    return { replayed: false, completed: false }
  }
  if (!response.ok) return { replayed: false, completed: false }
  const result = await response.json()
  if (result.code !== 200) return { replayed: false, completed: false }

  const { status, events } = result.data || {}
  // 缓冲已失效（服务端重启 / 超过 5min TTL / requestId 不存在）：无内容可恢复
  if (status === 'not_found') return { replayed: false, completed: false }
  // 前端已收到全部事件：仅需触发 onDone 收尾
  if (status === 'already_delivered') {
    callbacks.onDone?.()
    return { replayed: true, completed: true }
  }
  // completed / cancelled：重放缓冲事件（cancelled 不含 [DONE]，completed 含）
  if (events && events.length > 0) {
    for (const event of events) {
      processSseLines(`data:${event.data}\n`, callbacks)
    }
  }
  return { replayed: true, completed: status === 'completed' }
}

/**
 * 切回会话时恢复仍在运行的流式轮次（续流）：
 * 1) 先 POST /api/chat/reconnect 询问后端状态；
 * 2) 若 status=running：订阅 /api/chat/stream/reconnect 实时续流（sink 重放缓冲 + 继续推送），
 *    调用方需在再次切走时通过 signal 中止该续流，后端 run 不会因此中断；
 * 3) 其它状态（completed/cancelled/already_delivered/not_found）退化为 replayStreamOnReload 行为。
 *
 * @returns {Promise<{replayed:boolean, completed:boolean, live:boolean, aborted:boolean}>}
 *   aborted=true 表示续流被 signal 中止（用户再次切走），后端 run 仍在跑，交给下次 recover 续接。
 */
export async function resumeStreamOnReturn(requestId, lastEventId, callbacks, token, signal) {
  let pre = null
  try {
    const response = await fetch('/api/chat/reconnect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: token || '' },
      body: JSON.stringify({ requestId, lastEventId: lastEventId ? Number(lastEventId) : null }),
    })
    if (!response.ok) return { replayed: false, completed: false, live: false, aborted: false }
    const result = await response.json()
    if (result.code !== 200) return { replayed: false, completed: false, live: false, aborted: false }
    pre = result.data || {}
  } catch {
    return { replayed: false, completed: false, live: false, aborted: false }
  }

  const { status, events } = pre || {}

  if (status === 'not_found') return { replayed: false, completed: false, live: false, aborted: false }
  if (status === 'already_delivered') {
    callbacks.onDone?.()
    return { replayed: true, completed: true, live: false, aborted: false }
  }

  // 重放断连前已缓冲的事件（completed/cancelled 场景；running 场景后端不返回 events）
  if (events && events.length > 0) {
    for (const event of events) {
      processSseLines(`data:${event.data}\n`, callbacks)
    }
  }

  if (status === 'completed') return { replayed: true, completed: true, live: false, aborted: false }
  if (status === 'cancelled') return { replayed: true, completed: false, live: false, aborted: false }

  if (status === 'running') {
    try {
      await streamFetch('/api/chat/stream/reconnect', {
        method: 'POST',
        token,
        body: { requestId },
        signal,
        onLines: (text) => processSseLines(text, callbacks),
      })
    } catch (err) {
      if (err && err.name === 'AbortError') {
        // 用户在续流期间再次切走：后端 run 仍在跑，续流被 signal 中止，交给下次 recover 续接
        return { replayed: true, completed: false, live: true, aborted: true }
      }
      // 续流异常：退化为已缓冲部分，交由上层标记中断
      return { replayed: true, completed: false, live: true, aborted: false }
    }
    // 续流正常结束（含 [DONE]）：callbacks.onDone 已在 processSseLines 内触发
    return { replayed: true, completed: true, live: true, aborted: false }
  }

  return { replayed: true, completed: false, live: false, aborted: false }
}


function decodeSseTextContent(raw) {
  if (!raw) return ''
  let content = raw
  if (content.startsWith('"') && content.endsWith('"')) {
    try {
      content = JSON.parse(content)
    } catch {
      // 保持原样
    }
  }
  return content.replace(/\\n/g, '\n').replace(/\\r/g, '\r').replace(/\\t/g, '\t')
}

export function processSseLines(text, { onChunk, onStatus, onMetadata, onToolEvent, onRequestId, onDone, onEventId }) {
  const lines = text.split('\n')
  let currentEventId = null
  for (const line of lines) {
    // SSE 注释行（以冒号开头）：客户端应忽略，用于心跳保活
    if (line.startsWith(':')) continue
    // 1.2 解析 SSE 事件 ID（用于断线重连）
    if (line.startsWith('id:')) {
      currentEventId = line.substring(3).trim()
      continue
    }
    if (line.startsWith('data:')) {
      let content = line.substring(5)
      if (content.startsWith('[DONE]')) {
        const jsonStr = content.substring(6).trim()
        if (jsonStr) {
          try {
            onDone?.(JSON.parse(jsonStr))
          } catch {
            onDone?.()
          }
        } else {
          onDone?.()
        }
        if (currentEventId) {
          onEventId?.(currentEventId)
          currentEventId = null
        }
        continue
      }
      if (content) {
        if (content.startsWith('[STATUS]')) {
          const statusContent = content.substring(8)
          let parsed = null
          try {
            parsed = JSON.parse(statusContent)
          } catch {
            // 不是 JSON，作为普通状态消息处理
          }
          if (parsed && typeof parsed === 'object' && parsed.type) {
            if (
              parsed.type === 'tool_call' ||
              parsed.type === 'tool_result' ||
              parsed.type === 'tool_status' ||
              parsed.type === 'tool_complete' ||
              parsed.type === 'reasoning_content' ||
              parsed.type === 'workflow_node_start' ||
              parsed.type === 'workflow_node_complete' ||
              parsed.type === 'workflow_node_retry' ||
              parsed.type === 'workflow_node_failure' ||
              parsed.type === 'workflow_complete' ||
              parsed.type === 'workflow_llm_chunk' ||
              parsed.type === 'workflow_confirm_required' ||
              parsed.type === 'workflow_suspended' ||
              parsed.type === 'sensitive_block' ||
              parsed.type === 'skill_active' ||
              parsed.type === 'subagent_call' ||
              parsed.type === 'subagent_result' ||
              parsed.type === 'subagent_token' ||
              parsed.type === 'subagent_tool_call' ||
              parsed.type === 'subagent_tool_result' ||
              parsed.type === 'subagent_error' ||
              parsed.type === 'subagent_error_retry' ||
              parsed.type === 'subagent_batch_start' ||
              parsed.type === 'subagent_task_start' ||
              parsed.type === 'subagent_task_done' ||
              parsed.type === 'subagent_batch_done' ||
              parsed.type === 'subagent_batch_update' ||
              parsed.type === 'todos_updated' ||
              parsed.type === 'error' ||
              parsed.type === 'error_retry'
            ) {
              onToolEvent?.(parsed)
            }
            // 结构化事件但 type 未识别：丢弃，避免把原始 JSON 当状态文本飘字展示
            if (currentEventId) {
              onEventId?.(currentEventId)
              currentEventId = null
            }
            continue
          }
          onStatus?.(statusContent)
        } else if (content.startsWith('[METADATA]')) {
          onMetadata?.(content.substring(10))
        } else if (content.startsWith('[REQUEST_ID]')) {
          onRequestId?.(content.substring(12))
        } else {
          onChunk?.(decodeSseTextContent(content))
        }
        if (currentEventId) {
          onEventId?.(currentEventId)
          currentEventId = null
        }
      }
    }
  }
}

export function getRagReferences(sessionId, agentId, question) {
  return request.get('/chat/rag-references', {
    params: { sessionId, agentId, question },
  })
}

/** 停止进行中的流式对话（服务端置中断标记并连带取消子任务） */
export function stopChatStream(requestId) {
  return request.post('/chat/stream/stop', null, { params: { requestId } })
}

export function submitMessageFeedback(messageId, data) {
  return request.post(`/chat/messages/${messageId}/feedback`, data)
}

export function getMessageFeedback(messageId) {
  return request.get(`/chat/messages/${messageId}/feedback`)
}

export function batchGetMessageFeedbacks(messageIds) {
  return request.post('/chat/messages/feedbacks/batch', messageIds)
}

export function listMessageFeedbacks(pageNum = 1, pageSize = 20, rating = '') {
  const params = { pageNum, pageSize }
  if (rating) params.rating = rating
  return request.get('/chat/feedbacks', { params })
}

export function getFeedbackStats() {
  return request.get('/chat/feedbacks/stats')
}

export function refreshChatAttachmentPreviews(attachments) {
  return request.post('/chat/attachments/refresh-preview', attachments || [])
}

export function uploadChatAttachment(agentId, sessionId, file, configVersion) {
  const formData = new FormData()
  formData.append('file', file)
  const params = { agentId }
  if (sessionId) params.sessionId = sessionId
  if (configVersion != null) params.configVersion = configVersion
  return request.post('/chat/attachments', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    params,
  })
}

/** 删除缓冲区中未发送的附件（同步清理 MinIO 原文件与文档解析产物） */
export function deleteChatAttachment(agentId, sessionId, attachment) {
  const params = { agentId }
  if (sessionId) params.sessionId = sessionId
  return request.delete('/chat/attachments', { params, data: attachment })
}
