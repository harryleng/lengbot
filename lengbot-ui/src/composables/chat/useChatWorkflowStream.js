import { nextTick } from 'vue'
import { message } from 'ant-design-vue'
import {
  resolveWorkflowConfirmPending,
  resolveWorkflowFailureFromEvents,
  stripWorkflowErrorContent,
} from '../../components/workflow/workflowStepUtils.js'
import { resumeWorkflowStream } from '../../api/workflowTestStream.js'
import { abandonWorkflowConfirm as abandonWorkflowConfirmApi } from '../../api/workflow.js'
import {
  patchLocalConfirmEventsBeforeResume,
  patchLocalConfirmEventsOnAbandon,
  findSuspendNodeIdFromEvents,
} from '../../views/workflow/composables/useWorkflowTestLive.js'
import { getToolBlockOffsets } from './useChatEventPartition.js'

export const WORKFLOW_SSE_EVENT_TYPES = [
  'workflow_node_start',
  'workflow_node_complete',
  'workflow_node_retry',
  'workflow_node_failure',
  'workflow_complete',
  'workflow_confirm_required',
  'workflow_suspended',
  'workflow_llm_chunk',
]

/**
 * 工作流 Chat SSE 事件处理（从 Chat.vue 解耦）
 * @param {object} deps 运行时依赖，由 Chat 壳注入
 */
export function createChatWorkflowStreamHandlers(deps) {
  const {
    loading,
    streaming,
    currentStatus,
    hasStreamContent,
    streamSmoother,
    scrollToBottom,
    clearErrorRetry,
    getCurrentStreamingMsg,
    setCurrentStreamingMsg,
    getAbortController,
    setAbortController,
    getSelectedAgentId,
  } = deps

  /** 工作流 SSE 事件：更新节点轨迹、状态文案与正文流式输出 */
  function handleChatWorkflowStreamEvent(assistantMsg, event) {
    if (!assistantMsg || !event?.type) return false

    if (event.type === 'workflow_llm_chunk') {
      clearErrorRetry(assistantMsg)
      setCurrentStreamingMsg(assistantMsg)
      streamSmoother.push(event.content || '')
      hasStreamContent.value = true
      scrollToBottom()
      return true
    }

    if (!WORKFLOW_SSE_EVENT_TYPES.includes(event.type)) return false

    if (!assistantMsg._workflowEvents) assistantMsg._workflowEvents = []
    if (event.type !== 'workflow_confirm_required') {
      assistantMsg._workflowEvents.push(event)
    }
    hasStreamContent.value = true

    if (event.type === 'workflow_confirm_required') {
      assistantMsg._workflowConfirmPending = {
        runId: event.runId,
        confirmForm: event.confirmForm,
      }
      assistantMsg._workflowEvents.push(event)
      assistantMsg._streaming = false
      assistantMsg._toolsDone = true
      loading.value = false
      streaming.value = false
      const isAskUser = event.confirmForm?.hitlType === 'ask_user' || event.confirmForm?.toolName === 'ask_user'
      currentStatus.value = isAskUser ? '工作流等待您的回答' : '等待人工确认'
    } else if (event.type === 'workflow_suspended') {
      assistantMsg._streaming = false
      assistantMsg._toolsDone = true
      loading.value = false
      streaming.value = false
      if (!assistantMsg._workflowConfirmPending) {
        const pending = resolveWorkflowConfirmPending(assistantMsg._workflowEvents, null)
        if (pending) assistantMsg._workflowConfirmPending = pending
      }
      currentStatus.value = '工作流已暂停，等待确认'
      nextTick(() => scrollToBottom())
    } else if (event.type === 'workflow_node_start') {
      assistantMsg._streaming = true
      assistantMsg._workflowNodeRetry = null
      currentStatus.value = `正在执行: ${event.nodeLabel || event.nodeType || '节点'}`
    } else if (event.type === 'workflow_node_retry' || event.type === 'workflow_node_failure') {
      assistantMsg._streaming = event.type === 'workflow_node_retry'
      assistantMsg._workflowNodeRetry = {
        message: event.message || (event.type === 'workflow_node_retry' ? '节点重试中' : '节点执行失败'),
        reason: event.reason,
        attempt: event.attempt,
        maxAttempts: event.maxAttempts,
        kind: event.type === 'workflow_node_failure' ? 'failure' : 'retry',
        nodeLabel: event.nodeLabel,
      }
      currentStatus.value = event.message || currentStatus.value
    } else if (event.type === 'workflow_node_complete') {
      if (event.success === false) {
        assistantMsg._workflowNodeRetry = null
        assistantMsg._workflowError = {
          nodeId: event.nodeId,
          nodeLabel: event.nodeLabel || event.nodeType || '节点',
          nodeType: event.nodeType,
          message: event.userMessage || event.message || '节点执行失败',
          reason: event.failureReason,
          durationMs: event.durationMs,
        }
        assistantMsg.content = stripWorkflowErrorContent(assistantMsg.content)
      } else {
        assistantMsg._workflowNodeRetry = null
      }
      const label = event.nodeLabel || event.nodeType || '节点'
      const dur = event.durationMs != null ? ` (${event.durationMs}ms)` : ''
      currentStatus.value = event.success === false ? `${label} 执行失败` : `${label} 已完成${dur}`
    } else if (event.type === 'workflow_complete') {
      assistantMsg._workflowNodeRetry = null
      currentStatus.value = '工作流执行完成，正在整理回复…'
    }

    scrollToBottom()
    return true
  }

  function syncWorkflowResumeMetadata(msg, result) {
    if (!msg) return
    const base = typeof msg.metadata === 'object' && msg.metadata ? msg.metadata : {}
    if (result?.nodeEvents?.length) {
      msg._workflowEvents = result.nodeEvents
    }
    if (result?.suspended) {
      msg.metadata = {
        ...base,
        workflowSuspended: true,
        workflowRunId: result.runId,
        workflowConfirmForm: result.confirmForm,
        workflowEvents: msg._workflowEvents,
      }
      msg._workflowConfirmPending = {
        runId: result.runId,
        confirmForm: result.confirmForm,
      }
    } else {
      msg.metadata = {
        ...base,
        workflowSuspended: false,
        workflowConfirmResolved: true,
        workflowConfirmForm: null,
        workflowRunId: null,
        workflowEvents: msg._workflowEvents,
      }
      msg._workflowConfirmPending = null
    }
  }

  function finalizeWorkflowResumeMessage(msg, result) {
    if (!msg) return
    syncWorkflowResumeMetadata(msg, result)
    if (result?.suspended) {
      msg._streaming = false
      msg._toolsDone = true
      currentStatus.value = '工作流已暂停，等待确认'
      return
    }
    if (result?.output) {
      const out = String(result.output).trim()
      if (out) msg.content = out
    }
    msg._streaming = false
    msg._toolsDone = true
    currentStatus.value = ''
    scrollToBottom()
  }

  async function submitWorkflowConfirm(msg, formData) {
    const pending = msg?._workflowConfirmPending
    const selectedAgentId = getSelectedAgentId()
    if (!pending?.runId || !selectedAgentId) return

    const runId = pending.runId
    const savedPending = { ...pending }
    const suspendNodeId = findSuspendNodeIdFromEvents(msg._workflowEvents, runId)

    const patchRef = { value: { nodeEvents: msg._workflowEvents || [] } }
    patchLocalConfirmEventsBeforeResume(patchRef, suspendNodeId, formData)
    msg._workflowEvents = patchRef.value.nodeEvents

    msg._workflowConfirmPending = null
    msg._streaming = true
    msg._toolsDone = false

    loading.value = true
    streaming.value = true
    setCurrentStreamingMsg(msg)
    streamSmoother.start()
    currentStatus.value = '表单已提交，正在执行后续流程…'

    const controller = new AbortController()
    setAbortController(controller)

    try {
      await resumeWorkflowStream(
        selectedAgentId,
        {
          runId,
          formData,
          messageId:
            msg._id || (typeof msg.metadata === 'object' ? msg.metadata?.assistantMessageId : null) || undefined,
        },
        {
          signal: controller.signal,
          onEvent: (event) => {
            if (event?.type === 'error') {
              throw new Error(event.message || '恢复工作流失败')
            }
            handleChatWorkflowStreamEvent(msg, event)
          },
          onDone: (result) => {
            if (result) finalizeWorkflowResumeMessage(msg, result)
          },
        }
      )
    } catch (e) {
      if (e.name === 'AbortError') return
      message.error(e.message || '恢复工作流失败')
      if (!msg._workflowConfirmPending) {
        msg._workflowConfirmPending = savedPending
      }
      msg._streaming = false
      msg._toolsDone = true
      currentStatus.value = msg._workflowConfirmPending ? '工作流已暂停，等待确认' : ''
    } finally {
      streamSmoother.stop()
      setCurrentStreamingMsg(null)
      setAbortController(null)
      if (!msg._workflowConfirmPending) {
        loading.value = false
        streaming.value = false
      }
    }
  }

  async function abandonWorkflowConfirm(msg) {
    const pending = msg?._workflowConfirmPending
    const selectedAgentId = getSelectedAgentId()
    if (!pending?.runId || !selectedAgentId) return

    const runId = pending.runId
    const suspendNodeId = findSuspendNodeIdFromEvents(msg._workflowEvents, runId)
    const notice = '工作流已终止（用户放弃人工确认）'

    loading.value = true
    try {
      await abandonWorkflowConfirmApi(selectedAgentId, {
        runId,
        messageId: msg._id || (typeof msg.metadata === 'object' ? msg.metadata?.assistantMessageId : null) || undefined,
      })

      const patched = patchLocalConfirmEventsOnAbandon({ _workflowEvents: msg._workflowEvents || [] }, suspendNodeId)
      if (patched) msg._workflowEvents = patched

      msg._workflowConfirmPending = null
      msg._streaming = false
      msg._toolsDone = true
      streaming.value = false

      const existing = msg.content?.trim() || ''
      if (!existing) {
        msg.content = notice
      } else if (!existing.includes(notice)) {
        msg.content = `${existing}\n\n${notice}`
      }

      const base = typeof msg.metadata === 'object' && msg.metadata ? msg.metadata : {}
      msg.metadata = {
        ...base,
        workflowSuspended: false,
        workflowConfirmResolved: true,
        workflowAbandoned: true,
        workflowConfirmForm: null,
        workflowRunId: null,
        workflowEvents: msg._workflowEvents,
      }

      currentStatus.value = ''
      message.success('已放弃本次确认，工作流已终止')
      scrollToBottom()
    } catch (e) {
      message.error(e.message || '放弃确认失败')
    } finally {
      loading.value = false
    }
  }

  function applyToolMetadata(msg, meta) {
    if (!meta) return
    msg.metadata = { ...(msg.metadata || {}), ...meta }
    if (meta.toolEvents?.length) {
      msg._toolEvents = meta.toolEvents
    }
    if (meta.workflowEvents?.length) {
      msg._workflowEvents = meta.workflowEvents
    }
    const failure = resolveWorkflowFailureFromEvents(msg._workflowEvents)
    if (failure || meta.workflowFailed) {
      msg._workflowError = failure || msg._workflowError
      msg.content = stripWorkflowErrorContent(msg.content)
    }
    if (!msg._workflowError && meta.workflowError) {
      msg._workflowError = {
        nodeLabel: '工作流',
        message: meta.workflowError.message || '工作流执行失败',
        reason: meta.workflowError.failureReason,
      }
      msg.content = stripWorkflowErrorContent(msg.content)
    }
    if (meta.workflowAbandoned === true || meta.workflowConfirmResolved === true || meta.workflowSuspended === false) {
      msg._workflowConfirmPending = null
    }
    const pending = resolveWorkflowConfirmPending(msg._workflowEvents, meta)
    if (pending) {
      msg._workflowConfirmPending = pending
    }
    if (meta.toolBlockOffsets?.length) {
      msg._toolBlockOffsets = meta.toolBlockOffsets.map((o) => Number(o))
    }
    // 以 toolEvents 中的 contentOffset 为准，避免 stale toolBlockOffsets 导致历史消息切分错位
    msg._toolBlockOffsets = getToolBlockOffsets(msg)
  }

  return {
    handleChatWorkflowStreamEvent,
    syncWorkflowResumeMetadata,
    finalizeWorkflowResumeMessage,
    submitWorkflowConfirm,
    abandonWorkflowConfirm,
    applyToolMetadata,
  }
}
