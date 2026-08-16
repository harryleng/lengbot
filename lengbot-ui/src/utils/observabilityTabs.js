/** 可观测性页 Tab 标识 */
export const OBSERVABILITY_TABS = ['chat', 'workflow', 'tool']

/**
 * 规范化 URL 中的 tab 参数
 * @param {string|undefined|null} tab
 * @param {string} fallback 无效时的默认值
 */
export function normalizeObservabilityTab(tab, fallback = 'chat') {
  return OBSERVABILITY_TABS.includes(tab) ? tab : fallback
}

/**
 * 可观测性列表页路由（带 tab）
 * @param {string} tab
 */
export function observabilityListRoute(tab = 'chat') {
  return {
    path: '/app/observability',
    query: { tab: normalizeObservabilityTab(tab) },
  }
}
