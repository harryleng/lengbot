import request from '../utils/request'
import { streamFetch } from '../utils/sseFetch'

export function getPrompts(params) {
  return request.get('/prompts', { params })
}

export function getPrompt(id) {
  return request.get(`/prompts/${id}`)
}

export function createPrompt(data) {
  return request.post('/prompts', data)
}

export function updatePrompt(id, data) {
  return request.put('/prompts', data, { params: { id } })
}

export function deletePrompt(id) {
  return request.delete(`/prompts/${id}`)
}

export function getPromptVersions(promptKey) {
  return request.get(`/prompts/${promptKey}/versions`)
}

export function createPromptVersion(data) {
  return request.post('/prompts/versions', data)
}

export function getPromptVersionDetail(promptKey, version) {
  return request.get('/prompts/versions/detail', { params: { promptKey, version } })
}

export function getPromptTemplates() {
  return request.get('/prompts/templates')
}

export function getPromptTemplate(key) {
  return request.get(`/prompts/templates/${key}`)
}

// 创建模板
export function createPromptTemplate(data) {
  return request.post('/prompts/templates', data)
}

// 更新模板
export function updatePromptTemplate(id, data) {
  return request.put('/prompts/templates', data, { params: { id } })
}

// 删除模板
export function deletePromptTemplate(id) {
  return request.delete(`/prompts/templates/${id}`)
}

/**
 * 流式运行Prompt调试（SSE，带重试）
 */
export async function runPromptStream(data, { onChunk, onDone, onError }, signal, options = {}) {
  const { maxRetries = 3, retryDelay = 2000 } = options
  const token = localStorage.getItem('token')
  const ERROR_PREFIX = '[PROMPT_ERROR]'
  let retries = 0

  async function attempt() {
    await streamFetch('/api/prompts/run', {
      method: 'POST',
      token,
      body: data,
      signal,
      onLines: (text) => {
        for (const line of text.split('\n')) {
          if (line.startsWith('data:')) {
            const content = line.substring(5).trimStart()
            if (!content) continue
            // 后端下发的业务错误事件：转为不可重试错误，携带友好提示
            if (content.startsWith(ERROR_PREFIX)) {
              const bizErr = new Error(content.substring(ERROR_PREFIX.length))
              bizErr.name = 'PromptBizError'
              throw bizErr
            }
            onChunk?.(content)
          }
        }
      },
    })
  }

  while (retries <= maxRetries) {
    try {
      await attempt()
      onDone?.()
      return
    } catch (err) {
      if (err.name === 'AbortError') return
      // 业务错误（如 API Key 无效）无需重试，直接回调
      if (err.name === 'PromptBizError') {
        onError?.(err.message || '模型调用失败')
        return
      }
      retries++
      if (retries > maxRetries || signal?.aborted) {
        onError?.(err.message || '流式请求失败')
        return
      }
      const delay = retryDelay * Math.pow(2, retries - 1)
      await new Promise((r) => setTimeout(r, delay))
    }
  }
}
