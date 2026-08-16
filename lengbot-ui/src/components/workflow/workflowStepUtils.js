/** 工作流步骤展示通用工具 */
import { hasToolRenderer, TOOL_DISPLAY_NAMES } from '../toolRegistry.js'

/** 工具显示名 → 注册名（如「计算器」→ calculator） */
const DISPLAY_NAME_TO_TOOL = Object.entries(TOOL_DISPLAY_NAMES).reduce((acc, [key, label]) => {
  if (label) acc[label] = key
  return acc
}, {})

export function parseStepOutputs(outputs) {
  if (!outputs) return null
  if (typeof outputs === 'string') {
    try {
      return JSON.parse(outputs)
    } catch {
      return null
    }
  }
  return typeof outputs === 'object' ? outputs : null
}

export function formatKv(value) {
  if (value == null) return ''
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

export function hasKvData(value) {
  return value && typeof value === 'object' && Object.keys(value).length > 0
}

export function truncateText(text, max = 120) {
  if (text == null) return ''
  const s = String(text)
  return s.length <= max ? s : `${s.slice(0, max)}…`
}

/** 预览任意值为可读字符串 */
export function previewValue(value, max = 80) {
  if (value == null) return '—'
  if (typeof value === 'object') return truncateText(formatKv(value), max)
  return truncateText(String(value), max)
}

/** 统计 outputs 中有效键（排除内部字段） */
export function listOutputKeys(outputs, exclude = []) {
  if (!outputs || typeof outputs !== 'object') return []
  const skip = new Set([...exclude, 'llmOutput', 'toolResultText', 'extractRaw', '_is_completed', '_reason'])
  return Object.keys(outputs).filter((k) => !skip.has(k))
}

/** 从步骤中提取用户输入预览 */
export function extractUserInputText(step) {
  const outputs = parseStepOutputs(step?.outputs) || {}
  const fromOutput = outputs.input ?? outputs.query
  if (fromOutput != null && String(fromOutput).trim()) return String(fromOutput)
  const fromInput = step?.input?.userInput
  if (fromInput != null && String(fromInput).trim()) return String(fromInput)
  return ''
}

/** 构建变量赋值展示行 */
export function buildAssignmentRows(outputs, excludeKeys = []) {
  const keys = listOutputKeys(outputs, excludeKeys)
  return keys.map((key) => ({
    key,
    label: key,
    value: previewValue(outputs[key], 200),
  }))
}

export function isWorkflowAwaitingConfirm(workflowEvents) {
  return !!findUnresolvedConfirmEvent(workflowEvents)
}

const WORKFLOW_ERROR_CONTENT_PATTERNS = [
  /节点【[^】]+】执行失败[^\n]*/g,
  /执行失败:\s*java\.[^\n]*/g,
  /工作流执行失败[^\n]*/g,
]

/** 从正文移除工作流失败类脏文本（历史消息兼容） */
export function stripWorkflowErrorContent(content) {
  if (!content) return ''
  let result = String(content)
  for (const pattern of WORKFLOW_ERROR_CONTENT_PATTERNS) {
    result = result.replace(pattern, '')
  }
  return result.replace(/\n{3,}/g, '\n\n').trim()
}

/** 从 workflowEvents 解析最终失败节点 */
export function resolveWorkflowFailureFromEvents(workflowEvents) {
  if (!Array.isArray(workflowEvents) || !workflowEvents.length) return null
  for (let i = workflowEvents.length - 1; i >= 0; i--) {
    const ev = workflowEvents[i]
    if (ev?.type !== 'workflow_node_complete') continue
    if (ev.success !== false) continue
    return {
      nodeId: ev.nodeId,
      nodeLabel: ev.nodeLabel || ev.nodeType || '节点',
      nodeType: ev.nodeType,
      message: ev.userMessage || ev.message || '节点执行失败',
      reason: ev.failureReason,
      durationMs: ev.durationMs,
    }
  }
  return null
}

/** 工作流失败原因中文标签 */
export function workflowFailureReasonLabel(reason) {
  const map = {
    connect_timeout: '连接超时',
    read_timeout: '响应超时',
    execution_error: '执行异常',
  }
  return map[reason] || '执行失败'
}

/** 从 events / metadata 恢复挂起确认态 */
export function resolveWorkflowConfirmPending(workflowEvents, metadata) {
  if (metadata?.workflowConfirmResolved === true || metadata?.workflowAbandoned === true) {
    return null
  }
  if (metadata?.workflowSuspended === false) {
    return null
  }
  if (Array.isArray(workflowEvents)) {
    const hasComplete = workflowEvents.some((e) => e?.type === 'workflow_complete')
    const pendingEvent = findUnresolvedConfirmEvent(workflowEvents)
    const confirmSubmitted = workflowEvents.some(
      (e) =>
        e?.type === 'workflow_node_complete' &&
        e.nodeType === 'confirm' &&
        !e.suspended &&
        e.outputs &&
        Object.keys(e.outputs).length > 0
    )
    if (confirmSubmitted && !pendingEvent) {
      return null
    }
    if (hasComplete && !pendingEvent) {
      return null
    }
    if (!pendingEvent) {
      return null
    }
    if (metadata?.workflowConfirmForm && metadata?.workflowRunId) {
      return {
        runId: String(metadata.workflowRunId),
        confirmForm: metadata.workflowConfirmForm,
      }
    }
    return {
      runId: pendingEvent.runId != null ? String(pendingEvent.runId) : metadata?.workflowRunId,
      confirmForm: pendingEvent.confirmForm,
    }
  }
  if (metadata?.workflowConfirmForm && metadata?.workflowRunId && metadata?.workflowSuspended) {
    return {
      runId: String(metadata.workflowRunId),
      confirmForm: metadata.workflowConfirmForm,
    }
  }
  return null
}

/** 提取已提交的 confirm 数据（用于只读回显） */
export function resolveWorkflowConfirmSubmitted(workflowEvents) {
  if (!Array.isArray(workflowEvents)) return null
  for (let i = workflowEvents.length - 1; i >= 0; i--) {
    const ev = workflowEvents[i]
    if (ev?.type === 'workflow_confirm_required' && ev.resolved && ev.submittedData) {
      return ev.submittedData
    }
  }
  for (let i = workflowEvents.length - 1; i >= 0; i--) {
    const ev = workflowEvents[i]
    if (ev?.type === 'workflow_node_complete' && ev.outputs && ev.nodeType === 'confirm') {
      return ev.outputs
    }
  }
  return null
}

function findUnresolvedConfirmEvent(workflowEvents) {
  for (let i = workflowEvents.length - 1; i >= 0; i--) {
    const ev = workflowEvents[i]
    if (ev?.type !== 'workflow_confirm_required') continue
    if (ev.resolved || ev.submittedData) continue
    if (ev.confirmForm) return ev
  }
  return null
}

/**
 * 解析工作流 tool/mcp 节点的工具注册名（对齐 toolRegistry key）
 * @param {object} step 工作流步骤
 */
export function resolveWorkflowToolName(step) {
  const outputs = parseStepOutputs(step?.outputs) || {}
  const candidates = [outputs.toolName, step?.toolName, outputs.toolDisplayName]
    .map((v) => String(v || '').trim())
    .filter(Boolean)

  for (const name of candidates) {
    if (hasToolRenderer(name)) return name
    const mapped = DISPLAY_NAME_TO_TOOL[name]
    if (mapped && hasToolRenderer(mapped)) return mapped
    const lower = name.toLowerCase()
    if (hasToolRenderer(lower)) return lower
  }
  return candidates[0] || ''
}

/** 从扁平字段重建计算器类工具 JSON（兼容 outputMappings 展开后的 outputs） */
function reconstructCalculatorResult(outputs) {
  if (outputs.expression == null && outputs.result == null) return ''
  try {
    return JSON.stringify({
      expression: outputs.expression,
      operation: outputs.operation,
      operands: outputs.operands,
      result: outputs.result,
    })
  } catch {
    return ''
  }
}

/**
 * 从工作流 tool 节点 outputs 提取与 Chat tool_result 一致的结果字符串
 */
export function extractWorkflowToolResult(outputs) {
  if (!outputs || typeof outputs !== 'object') return ''

  const text = outputs.toolResultText
  if (typeof text === 'string' && text.trim()) {
    return text
  }

  const toolResult = outputs.toolResult
  if (toolResult != null) {
    if (typeof toolResult === 'string' && toolResult.trim()) {
      return toolResult
    }
    try {
      return JSON.stringify(toolResult)
    } catch {
      return String(toolResult)
    }
  }

  const output = outputs.output
  if (typeof output === 'string' && output.trim()) {
    return output
  }
  if (output != null && typeof output === 'object') {
    try {
      return JSON.stringify(output)
    } catch {
      return String(output)
    }
  }

  const calculatorJson = reconstructCalculatorResult(outputs)
  if (calculatorJson) return calculatorJson

  return ''
}

/**
 * 从 ask_user 节点 resume 后的 outputs 中提取用户回答
 * （优先 answer，其次 outputMappings 写入的业务字段如 province / gaokaoScore）
 */
export function pickAskUserAnswerFromOutputs(outputs) {
  if (!outputs || typeof outputs !== 'object') return ''
  const skip = new Set([
    'output',
    'toolResult',
    'toolResultText',
    'toolName',
    'toolId',
    'toolDisplayName',
    'question',
    'options',
    'is_open_ended',
    'wait_for_user',
    'break_loop',
  ])
  const answer = outputs.answer
  if (answer != null && String(answer).trim()) return String(answer).trim()
  for (const [key, value] of Object.entries(outputs)) {
    if (skip.has(key)) continue
    if (value == null || typeof value === 'object') continue
    const text = String(value).trim()
    if (text) return text
  }
  return ''
}

/**
 * 从 confirmForm（workflow_confirm_required）提取 ask_user 选项列表
 */
export function extractAskUserOptionsFromConfirmForm(confirmForm) {
  const fields = confirmForm?.formFields
  if (!Array.isArray(fields)) return []
  for (const field of fields) {
    if (field?.type !== 'radio' || !Array.isArray(field.options)) continue
    return field.options
      .map((opt) => (typeof opt === 'string' ? opt : (opt?.label ?? opt?.value ?? '')))
      .map((s) => String(s).trim())
      .filter(Boolean)
  }
  return []
}

/**
 * 从 step / outputs / confirmForm 回退解析 ask_user 提问文案
 */
export function pickAskUserQuestionFromStep(outputs, step) {
  const q = outputs?.question
  if (q != null && String(q).trim()) return String(q).trim()
  const msg = step?.confirmForm?.message
  if (msg != null && String(msg).trim()) return String(msg).trim()
  const raw = outputs?.toolResultText ?? outputs?.output
  if (typeof raw === 'string' && raw.trim()) {
    try {
      const parsed = JSON.parse(raw)
      if (parsed?.question && String(parsed.question).trim()) {
        return String(parsed.question).trim()
      }
    } catch {
      /* ignore */
    }
  }
  return ''
}

/**
 * 合并 ask_user 工具 JSON 与用户回答，供工作流 ToolCallRenderer / AskUserResult 展示
 */
export function enrichAskUserWorkflowResult(rawResult, outputs, step) {
  let data = {}
  let resultText = rawResult
  if (!resultText && outputs?.toolResultText) {
    resultText = String(outputs.toolResultText)
  }
  if (resultText) {
    try {
      const parsed = JSON.parse(resultText)
      if (parsed && typeof parsed === 'object') data = { ...parsed }
    } catch {
      data = { question: resultText }
    }
  }

  const question = pickAskUserQuestionFromStep(outputs, step)
  if (question) {
    data.question = question
  }
  if ((!data.options || !data.options.length) && outputs?.options) {
    data.options = outputs.options
  }
  if ((!data.options || !data.options.length) && step?.confirmForm) {
    const opts = extractAskUserOptionsFromConfirmForm(step.confirmForm)
    if (opts.length) data.options = opts
  }

  const userAnswer = pickAskUserAnswerFromOutputs(outputs)
  if (userAnswer) {
    data.user_answer = userAnswer
    data.wait_for_user = false
  } else if (step?.status === 'suspended') {
    data.wait_for_user = true
  }
  try {
    return JSON.stringify(data)
  } catch {
    return resultText || rawResult || ''
  }
}

/**
 * 构造 toolRegistry / ToolCallRenderer 可用的 pseudo event（对齐对话 Agent tool_result）
 */
export function buildWorkflowToolEvent(step) {
  const outputs = parseStepOutputs(step?.outputs) || {}
  const toolName = resolveWorkflowToolName(step)
  let result = extractWorkflowToolResult(outputs)
  if (toolName === 'ask_user') {
    result = enrichAskUserWorkflowResult(result, outputs, step)
  }
  return {
    toolName,
    displayName: outputs.toolDisplayName || '',
    result,
    type: 'tool_result',
  }
}

/** @deprecated 使用 buildWorkflowToolEvent */
export function buildToolPseudoEvent(step) {
  return buildWorkflowToolEvent(step)
}

/** 工作流 tool 节点是否可交给 ToolCallRenderer 渲染 */
export function canRenderWorkflowTool(event) {
  if (!event?.result || !String(event.result).trim()) return false
  if (event.toolName && hasToolRenderer(event.toolName)) return true
  // 未注册专用组件时仍走 BaseToolCall 兜底
  return true
}

/** 工作流 tool/mcp 节点是否应使用 toolRegistry 专用渲染（非 JSON 兜底） */
export function hasWorkflowToolRenderer(step) {
  const event = buildWorkflowToolEvent(step)
  return !!(event.toolName && hasToolRenderer(event.toolName) && event.result)
}

export function extractLegacyResultText(step) {
  const outputs = parseStepOutputs(step?.outputs)
  if (outputs && Object.keys(outputs).length > 0) {
    const preferKeys = ['result', 'output', 'text', 'answer', 'retrievalResult']
    for (const k of preferKeys) {
      const v = outputs[k]
      if (v != null && String(v).trim()) return String(v)
    }
    for (const v of Object.values(outputs)) {
      if (v != null && String(v).trim() && typeof v !== 'object') return String(v)
    }
  }
  if (step?.detail && String(step.detail).trim()) {
    return String(step.detail)
  }
  return ''
}

/** 合并 resume 返回的全量 events（去重，以 resume 结果为准） */
export function mergeWorkflowEvents(existing, incoming) {
  if (!incoming?.length) return existing || []
  return incoming
}
