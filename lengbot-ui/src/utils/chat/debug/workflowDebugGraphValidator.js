const GROUP_TYPES = new Set(['loop', 'batch'])
const GROUP_BUILTIN_TYPES = new Set(['loop_start', 'loop_end', 'batch_start', 'batch_end'])
const GROUP_BUILTIN_BY_GROUP = {
  loop: ['loop_start', 'loop_end'],
  batch: ['batch_start', 'batch_end'],
}

function getParentId(node) {
  return node?.parentNode || node?.parentId || null
}

function parseSize(value, fallback) {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const n = Number.parseFloat(value)
    if (Number.isFinite(n)) return n
  }
  return fallback
}

function addIssue(issues, severity, code, message, nodeId) {
  issues.push({ severity, code, message, nodeId })
}

function buildGraphMaps(nodes, edges) {
  const nodeMap = new Map((nodes || []).map((node) => [node.id, node]))
  const outgoing = new Map()
  const incoming = new Map()

  for (const node of nodes || []) {
    outgoing.set(node.id, [])
    incoming.set(node.id, [])
  }

  for (const edge of edges || []) {
    if (!edge?.source || !edge?.target) continue
    if (!outgoing.has(edge.source)) outgoing.set(edge.source, [])
    if (!incoming.has(edge.target)) incoming.set(edge.target, [])
    outgoing.get(edge.source).push(edge.target)
    incoming.get(edge.target).push(edge.source)
  }

  return { nodeMap, outgoing, incoming }
}

function walkFrom(startId, adjacency) {
  const visited = new Set()
  const queue = startId ? [startId] : []

  while (queue.length) {
    const current = queue.shift()
    if (!current || visited.has(current)) continue
    visited.add(current)
    for (const next of adjacency.get(current) || []) {
      if (!visited.has(next)) queue.push(next)
    }
  }

  return visited
}

function getTopology(nodes, edges) {
  const ids = new Set((nodes || []).map((node) => node.id))
  const indegree = new Map()
  const outgoing = new Map()

  ids.forEach((id) => {
    indegree.set(id, 0)
    outgoing.set(id, [])
  })

  for (const edge of edges || []) {
    if (!ids.has(edge.source) || !ids.has(edge.target)) continue
    outgoing.get(edge.source).push(edge.target)
    indegree.set(edge.target, (indegree.get(edge.target) || 0) + 1)
  }

  const queue = []
  for (const [id, count] of indegree.entries()) {
    if (count === 0) queue.push(id)
  }

  const order = []
  while (queue.length) {
    const id = queue.shift()
    order.push(id)
    for (const next of outgoing.get(id) || []) {
      const count = (indegree.get(next) || 0) - 1
      indegree.set(next, count)
      if (count === 0) queue.push(next)
    }
  }

  return {
    order,
    hasCycle: order.length !== ids.size,
  }
}

function validateNodesAndEdges(nodes, edges, issues) {
  const { nodeMap, outgoing, incoming } = buildGraphMaps(nodes, edges)
  const topLevelStarts = (nodes || []).filter((node) => node.type === 'start' && !getParentId(node))
  const topLevelEnds = (nodes || []).filter((node) => node.type === 'end' && !getParentId(node))

  if (topLevelStarts.length !== 1) {
    addIssue(issues, 'error', 'global-start-count', '全局工作流必须且只能有一个顶层 start 节点')
  }
  if (topLevelEnds.length !== 1) {
    addIssue(issues, 'error', 'global-end-count', '全局工作流必须且只能有一个顶层 end 节点')
  }

  const startId = topLevelStarts[0]?.id
  const endId = topLevelEnds[0]?.id

  if (startId && (incoming.get(startId)?.length || 0) > 0) {
    addIssue(issues, 'error', 'start-incoming-edge', '全局 start 节点不能有入边', startId)
  }
  if (endId && (outgoing.get(endId)?.length || 0) > 0) {
    addIssue(issues, 'error', 'end-outgoing-edge', '全局 end 节点不能有出边', endId)
  }

  for (const edge of edges || []) {
    if (!nodeMap.has(edge.source)) {
      addIssue(issues, 'error', 'edge-source-missing', `边 ${edge.id || ''} 的 source 节点不存在`)
    }
    if (!nodeMap.has(edge.target)) {
      addIssue(issues, 'error', 'edge-target-missing', `边 ${edge.id || ''} 的 target 节点不存在`)
    }
  }

  const topology = getTopology(nodes, edges)
  if (topology.hasCycle) {
    addIssue(issues, 'error', 'graph-cycle', '工作流主图必须是 DAG，当前存在环路')
  }

  if (!topology.hasCycle && startId && topology.order[0] !== startId) {
    addIssue(issues, 'error', 'start-topology-first', '全局 start 节点必须位于拓扑顺序第一位', startId)
  }
  if (!topology.hasCycle && endId && topology.order[topology.order.length - 1] !== endId) {
    addIssue(issues, 'error', 'end-topology-last', '全局 end 节点必须位于拓扑顺序最后一位', endId)
  }

  if (startId) {
    const reachableFromStart = walkFrom(startId, outgoing)
    for (const node of nodes || []) {
      if (!isNodeCoveredByReachability(node, nodes, reachableFromStart)) {
        addIssue(
          issues,
          'error',
          'node-unreachable-from-start',
          `节点 ${node.data?.label || node.id} 无法从 start 到达`,
          node.id
        )
      }
    }
  }

  if (endId) {
    const canReachEnd = walkFrom(endId, incoming)
    for (const node of nodes || []) {
      if (!isNodeCoveredByReachability(node, nodes, canReachEnd)) {
        addIssue(issues, 'error', 'node-cannot-reach-end', `节点 ${node.data?.label || node.id} 无法到达 end`, node.id)
      }
    }
  }
}

function isNodeCoveredByReachability(node, nodes, visited) {
  if (visited.has(node.id)) return true
  if (!GROUP_TYPES.has(node.type)) return false
  return (nodes || []).some((child) => getParentId(child) === node.id && visited.has(child.id))
}

function validateGroupNodes(nodes, issues) {
  for (const group of nodes || []) {
    if (!GROUP_TYPES.has(group.type)) continue
    const children = (nodes || []).filter((node) => getParentId(node) === group.id)
    const requiredTypes = GROUP_BUILTIN_BY_GROUP[group.type] || []

    for (const requiredType of requiredTypes) {
      if (!children.some((child) => child.type === requiredType)) {
        addIssue(
          issues,
          'error',
          'group-builtin-missing',
          `${group.data?.label || group.id} 缺少内置节点 ${requiredType}`,
          group.id
        )
      }
    }

    const width = parseSize(group.style?.width, 560)
    const height = parseSize(group.style?.height, 380)

    for (const child of children) {
      if (!child.parentNode) {
        addIssue(issues, 'error', 'group-child-parent-missing', `容器子节点 ${child.id} 缺少 parentNode`, child.id)
      }
      if (child.extent !== 'parent') {
        addIssue(issues, 'warning', 'group-child-extent', `容器子节点 ${child.id} 建议设置 extent=parent`, child.id)
      }
      const x = Number(child.position?.x)
      const y = Number(child.position?.y)
      if (!Number.isFinite(x) || !Number.isFinite(y) || x < 0 || y < 44 || x > width - 40 || y > height - 40) {
        addIssue(
          issues,
          'error',
          'group-child-out-of-bounds',
          `容器子节点 ${child.data?.label || child.id} 坐标不在父节点范围内`,
          child.id
        )
      }
    }

    const businessChildren = children.filter((child) => !GROUP_BUILTIN_TYPES.has(child.type))
    addIssue(
      issues,
      'info',
      'group-business-child-count',
      `${group.data?.label || group.id} 业务子节点 ${businessChildren.length} 个，已排除内置开始/结束节点`,
      group.id
    )
  }

  for (const node of nodes || []) {
    const parentId = getParentId(node)
    if (!parentId) continue
    const parent = nodes.find((item) => item.id === parentId)
    if (!parent) {
      addIssue(issues, 'error', 'parent-node-missing', `节点 ${node.id} 的父节点 ${parentId} 不存在`, node.id)
    }
  }
}

function validateEvents(nodes, nodeEvents, issues) {
  const nodeIds = new Set((nodes || []).map((node) => node.id))
  const groupIds = new Set((nodes || []).filter((node) => GROUP_TYPES.has(node.type)).map((node) => node.id))

  for (const event of nodeEvents || []) {
    if (event.nodeId && !nodeIds.has(event.nodeId)) {
      addIssue(issues, 'error', 'event-node-missing', `事件 ${event.type} 指向不存在的节点 ${event.nodeId}`)
    }
    if (event.parentNodeId && !groupIds.has(event.parentNodeId)) {
      addIssue(issues, 'error', 'event-parent-missing', `事件 ${event.type} 指向不存在的父节点 ${event.parentNodeId}`)
    }
  }
}

export function validateWorkflowDebugGraph(graph = {}, nodeEvents = []) {
  const nodes = Array.isArray(graph.nodes) ? graph.nodes : []
  const edges = Array.isArray(graph.edges) ? graph.edges : []
  const issues = []

  if (!nodes.length) {
    addIssue(issues, 'error', 'empty-graph', '工作流图不能为空')
  } else {
    validateNodesAndEdges(nodes, edges, issues)
    validateGroupNodes(nodes, issues)
    validateEvents(nodes, nodeEvents, issues)
  }

  const errors = issues.filter((issue) => issue.severity === 'error')
  const warnings = issues.filter((issue) => issue.severity === 'warning')
  const infos = issues.filter((issue) => issue.severity === 'info')

  return {
    valid: errors.length === 0,
    issues,
    errors,
    warnings,
    infos,
  }
}
