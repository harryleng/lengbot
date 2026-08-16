import request from '../utils/request'

/**
 * SubAgent API
 */
export function getSubAgents(params) {
  return request.get('/subagents', { params })
}

export function getSubAgent(id) {
  return request.get(`/subagents/${id}`)
}

export function createSubAgent(data) {
  return request.post('/subagents', data)
}

export function updateSubAgent(data) {
  return request.put('/subagents', data)
}

export function deleteSubAgent(id) {
  return request.delete(`/subagents/${id}`)
}

export function getEnabledSubAgents() {
  return request.get('/subagents/enabled')
}

export function setSubAgentEnabled(id, enabled) {
  return request.put(`/subagents/${id}/enabled`, null, { params: { enabled } })
}

export function getSubAgentRuns(params) {
  return request.get('/subagents/runs', { params })
}

export function getSubAgentRuntimeSummaries(sessionId, limit = 20, parentRequestId) {
  const params = { sessionId, limit }
  if (parentRequestId) params.parentRequestId = parentRequestId
  return request.get('/subagents/runs/summary', { params })
}

/** 获取一条用户请求对应的待办、附件、产物和子智能体状态快照。 */
export function getResearchTaskProjection(sessionId, parentRequestId) {
  return request.get('/subagents/runs/projection', { params: { sessionId, parentRequestId } })
}

export function getSubAgentBatch(batchId, sessionId) {
  return request.get(`/subagents/batches/${batchId}`, { params: { sessionId } })
}

export function cancelSubAgentBatch(batchId, sessionId) {
  return request.post(`/subagents/batches/${batchId}/cancel`, null, { params: { sessionId } })
}

export function cancelSubAgentTask(taskId, sessionId) {
  return request.post(`/subagents/runs/${taskId}/cancel`, null, { params: { sessionId } })
}

export function getSubAgentRun(taskId, sessionId) {
  return request.get(`/subagents/runs/${taskId}`, { params: { sessionId } })
}

export function getSubAgentRunThread(taskId, sessionId) {
  return request.get(`/subagents/runs/${taskId}/thread`, { params: { sessionId } })
}

export function getSubAgentRunEvents(taskId, sessionId, cursor, limit = 50) {
  const params = { sessionId, limit }
  if (cursor) params.cursor = cursor
  return request.get(`/subagents/runs/${taskId}/events`, { params })
}
