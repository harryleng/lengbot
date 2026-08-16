import { parseMessage } from '@/composables/chat/useChatMessageModel'
import { normalizeDebugUiState } from './debugUiState'

const DEBUG_MSG_ID = 'debug-msg-preview'

/** 默认 assistant 消息（API 形态，供 JSON 编辑器使用） */
export function createDefaultApiMessage() {
  return {
    role: 'assistant',
    content: '你好，这是 Debug Lab 预览消息。\n\n支持 **Markdown** 渲染测试。',
    metadata: {
      toolEvents: [],
      workflowEvents: [],
      reasoningContent: '',
      ragReferences: [],
      requestId: 'debug-request-id',
    },
  }
}

/** API 形态消息 → 编辑器 JSON 字符串 */
export function apiMessageToEditorJson(apiMessage) {
  return JSON.stringify(apiMessage, null, 2)
}

/** 编辑器 JSON → API 形态消息 */
export function editorJsonToApiMessage(jsonText) {
  const parsed = JSON.parse(jsonText)
  if (!parsed || typeof parsed !== 'object') {
    throw new Error('消息必须是 JSON 对象')
  }
  if (!parsed.metadata || typeof parsed.metadata !== 'object') {
    parsed.metadata = {}
  }
  return parsed
}

/**
 * API 形态消息 → parseMessage 后的预览对象（供 ChatMessageRow 使用）
 * @param {object} apiMessage
 * @param {{ streaming?: boolean, reasoningExpanded?: boolean }} uiState
 */
export function buildPreviewMessage(apiMessage, uiState = {}) {
  const ui = normalizeDebugUiState(uiState)
  const msg = parseMessage({
    id: DEBUG_MSG_ID,
    role: apiMessage.role || 'assistant',
    content: apiMessage.content ?? '',
    metadata: apiMessage.metadata ?? {},
    createTime: new Date().toISOString(),
  })

  msg._streaming = ui.streaming
  msg._reasoningExpanded = ui.reasoningExpanded
  msg._reasoningDone = ui.streaming ? ui.reasoningDone : (ui.reasoningDone ?? true)
  msg._toolsDone = ui.streaming ? ui.toolsDone : (ui.toolsDone ?? true)
  msg._toolExpanded = ui.toolExpanded
  return msg
}

/** 从 Chat 消息对象提取可导入的 API 形态 */
export function extractApiMessageFromChatMsg(msg) {
  let metadata = msg.metadata
  if (typeof metadata === 'string') {
    try {
      metadata = JSON.parse(metadata)
    } catch {
      metadata = {}
    }
  }
  if (!metadata || typeof metadata !== 'object') {
    metadata = {}
  }
  return {
    role: msg.role || 'assistant',
    content: msg.content ?? '',
    metadata: {
      toolEvents: metadata.toolEvents ?? msg._toolEvents ?? [],
      workflowEvents: metadata.workflowEvents ?? msg._workflowEvents ?? [],
      reasoningContent: metadata.reasoningContent ?? msg._reasoningContent ?? '',
      ragReferences: metadata.ragReferences ?? [],
      requestId: metadata.requestId ?? msg._requestId ?? '',
      error: metadata.error,
      workflowError: metadata.workflowError,
    },
  }
}
