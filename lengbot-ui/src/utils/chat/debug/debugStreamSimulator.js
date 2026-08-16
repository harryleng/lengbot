import { buildPreviewMessage } from './debugMessageBuilder'
import { normalizeDebugUiState } from './debugUiState'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/**
 * 流式模拟：逐步追加 content / 事件，每次回调 parseMessage 后的预览对象
 * @param {object} apiMessage 完整目标消息
 * @param {object} uiState
 * @param {(msg: object) => void} onUpdate
 * @param {{ chunkSize?: number, chunkDelayMs?: number, eventDelayMs?: number, signal?: AbortSignal }} [options]
 */
export async function runDebugStreamSimulation(apiMessage, uiState, onUpdate, options = {}) {
  const { chunkSize = 8, chunkDelayMs = 45, eventDelayMs = 280, signal } = options

  const ui = normalizeDebugUiState({ ...uiState, streaming: true, toolsDone: false, reasoningDone: false })
  const fullContent = apiMessage.content || ''
  const metadata = JSON.parse(JSON.stringify(apiMessage.metadata || {}))
  const toolEvents = metadata.toolEvents || []
  const workflowEvents = metadata.workflowEvents || []
  const reasoningContent = metadata.reasoningContent || ''

  let partialContent = ''
  let partialReasoning = ''
  let appliedToolCount = 0
  let appliedWorkflowCount = 0

  const emit = (extraUi = {}) => {
    onUpdate(
      buildPreviewMessage(
        {
          role: apiMessage.role || 'assistant',
          content: partialContent,
          metadata: {
            ...metadata,
            reasoningContent: partialReasoning,
            toolEvents: toolEvents.slice(0, appliedToolCount),
            workflowEvents: workflowEvents.slice(0, appliedWorkflowCount),
          },
        },
        { ...ui, ...extraUi }
      )
    )
  }

  emit()

  if (reasoningContent) {
    for (let i = 0; i < reasoningContent.length; i += chunkSize) {
      if (signal?.aborted) return
      partialReasoning = reasoningContent.slice(0, i + chunkSize)
      emit({ reasoningDone: i + chunkSize >= reasoningContent.length ? false : false })
      await sleep(chunkDelayMs)
    }
    emit({ reasoningDone: true })
  }

  while (appliedToolCount < toolEvents.length) {
    if (signal?.aborted) return
    appliedToolCount++
    emit()
    await sleep(eventDelayMs)
  }

  while (appliedWorkflowCount < workflowEvents.length) {
    if (signal?.aborted) return
    const ev = workflowEvents[appliedWorkflowCount]
    appliedWorkflowCount++
    if (ev?.type === 'workflow_llm_chunk') {
      partialContent += ev.content || ''
    }
    emit()
    await sleep(eventDelayMs)
  }

  for (let i = 0; i < fullContent.length; i += chunkSize) {
    if (signal?.aborted) return
    partialContent = fullContent.slice(0, i + chunkSize)
    emit()
    await sleep(chunkDelayMs)
  }

  onUpdate(buildPreviewMessage(apiMessage, normalizeDebugUiState({ ...uiState, streaming: false })))
}
