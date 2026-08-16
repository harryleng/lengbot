/**
 * SubAgent Chat 能力事件注册表
 */
import { RobotOutlined } from '@ant-design/icons-vue'

export const SUBAGENT_EVENT_TYPES = new Set([
  'subagent_batch_start',
  'subagent_task_start',
  'subagent_task_done',
  'subagent_batch_done',
  'subagent_batch_update',
  'subagent_call',
  'subagent_result',
  'subagent_token',
  'subagent_tool_call',
  'subagent_tool_result',
  'subagent_error',
  'subagent_error_retry',
])

export const SUBAGENT_CALL_EVENT_TYPE = 'subagent_call'
export const SUBAGENT_BATCH_START_EVENT_TYPE = 'subagent_batch_start'

export function isSubagentEvent(event) {
  return SUBAGENT_EVENT_TYPES.has(event?.type)
}

export function formatSubagentCallTitle(event) {
  const name = event?.displayName || event?.subagentName || 'SubAgent'
  return `委派 SubAgent：${name}`
}

export function formatSubagentCallStatus(event) {
  const name = event?.displayName || event?.subagentName || ''
  return name ? `委派 SubAgent：${name}` : '委派 SubAgent'
}

export function formatSubagentToolCallStatus(event) {
  const tool = event?.toolDisplayName || event?.toolName || ''
  return tool ? `SubAgent 调用工具：${tool}` : 'SubAgent 调用工具'
}

export function getSubagentIcon() {
  return RobotOutlined
}
