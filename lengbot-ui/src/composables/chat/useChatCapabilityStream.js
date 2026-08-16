/**
 * Chat 能力类 SSE 事件处理（Skill / SubAgent，从 Chat.vue 解耦）
 */
import { nextTick } from 'vue'
import { SKILL_ACTIVE_EVENT_TYPE, formatSkillActiveStatus } from '../../components/skills/skillRegistry.js'
import {
  isSubagentEvent,
  formatSubagentCallStatus,
  formatSubagentToolCallStatus,
} from '../../components/capabilities/subagentRegistry.js'
import { resolveToolBlockSplitAt } from './useChatEventPartition.js'

export { SKILL_ACTIVE_EVENT_TYPE }
export { isSubagentEvent }

/**
 * @param {object} deps Chat 运行时依赖
 */
export function createChatCapabilityStreamHandlers(deps) {
  const { currentStatus, hasStreamContent, scrollToBottom, messages, messagesRef, onSubagentEvent } = deps

  function scrollToCapabilityBlock(assistantMsg, selector) {
    if (!assistantMsg) {
      scrollToBottom()
      return
    }
    nextTick(() => {
      requestAnimationFrame(() => {
        const idx = messages?.value?.indexOf(assistantMsg) ?? -1
        const container = messagesRef?.value
        if (idx < 0 || !container) {
          return
        }
        // 用户已向上浏览历史内容时，不因 SubAgent 的工具/状态事件抢回滚动视角。
        const distanceToBottom = container.scrollHeight - container.scrollTop - container.clientHeight
        if (distanceToBottom > 48) return
        const row = container.querySelector(`[data-index="${idx}"]`)
        const block = row?.querySelector(selector)
        if (!block) {
          return
        }
        const containerRect = container.getBoundingClientRect()
        const blockRect = block.getBoundingClientRect()
        if (blockRect.bottom > containerRect.bottom - 16) {
          container.scrollTop += blockRect.bottom - containerRect.bottom + 16
        } else if (blockRect.top < containerRect.top + 16) {
          container.scrollTop += blockRect.top - containerRect.top - 16
        }
      })
    })
  }

  /**
   * 处理 Skill / SubAgent SSE 事件
   * @returns {boolean} 是否已消费该事件
   */
  function handleChatCapabilityStreamEvent(assistantMsg, event) {
    if (!assistantMsg || !event?.type) return false

    if (event.type === SKILL_ACTIVE_EVENT_TYPE) {
      if (!assistantMsg._toolEvents) assistantMsg._toolEvents = []
      assistantMsg._toolEvents.push(event)
      hasStreamContent.value = true
      currentStatus.value = formatSkillActiveStatus(event.skills)
      scrollToBottom()
      return true
    }

    // todos_updated：不进消息气泡的 _toolEvents（write_todos 本身的 tool_result 已展示），仅透传给运行时事件 sink 供状态栏消费
    if (event.type === 'todos_updated') {
      onSubagentEvent?.(event)
      return true
    }

    if (!isSubagentEvent(event)) return false

    const offset = event.contentOffset ?? assistantMsg.content?.length ?? 0
    if (event.contentOffset == null) {
      event.contentOffset = offset
    }

    if (!assistantMsg._toolEvents) assistantMsg._toolEvents = []
    assistantMsg._toolEvents.push(event)
    onSubagentEvent?.(event)

    if (event.type === 'subagent_call' || event.type === 'subagent_batch_start') {
      const content = assistantMsg.content || ''
      const splitAt = resolveToolBlockSplitAt(content, event, offset)
      event.contentOffset = splitAt
      if (!event.contentPrefixAnchor && splitAt > 0) {
        event.contentPrefixAnchor = content.substring(0, splitAt)
      }
      assistantMsg._toolExpanded = true
      assistantMsg._currentToolOffset = splitAt
      registerToolBlockOffset(assistantMsg, splitAt)
      currentStatus.value =
        event.type === 'subagent_batch_start'
          ? `委派 ${event.tasks?.length || 1} 个 SubAgent 任务`
          : formatSubagentCallStatus(event)
      scrollToCapabilityBlock(assistantMsg, '.subagent-call-block')
    } else if (event.type === 'subagent_task_start') {
      currentStatus.value = `SubAgent 任务 ${Number(event.task_index || 0) + 1} 执行中...`
    } else if (event.type === 'subagent_task_done' || event.type === 'subagent_batch_done') {
      currentStatus.value = event.status === 'failed' ? 'SubAgent 批次执行失败' : 'SubAgent 批次执行完成'
    } else if (event.type === 'subagent_tool_call') {
      currentStatus.value = formatSubagentToolCallStatus(event)
      scrollToCapabilityBlock(assistantMsg, '.subagent-call-block')
    } else if (event.type === 'subagent_token') {
      currentStatus.value = 'SubAgent 输出中...'
      scrollToBottom()
    } else if (event.type === 'subagent_error_retry') {
      assistantMsg._toolExpanded = true
      registerToolBlockOffset(assistantMsg, offset)
      assistantMsg._subagentRetryPulse = (assistantMsg._subagentRetryPulse || 0) + 1
      currentStatus.value = event.message || `SubAgent 重试 ${event.attempt}/${event.maxRetries}`
      scrollToCapabilityBlock(
        assistantMsg,
        '.subagent-call-block.is-retrying, .subagent-call-block.retry-pulse, .subagent-call-block'
      )
    } else if (event.type === 'subagent_error') {
      assistantMsg._toolExpanded = true
      registerToolBlockOffset(assistantMsg, offset)
      currentStatus.value = event.message || 'SubAgent 执行失败'
      scrollToCapabilityBlock(assistantMsg, '.subagent-call-block')
    } else {
      hasStreamContent.value = true
      scrollToBottom()
      return true
    }

    hasStreamContent.value = true
    return true
  }

  return { handleChatCapabilityStreamEvent }
}

/** 注册工具块 offset（供 Chat 复用） */
export function registerToolBlockOffset(msg, offset) {
  if (offset == null || offset < 0) return
  if (!msg._toolBlockOffsets) msg._toolBlockOffsets = []
  if (!msg._toolBlockOffsets.some((o) => o == offset)) {
    msg._toolBlockOffsets.push(offset)
    msg._toolBlockOffsets.sort((a, b) => a - b)
  }
}
