import { getTopSkillEvents } from '../../composables/chat/useChatEventPartition.js'
import { hasMessageErrorState } from './messageErrorState.js'

/**
 * 从 ctx 解析 editingMessageId（支持 Ref 或原始值）
 * @param {import('vue').Ref<string|null>|string|null} editingMessageId
 */
function resolveEditingMessageId(editingMessageId) {
  return editingMessageId?.value ?? editingMessageId ?? null
}

/**
 * 从 ctx 解析 messages 数组（支持 Ref 或原始数组）
 * @param {import('vue').Ref<Array>|Array} messages
 */
function resolveMessages(messages) {
  return messages?.value ?? messages ?? []
}

/** 消息是否具备可展示操作栏的上下文（正文、错误、工作流轨迹等） */
export function hasMessageActionContext(msg) {
  if (!msg) return false
  return !!(
    msg.content ||
    msg._error ||
    msg._workflowError ||
    msg._workflowEvents?.length > 0 ||
    msg._sensitiveBlock ||
    msg._terminated ||
    hasMessageErrorState(msg)
  )
}

/**
 * 推导单条消息的展示态，镜像 Chat.vue 消息行模板中的 v-if 条件
 * @param {object} msg
 * @param {object} ctx
 * @param {number} ctx.index
 * @param {import('vue').Ref<string|null>|string|null} [ctx.editingMessageId]
 * @param {import('vue').Ref<Array>|Array} [ctx.messages]
 * @param {number} [ctx.messagesLength]
 * @param {number|null} [ctx.lastReplyElapsed]
 * @param {(msg: object) => object|null} [ctx.getReplyToInfo]
 * @param {(msg: object) => Array} [ctx.getMsgAttachments]
 * @param {(msg: object) => Array} [ctx.getMsgRagRefs]
 * @param {(msg: object, index: number) => boolean} [ctx.isMessageEditing]
 * @returns {object}
 */
export function buildMessageViewState(msg, ctx) {
  const {
    index,
    editingMessageId,
    messages,
    messagesLength,
    lastReplyElapsed,
    getReplyToInfo: getReplyToInfoFn,
    getMsgAttachments: getMsgAttachmentsFn,
    getMsgRagRefs: getMsgRagRefsFn,
    isMessageEditing: isMessageEditingFn,
  } = ctx

  const sensitiveBlock = !!msg?._sensitiveBlock

  const isEditing =
    typeof isMessageEditingFn === 'function'
      ? isMessageEditingFn(msg, index)
      : (() => {
          const eid = resolveEditingMessageId(editingMessageId)
          if (!eid || !msg) return false
          if (msg._id) return eid === msg._id
          return eid === `local-${index}`
        })()

  const replyToInfo =
    typeof getReplyToInfoFn === 'function'
      ? getReplyToInfoFn(msg)
      : (() => {
          if (!msg?._replyToMessageId) return null
          if (msg._replyToContent) {
            return { content: msg._replyToContent, role: msg._replyToRole }
          }
          const list = resolveMessages(messages)
          const refMsg = list.find((m) => m._id === msg._replyToMessageId)
          if (refMsg) {
            return { content: (refMsg.content || '').slice(0, 100), role: refMsg.role }
          }
          return null
        })()

  const attachments = typeof getMsgAttachmentsFn === 'function' ? getMsgAttachmentsFn(msg) : msg?._attachments || []

  const ragRefs = typeof getMsgRagRefsFn === 'function' ? getMsgRagRefsFn(msg) : []

  const len = messagesLength ?? resolveMessages(messages).length
  const hasErrors = hasMessageErrorState(msg)

  return {
    mode: isEditing ? 'edit' : 'normal',
    showReplyQuote: !!replyToInfo,
    showUserAttachments: msg?.role === 'user' && attachments.length > 0 && !sensitiveBlock,
    showSensitiveBlock: false,
    showReasoning: !!msg?._reasoningContent && !sensitiveBlock && !hasErrors,
    showTopSkills: getTopSkillEvents(msg).length > 0 && !sensitiveBlock && !hasErrors,
    showWorkflow: (msg?._workflowEvents?.length ?? 0) > 0 && !sensitiveBlock && !hasErrors,
    showToolSegments: !sensitiveBlock && !hasErrors && (msg?._toolEvents?.length ?? 0) > 0,
    showPlainContent: !sensitiveBlock && !hasErrors && !(msg?._toolEvents?.length > 0),
    showPlainContentBlock:
      !sensitiveBlock && !hasErrors && !(msg?._toolEvents?.length > 0) && !!msg?.content && msg.content !== '[附件]',
    showWorkflowConfirm: !!msg?._workflowConfirmPending?.confirmForm && !sensitiveBlock && !hasErrors,
    showErrorRetry: false,
    showError: false,
    showWorkflowResilience: false,
    showWorkflowError: false,
    showErrors: hasErrors,
    showActions: !msg?._streaming && hasMessageActionContext(msg),
    showRagRefs: msg?.role === 'assistant' && ragRefs.length > 0 && !msg?._streaming,
    showReplyElapsed:
      msg?.role === 'assistant' &&
      index === len - 1 &&
      !msg?._streaming &&
      lastReplyElapsed !== null &&
      lastReplyElapsed !== undefined,
    showFeedback: msg?.role === 'assistant' && !msg?._streaming && !!(msg?._id || msg?._terminated),
    isUserMessageStack: msg?.role === 'user',
  }
}
