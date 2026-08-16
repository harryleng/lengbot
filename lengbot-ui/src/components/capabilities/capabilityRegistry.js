/**
 * Chat 能力块渲染注册表（Skill / SubAgent 等）
 */
import { defineAsyncComponent } from 'vue'
import { SKILL_ACTIVE_EVENT_TYPE } from '../skills/skillRegistry.js'
import { SUBAGENT_BATCH_START_EVENT_TYPE, SUBAGENT_CALL_EVENT_TYPE } from './subagentRegistry.js'

const SkillActiveBlock = defineAsyncComponent(() => import('../skills/SkillActiveBlock.vue'))
const SubAgentCallBlock = defineAsyncComponent(() => import('./SubAgentCallBlock.vue'))

/** 能力事件类型 → 块级渲染组件（仅顶层块，非 tool_result） */
export const CAPABILITY_BLOCK_RENDERERS = {
  [SKILL_ACTIVE_EVENT_TYPE]: SkillActiveBlock,
  [SUBAGENT_CALL_EVENT_TYPE]: SubAgentCallBlock,
  [SUBAGENT_BATCH_START_EVENT_TYPE]: SubAgentCallBlock,
}

/**
 * 获取能力块渲染组件
 * @param {string} eventType
 */
export function getCapabilityBlockRenderer(eventType) {
  return CAPABILITY_BLOCK_RENDERERS[eventType] || null
}

/** 是否由能力面板渲染的块级事件 */
export function isCapabilityBlockEvent(event) {
  if (!event?.type) return false
  return Object.prototype.hasOwnProperty.call(CAPABILITY_BLOCK_RENDERERS, event.type)
}

/** 过滤出可渲染的块级能力事件 */
export function filterCapabilityBlockEvents(events) {
  return (events || []).filter(isCapabilityBlockEvent)
}
