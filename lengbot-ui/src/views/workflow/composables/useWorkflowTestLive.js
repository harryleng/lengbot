/**
 * 工作流调试实时 SSE 事件处理（live 模式：无回放动画，边收边更新画板与时间线）
 */

/**
 * @param {Object} event SSE 节点事件
 * @param {Object} ctx 上下文 refs / setters
 */
export function handleLiveWorkflowTestEvent(event, ctx) {
  if (!event?.type) return

  const { testResult, testPendingConfirm, testCurrentNodeId, testFailedNodeId, setReplayNodeStatus } = ctx

  if (!testResult.value) {
    testResult.value = { nodeEvents: [], output: '', variables: {} }
  }
  if (!testResult.value.nodeEvents) {
    testResult.value.nodeEvents = []
  }

  const appendEvent = (ev) => {
    testResult.value = {
      ...testResult.value,
      nodeEvents: [...testResult.value.nodeEvents, ev],
    }
  }

  const type = event.type

  if (type === 'workflow_node_start') {
    appendEvent(event)
    setReplayNodeStatus(event.nodeId, 'executing')
    testCurrentNodeId.value = event.nodeId
    if (testFailedNodeId) testFailedNodeId.value = null
    return
  }

  if (type === 'workflow_node_complete') {
    appendEvent(event)
    const status = event.success === false ? 'fail' : event.suspended ? 'executing' : 'success'
    setReplayNodeStatus(event.nodeId, status, event.durationMs)
    if (event.success === false && testFailedNodeId) {
      testFailedNodeId.value = event.nodeId
    }
    if (!event.suspended) {
      testCurrentNodeId.value = null
    }
    return
  }

  if (type === 'workflow_confirm_required') {
    appendEvent(event)
    testPendingConfirm.value = {
      runId: event.runId,
      confirmForm: event.confirmForm,
    }
    return
  }

  if (type === 'workflow_suspended') {
    appendEvent(event)
    return
  }

  if (type === 'workflow_complete' || type === 'workflow_llm_chunk') {
    appendEvent(event)
    return
  }

  appendEvent(event)
}

/**
 * 恢复执行前：本地同步 patch confirm 节点事件（与后端 patchConfirmEventsOnResume 一致）
 */
export function patchLocalConfirmEventsBeforeResume(testResult, suspendNodeId, formData) {
  if (!testResult.value?.nodeEvents?.length || !suspendNodeId) return

  const submitted = {}
  if (formData) {
    Object.entries(formData).forEach(([key, value]) => {
      if (!key || key.startsWith('_')) return
      submitted[key] = value
    })
  }

  const events = testResult.value.nodeEvents.map((e) => ({ ...e }))
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i]
    if (e.type !== 'workflow_node_complete') continue
    if (String(e.nodeId) !== String(suspendNodeId)) continue
    if (!e.suspended) continue
    e.suspended = false
    e.success = true
    e.message = '用户已提交'
    if (Object.keys(submitted).length) {
      e.outputs = { ...(e.outputs || {}), ...submitted }
    }
    break
  }
  events.forEach((e) => {
    if (e.type !== 'workflow_confirm_required') return
    if (String(e.nodeId) !== String(suspendNodeId)) return
    e.resolved = true
    if (Object.keys(submitted).length) e.submittedData = { ...submitted }
  })

  testResult.value = {
    ...testResult.value,
    nodeEvents: events,
  }
}

export function findSuspendNodeIdFromEvents(nodeEvents, runId) {
  if (!nodeEvents?.length) return null
  for (let i = nodeEvents.length - 1; i >= 0; i--) {
    const e = nodeEvents[i]
    if (e.type === 'workflow_confirm_required' && (!runId || e.runId === runId)) {
      return e.nodeId
    }
  }
  return null
}

/**
 * 放弃确认：本地 patch confirm 节点事件（与后端 patchConfirmEventsOnAbandon 一致）
 */
export function patchLocalConfirmEventsOnAbandon(target, suspendNodeId) {
  const events = Array.isArray(target?.nodeEvents)
    ? target.nodeEvents
    : Array.isArray(target?._workflowEvents)
      ? target._workflowEvents
      : null
  if (!events?.length || !suspendNodeId) return events

  const next = events.map((e) => ({ ...e }))
  for (let i = next.length - 1; i >= 0; i--) {
    const e = next[i]
    if (e.type !== 'workflow_node_complete') continue
    if (String(e.nodeId) !== String(suspendNodeId)) continue
    if (!e.suspended) continue
    e.suspended = false
    e.success = false
    e.message = '用户已放弃'
    break
  }
  next.forEach((e) => {
    if (e.type !== 'workflow_confirm_required') return
    if (String(e.nodeId) !== String(suspendNodeId)) return
    e.resolved = true
    e.abandoned = true
  })
  next.push({
    type: 'workflow_abandoned',
    message: '用户放弃人工确认，工作流已终止',
  })
  return next
}

export function applyWorkflowTestStreamResult(result, ctx) {
  const { testResult, testPendingConfirm } = ctx
  testResult.value = {
    ...result,
    nodeEvents: result?.nodeEvents?.length ? result.nodeEvents : testResult.value?.nodeEvents || [],
  }
  if (result?.suspended) {
    testPendingConfirm.value = {
      runId: result.runId,
      confirmForm: result.confirmForm,
    }
  } else {
    testPendingConfirm.value = null
  }
}
