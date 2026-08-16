/**
 * 聊天消息错误态统一模型：分类、持久化判定、操作栏模式
 */

export const ERROR_TYPE = {
  AI_ERROR: 'AI_ERROR',
  AI_RETRY: 'AI_RETRY',
  REQUEST_ERROR: 'REQUEST_ERROR',
  USER_ABORT: 'USER_ABORT',
  SENSITIVE_BLOCK: 'SENSITIVE_BLOCK',
  TOOL_STEP_LIMIT: 'TOOL_STEP_LIMIT',
  WORKFLOW_ERROR: 'WORKFLOW_ERROR',
  WORKFLOW_NODE_RETRY: 'WORKFLOW_NODE_RETRY',
  WORKFLOW_NODE_FAILURE: 'WORKFLOW_NODE_FAILURE',
}

export const TOOL_STEP_LIMIT_PATTERN = /\[工具调用轮次已达上限[^\]]*\]/
export const USER_ABORT_MARKDOWN = /\*AI 输出已终止\*/

const FATAL_TITLES = {
  [ERROR_TYPE.AI_ERROR]: 'AI 调用异常',
  [ERROR_TYPE.REQUEST_ERROR]: '请求失败',
  [ERROR_TYPE.USER_ABORT]: '输出已终止',
  [ERROR_TYPE.SENSITIVE_BLOCK]: '内容安全拦截',
  [ERROR_TYPE.TOOL_STEP_LIMIT]: '工具调用达上限',
  [ERROR_TYPE.WORKFLOW_ERROR]: '工作流执行失败',
  [ERROR_TYPE.WORKFLOW_NODE_FAILURE]: '工作流节点失败',
}

const RETRY_TITLES = {
  [ERROR_TYPE.AI_RETRY]: 'AI 连接异常，正在重试',
  [ERROR_TYPE.WORKFLOW_NODE_RETRY]: '工作流节点重试中',
}

export function extractToolStepLimitError(content) {
  const match = (content || '').match(TOOL_STEP_LIMIT_PATTERN)
  if (!match) return null
  return {
    message: match[0].replace(/^\[|\]$/g, ''),
    code: ERROR_TYPE.TOOL_STEP_LIMIT,
  }
}

export function stripToolStepLimitFromContent(content) {
  return (content || '')
    .replace(TOOL_STEP_LIMIT_PATTERN, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

export function stripUserAbortFromContent(content) {
  return (content || '')
    .replace(/\n*\*AI 输出已终止\*\n*/g, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

export function isMessagePersisted(msg) {
  // 仅 [DONE] 明确返回 assistantMessageId 或历史加载的消息视为已落库
  return msg?._persisted === true
}

/** 重新生成时可安全删除的助手消息 ID（无 ID 则不删库） */
export function resolveDeleteAssistantMessageId(msg) {
  if (!msg || msg.role !== 'assistant' || !msg._id) return undefined
  return msg._id
}

/** @returns {'retry'|'fatal'|null} */
export function resolveMessageRetryState(msg) {
  if (!msg) return null
  if (msg._errorRetry) {
    return {
      kind: 'retry',
      type: ERROR_TYPE.AI_RETRY,
      title: RETRY_TITLES[ERROR_TYPE.AI_RETRY],
      message: msg._errorRetry.message || 'AI 连接异常，正在重试',
      code: msg._errorRetry.code || 'LLM_ERROR',
      attempt: msg._errorRetry.attempt,
      maxRetries: msg._errorRetry.maxRetries,
      streaming: !!msg._streaming,
    }
  }
  if (msg._workflowNodeRetry && msg._workflowNodeRetry.kind !== 'failure') {
    const reason = msg._workflowNodeRetry.reason
    const titleMap = {
      connect_timeout: '节点连接超时，正在重试',
      read_timeout: '节点响应超时，正在重试',
      execution_error: '节点执行异常，正在重试',
    }
    return {
      kind: 'retry',
      type: ERROR_TYPE.WORKFLOW_NODE_RETRY,
      title: titleMap[reason] || RETRY_TITLES[ERROR_TYPE.WORKFLOW_NODE_RETRY],
      message: msg._workflowNodeRetry.message || '工作流节点重试中',
      code: reason || 'WORKFLOW_RETRY',
      attempt: msg._workflowNodeRetry.attempt,
      maxRetries: msg._workflowNodeRetry.maxAttempts,
      streaming: !!msg._streaming,
    }
  }
  return null
}

/** @returns {object|null} */
export function resolveMessageFatalErrorState(msg) {
  if (!msg) return null

  if (msg._error) {
    const code = msg._error.code || 'UNKNOWN'
    const type = code === 'REQUEST_ERROR' ? ERROR_TYPE.REQUEST_ERROR : ERROR_TYPE.AI_ERROR
    return {
      kind: 'fatal',
      type,
      title: FATAL_TITLES[type] || FATAL_TITLES[ERROR_TYPE.AI_ERROR],
      message: msg._error.message || '未知错误',
      code,
    }
  }

  if (msg._workflowError) {
    return {
      kind: 'fatal',
      type: ERROR_TYPE.WORKFLOW_ERROR,
      title: FATAL_TITLES[ERROR_TYPE.WORKFLOW_ERROR],
      message: msg._workflowError.message || '工作流执行失败',
      code: msg._workflowError.reason || 'WORKFLOW_ERROR',
      nodeLabel: msg._workflowError.nodeLabel,
    }
  }

  if (msg._workflowNodeRetry?.kind === 'failure') {
    return {
      kind: 'fatal',
      type: ERROR_TYPE.WORKFLOW_NODE_FAILURE,
      title: FATAL_TITLES[ERROR_TYPE.WORKFLOW_NODE_FAILURE],
      message: msg._workflowNodeRetry.message || '工作流节点执行失败',
      code: msg._workflowNodeRetry.reason || 'WORKFLOW_NODE_FAILURE',
      attempt: msg._workflowNodeRetry.attempt,
      maxRetries: msg._workflowNodeRetry.maxAttempts,
    }
  }

  if (msg._sensitiveBlock) {
    const scope = msg._sensitiveBlock
    return {
      kind: 'fatal',
      type: ERROR_TYPE.SENSITIVE_BLOCK,
      title: scope === 'user_input' ? '输入内容被拦截' : 'AI 输出被拦截',
      message: msg.content || (scope === 'user_input' ? '输入内容包含敏感信息' : 'AI 输出包含敏感信息，已拦截'),
      code: scope === 'user_input' ? 'SENSITIVE_USER' : 'SENSITIVE_AI',
      scope,
    }
  }

  if (msg._terminated) {
    return {
      kind: 'fatal',
      type: ERROR_TYPE.USER_ABORT,
      title: FATAL_TITLES[ERROR_TYPE.USER_ABORT],
      message: '您已手动终止 AI 输出',
      code: ERROR_TYPE.USER_ABORT,
    }
  }

  const toolLimit = extractToolStepLimitError(msg.content)
  if (toolLimit) {
    return {
      kind: 'fatal',
      type: ERROR_TYPE.TOOL_STEP_LIMIT,
      title: FATAL_TITLES[ERROR_TYPE.TOOL_STEP_LIMIT],
      message: toolLimit.message,
      code: toolLimit.code,
    }
  }

  return null
}

export function hasMessageErrorState(msg) {
  return !!(resolveMessageRetryState(msg) || resolveMessageFatalErrorState(msg))
}

/**
 * 操作栏模式
 * - normal: 常规消息
 * - error_ephemeral: 错误未落库（无 messageId）
 * - error_persisted: 错误已落库（有 messageId）
 */
export function getMessageActionProfile(msg) {
  if (!msg || msg.role !== 'assistant') return 'normal'
  if (!hasMessageErrorState(msg)) return 'normal'
  return isMessagePersisted(msg) ? 'error_persisted' : 'error_ephemeral'
}

/** 流式结束 / 历史加载后：从正文提取结构化错误并清理正文 */
export function normalizeAssistantMessageErrors(msg) {
  if (!msg || msg.role !== 'assistant') return

  if (msg.metadata?.error && !msg._error) {
    const err = msg.metadata.error
    msg._error = {
      message: err.message || '未知错误',
      code: err.code || 'UNKNOWN',
    }
  }

  const toolLimit = extractToolStepLimitError(msg.content)
  if (toolLimit && !msg._error) {
    msg._contentError = toolLimit
  }

  if (extractToolStepLimitError(msg.content)) {
    msg.content = stripToolStepLimitFromContent(msg.content)
  }

  if (msg._terminated || msg.metadata?.aborted || USER_ABORT_MARKDOWN.test(msg.content || '')) {
    if (!msg._error && !msg._terminated) {
      msg._terminated = true
    }
    msg.content = stripUserAbortFromContent(msg.content)
  }
}

/** [DONE] metadata 合并后同步错误态 */
export function applyMessageErrorFromDoneMeta(msg, meta) {
  if (!msg || !meta) return
  if (meta.error) {
    msg._error = {
      message: meta.error.message || '未知错误',
      code: meta.error.code || 'UNKNOWN',
    }
    if (meta.requestId && !msg._requestId) {
      msg._requestId = meta.requestId
    }
    if (!meta.assistantMessageId) {
      msg._persisted = false
      msg._id = null
    }
  }
  normalizeAssistantMessageErrors(msg)
}
