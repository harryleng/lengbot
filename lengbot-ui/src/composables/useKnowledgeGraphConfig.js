/**
 * 知识图谱共享配置与数据格式化
 *
 * StandaloneGraph.vue（独立页）与 KnowledgeGraphTab.vue（知识库详情内嵌）
 * 共用同一套色板、布局、formatGraphData，抽出集中维护避免双份漂移。
 */

export const COLOR_PALETTE = [
  '#60a5fa',
  '#34d399',
  '#f59e0b',
  '#f472b6',
  '#22d3ee',
  '#a78bfa',
  '#f97316',
  '#4ade80',
  '#f43f5e',
  '#2dd4bf',
]

export const LAYOUT_CONFIG = {
  type: 'd3-force',
  preventOverlap: true,
  alphaDecay: 0.1,
  alphaMin: 0.01,
  velocityDecay: 0.6,
  iterations: 150,
  force: {
    center: { x: 0.5, y: 0.5, strength: 0.1 },
    charge: { strength: -400, distanceMax: 600 },
    link: { distance: 100, strength: 0.8 },
  },
  collide: { radius: 40, strength: 0.8, iterations: 3 },
}

/**
 * 将后端图谱数据格式化为 G6 所需结构
 * - 节点：以 elementId 为 id，degree 由边统计得到
 * - 边：以 elementId 为 id；同 source-target 的平行边交替向两侧弯曲
 */
export function formatGraphData(data) {
  if (!data) return { nodes: [], edges: [] }

  const degrees = new Map()
  for (const n of data.nodes) {
    degrees.set(String(n.elementId), 0)
  }
  for (const e of data.edges) {
    const s = String(e.startNodeElementId)
    const t = String(e.endNodeElementId)
    degrees.set(s, (degrees.get(s) || 0) + 1)
    degrees.set(t, (degrees.get(t) || 0) + 1)
  }

  const nodes = (data.nodes || []).map((n) => ({
    id: String(n.elementId),
    data: {
      label: n.name || String(n.elementId),
      degree: degrees.get(String(n.elementId)) || 0,
      original: n,
    },
  }))

  const edges = (data.edges || []).map((e, idx) => ({
    id: e.elementId ? String(e.elementId) : `edge-${idx}`,
    source: String(e.startNodeElementId),
    target: String(e.endNodeElementId),
    data: {
      label: e.relationType || '',
      original: e,
    },
  }))

  // 平行边偏移：同 source-target 的边交替向两侧弯曲
  const edgeGroups = {}
  edges.forEach((e) => {
    const key = [e.source, e.target].sort().join('->')
    if (!edgeGroups[key]) edgeGroups[key] = []
    edgeGroups[key].push(e)
  })
  const BASE_OFFSET = 30
  Object.values(edgeGroups).forEach((group) => {
    if (group.length <= 1) return
    group.forEach((edge, i) => {
      const sign = i % 2 === 0 ? 1 : -1
      const magnitude = Math.ceil(i / 2)
      edge.data.curveOffset = sign * magnitude * BASE_OFFSET
    })
  })

  return { nodes, edges }
}
