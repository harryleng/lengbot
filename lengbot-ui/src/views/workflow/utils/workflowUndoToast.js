/** 撤回通知推入回调（由 WorkflowUndoToastStack 挂载时注册） */
let pushHandler = null

/**
 * 注册画板内撤回通知栈
 * @param {(text: string) => void} handler
 */
export function bindWorkflowUndoToastStack(handler) {
  pushHandler = handler || null
}

/**
 * 展示工作流撤回通知（单行、2 秒消失、最多 3 条，定位在画板右上角）
 * @param {string} description 撤回说明
 */
export function showWorkflowUndoToast(description) {
  const detail = description || '上一步画布操作'
  pushHandler?.(`↩ 已撤回：${detail}`)
}

export const WORKFLOW_UNDO_TOAST_DURATION_MS = 2000
export const WORKFLOW_UNDO_TOAST_MAX = 3
