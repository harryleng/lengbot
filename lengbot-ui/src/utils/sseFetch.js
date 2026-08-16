/**
 * 通用 SSE（Server-Sent Events）流式请求工具
 * 基于 fetch API，支持 Authorization Header 传递 token
 *
 * @param {string} url - 请求地址
 * @param {Object} options
 * @param {string} options.token - 认证 token
 * @param {string} [options.method='GET'] - HTTP 方法
 * @param {Object} [options.body] - 请求体（POST 时自动 JSON.stringify）
 * @param {function} options.onEvent - 收到事件回调 ({ event, data })
 * @param {function} [options.onDone] - 流结束回调
 * @param {function} [options.onError] - 错误回调
 * @param {AbortSignal} [options.signal] - 取消信号
 * @param {number} [options.maxRetries=0] - 最大重试次数（0 表示不重试）
 * @param {number} [options.retryDelay=2000] - 重试基础延迟（ms），指数退避
 * @returns {{ close: () => void }} 控制句柄
 */
export function sseFetch(
  url,
  { token, method = 'GET', body, onEvent, onDone, onError, signal, maxRetries = 0, retryDelay = 2000 }
) {
  let aborted = false
  let retries = 0
  const internalController = new AbortController()

  // 外部 signal 中止时同步中止内部 controller
  if (signal) {
    if (signal.aborted) {
      internalController.abort(signal.reason)
    } else {
      signal.addEventListener('abort', () => internalController.abort(signal.reason), { once: true })
    }
  }

  const effectiveSignal = internalController.signal

  async function attempt() {
    await streamFetch(url, {
      token,
      method,
      body,
      signal: effectiveSignal,
      onLines: (text) => {
        for (const evt of parseSseLines(text)) {
          onEvent?.(evt)
        }
      },
    })
  }

  async function run() {
    while (!aborted) {
      try {
        await attempt()
        onDone?.()
        return
      } catch (err) {
        if (err.name === 'AbortError' || effectiveSignal.aborted) return
        retries++
        if (retries > maxRetries) {
          onError?.(err)
          return
        }
        const delay = retryDelay * Math.pow(2, retries - 1)
        await new Promise((r) => setTimeout(r, delay))
      }
    }
  }

  run()

  return {
    close() {
      aborted = true
      internalController.abort()
    },
  }
}

/**
 * 底层 SSE 流式原语：fetch + reader + 行缓冲，按换行切段回调
 * <p>所有协议变体（标准 SSE / 自定义 [STATUS] / 简单 data:）共用此实现，
 * 各自只需提供 onLines 回调中的行解析逻辑</p>
 *
 * @param {string} url 请求地址
 * @param {Object} options
 * @param {string} [options.token] 认证 token
 * @param {string} [options.method='GET'] HTTP 方法
 * @param {Object} [options.body] 请求体（POST 时自动 JSON.stringify）
 * @param {AbortSignal} [options.signal] 取消信号
 * @param {function} [options.onLines] 收到一段完整行（按 \n 切）回调，参数为完整文本
 * @returns {Promise<void>} 流结束时 resolve
 */
export async function streamFetch(url, { token, method = 'GET', body, signal, onLines } = {}) {
  const headers = {}
  if (token) headers['Authorization'] = token
  if (body) headers['Content-Type'] = 'application/json'
  const fetchOptions = { method, headers, signal }
  if (body) fetchOptions.body = JSON.stringify(body)

  try {
    const response = await fetch(url, fetchOptions)
    if (!response.ok) {
      let msg = `流式请求失败: ${response.status}`
      try {
        const errBody = await response.json()
        if (errBody?.message) msg = errBody.message
      } catch {
        // 非 JSON 错误体（如 SSE 端点 401 吐的 data:[STATUS]），沿用 status 文案
      }
      const error = new Error(msg)
      error.status = response.status
      throw error
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        if (buffer.trim()) onLines?.(buffer)
        return
      }
      buffer += decoder.decode(value, { stream: true })
      const lastNewline = buffer.lastIndexOf('\n')
      if (lastNewline === -1) continue
      const complete = buffer.substring(0, lastNewline)
      buffer = buffer.substring(lastNewline + 1)
      onLines?.(complete)
    }
  } catch (err) {
    // 用户主动中止：fetch 阶段或 reader 阶段都可能抛 AbortError，统一静默
    if (err.name === 'AbortError') return
    throw err
  }
}

function parseSseLines(text) {
  const events = []
  let currentEvent = ''
  let currentData = ''
  for (const line of text.split('\n')) {
    if (line.startsWith('event:')) {
      currentEvent = line.substring(6).trim()
    } else if (line.startsWith('data:')) {
      const chunk = line.substring(5).trimStart()
      currentData += chunk
    } else if (line === '') {
      if (currentData) {
        events.push({ event: currentEvent || 'message', data: currentData })
      }
      currentEvent = ''
      currentData = ''
    }
  }
  return events
}
