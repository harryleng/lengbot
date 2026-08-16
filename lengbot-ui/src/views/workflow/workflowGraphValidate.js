/**
 * 工作流图结构校验（与后端 WorkflowGraphValidateUtil 语义对齐）
 */

import { getNodeMeta } from './nodeMeta'
import { isGroupNodeType } from './workflowGroup'

/** 允许多条出边的分支节点类型 */
export const BRANCH_NODE_TYPES = new Set(['condition', 'classifier'])

const MULTI_OUT_EDGE_MESSAGE =
  '当前引擎仅会沿第一条出边继续执行，不会并行跑多条分支。请删除多余连线，或改用「条件分支 / 意图分类」节点。'

function resolveNodeLabel(node) {
  if (!node) return '节点'
  const label = node.data?.label?.trim()
  if (label) return label
  return getNodeMeta(node.type)?.title || node.id || '节点'
}

function isMultiOutEdgeAllowed(nodeType) {
  if (!nodeType || nodeType === 'end') return true
  if (isGroupNodeType(nodeType)) return true
  return BRANCH_NODE_TYPES.has(nodeType)
}

function countOutgoingEdges(edges, sourceId) {
  if (!sourceId || !Array.isArray(edges)) return 0
  return edges.filter((e) => e?.source === sourceId).length
}

/**
 * 校验非分支节点的多条出边
 * @returns {{ nodeId: string, field: string, message: string }[]}
 */
export function validateMultiOutgoingEdges(nodes, edges) {
  if (!Array.isArray(nodes) || !Array.isArray(edges)) return []

  const nodeMap = new Map(nodes.map((n) => [n.id, n]))
  const outCount = new Map()
  for (const edge of edges) {
    if (!edge?.source) continue
    outCount.set(edge.source, (outCount.get(edge.source) || 0) + 1)
  }

  const errors = []
  for (const [nodeId, count] of outCount.entries()) {
    if (count <= 1) continue
    const node = nodeMap.get(nodeId)
    const type = node?.type || ''
    if (isMultiOutEdgeAllowed(type)) continue
    const label = resolveNodeLabel(node)
    errors.push({
      nodeId,
      field: 'multiOutgoing',
      message: `「${label}」有 ${count} 条出边：${MULTI_OUT_EDGE_MESSAGE}`,
    })
  }
  return errors
}

/**
 * 新建连线后是否会违反多出边规则
 */
export function wouldViolateMultiOutgoingEdge(nodes, edges, connection) {
  if (!connection?.source) return false
  const node = nodes.find((n) => n.id === connection.source)
  if (isMultiOutEdgeAllowed(node?.type)) return false
  return countOutgoingEdges(edges, connection.source) >= 1
}

export function getMultiOutgoingEdgeHint() {
  return MULTI_OUT_EDGE_MESSAGE
}
