import { computed } from 'vue'

/**
 * 是否存在未提交的工作流人工确认表单
 * @param {Array} messages
 * @returns {boolean}
 */
export function hasPendingWorkflowConfirm(messages) {
  if (!Array.isArray(messages)) return false
  return messages.some(
    (m) => m?.role === 'assistant' && m._workflowConfirmPending?.confirmForm && m._workflowConfirmPending?.runId
  )
}

/**
 * 对话发送门控：loading + 工作流人工确认待填
 * @param {object} options
 * @param {import('vue').Ref<boolean>} options.loading
 * @param {import('vue').Ref<string>} options.input
 * @param {import('vue').Ref<Array>} options.pendingAttachments
 * @param {import('vue').Ref<Array>} options.messages
 */
export function useChatSendGate({ loading, input, pendingAttachments, messages }) {
  const workflowConfirmBlocked = computed(() => hasPendingWorkflowConfirm(messages.value))

  const sendBlockReason = computed(() => {
    if (loading.value) return 'generating'
    if (workflowConfirmBlocked.value) return 'workflow_confirm'
    return null
  })

  const canSend = computed(() => {
    if (loading.value) return false
    if (workflowConfirmBlocked.value) return false
    return input.value.trim().length > 0 || pendingAttachments.value.length > 0
  })

  return {
    canSend,
    sendBlockReason,
    workflowConfirmBlocked,
  }
}
