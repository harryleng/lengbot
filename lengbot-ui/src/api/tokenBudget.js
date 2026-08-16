import request from '../utils/request'

/** 获取 Token 限额配置 */
export function getTokenBudgetConfig() {
  return request.get('/system-config/token-budget/config')
}

/** 更新 Token 限额配置 */
export function updateTokenBudgetConfig(data) {
  return request.put('/system-config/token-budget/config', data)
}

/** 获取全局 Token 使用统计 */
export function getTokenBudgetStats() {
  return request.get('/system-config/token-budget/stats')
}

/**
 * 获取用户 Token 消耗排行
 * @param {number|object} [params=20] 传 number 时按旧签名当 limit 兼容；传 object 时取 { range, limit }
 * @param {string} [params.range=today] 时间范围：today / 7d / 14d / 30d
 * @param {number} [params.limit=20] 返回条数
 */
export function getTokenBudgetRanking(params = 20) {
  const { range = 'today', limit = 20 } = typeof params === 'number' ? { limit: params } : params
  return request.get('/system-config/token-budget/ranking', { params: { range, limit } })
}

/** 获取本人 Token 用量（今日 + 近 7 天累计） */
export function getMyTokenUsage() {
  return request.get('/system-config/token-budget/my-usage')
}
