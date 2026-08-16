/** @ mention 展示文案（输入框 / 历史消息共用） */

export const MENTION_TYPE_LABELS = {
  knowledge: '知识库',
  subagent: 'SubAgents',
  skill: 'Skill',
  tool: '工具',
}

/**
 * @param {string} type mention 类型
 * @returns {string} chip 样式类名（不含 mention-chip 基础类）
 */
export function getMentionChipClass(type) {
  return `mention-chip-${type || 'tool'}`
}

/**
 * @param {string} type mention 类型
 * @param {string} name 展示名
 * @param {string|number} resourceId 资源ID
 * @param {string} token 原始 token
 * @returns {{ title: string, sub: string }}
 */
export function getMentionTooltip(type, name, resourceId, token) {
  const typeLabel = MENTION_TYPE_LABELS[type] || type || '资源'
  const lines = []
  if (resourceId != null && String(resourceId) !== '') lines.push(`ID：${resourceId}`)
  if (token) lines.push(token)
  return {
    title: `${typeLabel}：${name}`,
    sub: lines.join('\n'),
  }
}
