/**
 * 上游变量树：基于 DAG 收集前序节点输出字段
 */
import { mergeNodeOutputFields } from './nodeIoContract.js'

/** 递归收集 nodeId 的所有上游节点 ID */
export function getNeighborNodeIds(nodeId, edges) {
  const preds = new Set()
  function walk(targetId) {
    for (const e of edges || []) {
      if (e.target === targetId && !preds.has(e.source)) {
        preds.add(e.source)
        walk(e.source)
      }
    }
  }
  walk(nodeId)
  return [...preds]
}

/**
 * 构建上游变量菜单项（供 VariablePickerInput 使用）
 */
export function buildUpstreamVariableItems(nodeId, nodes, edges) {
  if (!nodeId || !nodes?.length) return []
  const neighborIds = getNeighborNodeIds(nodeId, edges)
  const items = []
  for (const id of neighborIds) {
    const node = nodes.find((n) => n.id === id)
    if (!node) continue
    const label = node.data?.label || node.type
    const fields = mergeNodeOutputFields(node)
    for (const f of fields) {
      items.push({
        key: `${id}.${f.key}`,
        nodeId: id,
        field: f.key,
        label: `${label} · ${f.label || f.key}`,
        desc: f.desc || '',
        example: `{{${id}.${f.key}}}`,
      })
    }
  }
  return items
}
