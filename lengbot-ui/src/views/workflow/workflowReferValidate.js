/**
 * 工作流变量引用静态校验
 */
import { BUILTIN_VARIABLES } from './nodeConfigMeta'
import { mergeNodeOutputFields } from './nodeIoContract.js'
import { getNeighborNodeIds } from './workflowUpstreamVariables.js'

const REF_PATTERN = /\{\{([^}]+)\}\}|\$\{([^}]+)\}/g

const BUILTIN_KEYS = new Set(BUILTIN_VARIABLES.map((v) => v.key))

/** 解析模板中所有引用路径 */
export function extractReferencePaths(text) {
  if (!text || typeof text !== 'string') return []
  const paths = []
  let m
  REF_PATTERN.lastIndex = 0
  while ((m = REF_PATTERN.exec(text)) !== null) {
    const path = (m[1] || m[2] || '').trim()
    if (path) paths.push(path)
  }
  return paths
}

function resolveFieldExists(path, nodes, edges, currentNodeId) {
  if (!path) return true
  if (path.startsWith('sys.')) {
    const key = path.slice(4)
    return BUILTIN_KEYS.has(key)
  }
  if (!path.includes('.')) {
    // 扁平 {{query}}/{{output}} 等旧画布引用保持兼容
    return true
  }
  const dot = path.indexOf('.')
  const nodeId = path.slice(0, dot)
  const field = path.slice(dot + 1)
  if (nodeId === 'sys') {
    return BUILTIN_KEYS.has(field)
  }
  const node = nodes.find((n) => n.id === nodeId)
  if (!node) return false
  if (currentNodeId) {
    const upstream = new Set(getNeighborNodeIds(currentNodeId, edges))
    if (!upstream.has(nodeId) && nodeId !== currentNodeId) {
      return false
    }
  }
  const fields = mergeNodeOutputFields(node)
  const topField = field.split('.')[0]
  return fields.some((f) => f.key === topField)
}

/** 收集节点配置中所有模板字符串 */
function collectNodeTemplateStrings(node) {
  const d = node.data || {}
  const texts = []
  const push = (v) => {
    if (typeof v === 'string' && v.includes('{{')) texts.push(v)
  }
  push(d.promptTemplate)
  push(d.sysPrompt)
  push(d.inputVariable)
  push(d.output)
  push(d.textTemplate)
  push(d.templateContent)
  push(d.template_content)
  push(d.instruction)
  for (const row of d.inputMappings || []) push(row?.value)
  for (const row of d.outputMappings || []) push(row?.value)
  for (const row of d.jsonParams || d.json_params || []) push(row?.value)
  if (Array.isArray(d.conditionGroups)) {
    for (const g of d.conditionGroups) {
      for (const r of g.rules || g.conditions || []) {
        push(r?.left)
        push(r?.right)
        push(r?.value)
      }
    }
  }
  return texts
}

/**
 * 校验工作流中变量引用是否合法
 * @returns {{ nodeId, field, message }[]}
 */
export function validateWorkflowReferences(nodes, edges) {
  const errors = []
  for (const node of nodes || []) {
    if (node.type === 'start') continue
    const texts = collectNodeTemplateStrings(node)
    for (const text of texts) {
      for (const path of extractReferencePaths(text)) {
        if (!resolveFieldExists(path, nodes, edges, node.id)) {
          errors.push({
            nodeId: node.id,
            field: 'variableRef',
            message: `变量引用无效：${path}（节点 ${node.data?.label || node.id}）`,
          })
        }
      }
    }
  }
  return errors
}
