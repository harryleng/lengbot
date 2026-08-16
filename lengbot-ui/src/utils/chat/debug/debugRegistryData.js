import {
  TOOL_RENDERERS,
  TOOL_DISPLAY_NAMES,
  HIDDEN_TOOL_NAMES,
  getToolDisplayName,
  hasToolRenderer,
} from '@/components/toolRegistry'
import { CAPABILITY_BLOCK_RENDERERS } from '@/components/capabilities/capabilityRegistry'
import { SUBAGENT_EVENT_TYPES } from '@/components/capabilities/subagentRegistry'
import {
  SKILL_ITEM_RENDERERS,
  SKILL_META,
  BUILTIN_SKILL_SLUGS,
  SKILL_ACTIVE_EVENT_TYPE,
} from '@/components/skills/skillRegistry'

/** SSE 流事件类型（非工具）：SubAgent 委派运行时推送 + Skill 启用事件，由能力面板渲染 */
const CAPABILITY_EVENT_TYPES = new Set([...SUBAGENT_EVENT_TYPES, SKILL_ACTIVE_EVENT_TYPE])

/** 顶层块渲染组件名（对齐 CAPABILITY_BLOCK_RENDERERS 的实际组件） */
const CAPABILITY_BLOCK_COMPONENT_NAMES = {
  [SKILL_ACTIVE_EVENT_TYPE]: 'SkillActiveBlock',
  subagent_call: 'SubAgentCallBlock',
  subagent_batch_start: 'SubAgentCallBlock（批次适配）',
}

/** 工具注册表只读数据（仅真正的工具，排除 SSE 流事件） */
export function getToolRegistryRows() {
  const names = new Set([...Object.keys(TOOL_RENDERERS), ...Object.keys(TOOL_DISPLAY_NAMES), ...HIDDEN_TOOL_NAMES])
  return Array.from(names)
    .filter((toolName) => !CAPABILITY_EVENT_TYPES.has(toolName))
    .sort()
    .map((toolName) => ({
      toolName,
      displayName: TOOL_DISPLAY_NAMES[toolName] || toolName,
      hasRenderer: hasToolRenderer(toolName),
      hidden: HIDDEN_TOOL_NAMES.has(toolName),
      component: TOOL_RENDERERS[toolName]
        ? '专用 *Result.vue'
        : HIDDEN_TOOL_NAMES.has(toolName)
          ? 'CapabilityPanel'
          : 'BaseToolCall',
    }))
}

/** 能力块注册表只读数据：SubAgent 流事件 + Skill 启用事件，区分顶层块与流内子事件 */
export function getCapabilityRegistryRows() {
  return Array.from(CAPABILITY_EVENT_TYPES)
    .sort()
    .map((eventType) => {
      const blockComponent = CAPABILITY_BLOCK_COMPONENT_NAMES[eventType]
      const topLevel = !!CAPABILITY_BLOCK_RENDERERS[eventType]
      return {
        eventType,
        displayName: getToolDisplayName(eventType),
        component: topLevel ? blockComponent : 'SubAgent 流内子事件',
        topLevel: topLevel ? '是' : '否',
      }
    })
}

/** Skill 列表项注册表只读数据 */
export function getSkillRegistryRows() {
  const slugs = new Set([...BUILTIN_SKILL_SLUGS, ...Object.keys(SKILL_META), ...Object.keys(SKILL_ITEM_RENDERERS)])
  return Array.from(slugs)
    .sort()
    .map((slug) => ({
      slug,
      displayName: SKILL_META[slug]?.displayName || slug,
      hasCustomRenderer: Object.prototype.hasOwnProperty.call(SKILL_ITEM_RENDERERS, slug),
      component: SKILL_ITEM_RENDERERS[slug] ? '定制 SkillItem' : 'DefaultSkillItem',
      builtin: BUILTIN_SKILL_SLUGS.includes(slug),
    }))
}
