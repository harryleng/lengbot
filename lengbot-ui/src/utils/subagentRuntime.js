/** 子智能体运行态终态集合：到达后不再被陈旧的 live 状态回退 */
export const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled', 'timeout'])

/** 状态进度权重：用于在 DB 与 live 之间取「更靠后」的一方 */
export const STATUS_WEIGHT = {
  pending: 0,
  cancel_requested: 1,
  running: 2,
  completed: 3,
  failed: 3,
  cancelled: 3,
  timeout: 3,
}

/**
 * 合并 DB run 与 live 状态：以状态进度更靠后的一方为准。
 * background 任务无实时 emitter，主 SSE 早关，live 会冻结在 pending/等待调度；
 * 此时 DB 轮询到的终态应覆盖陈旧 live，避免侧栏永远停在「等待调度」。
 * running 中的实时输出（liveOutput/progress_summary）仍取 live。
 */
export function pickFresher(dbRun, live) {
  if (!dbRun) return { ...live }
  if (!live) return { ...dbRun }
  const dbTerminal = TERMINAL_STATUSES.has(dbRun.status)
  const liveTerminal = TERMINAL_STATUSES.has(live.status)
  // DB 已终态而 live 未终态：以 DB 状态为准，保留 live 的实时输出
  if (dbTerminal && !liveTerminal) {
    return { ...live, ...dbRun, liveOutput: live.liveOutput }
  }
  // 两者都终态或都非终态：按进度权重取靠后的一方
  const dbWeight = STATUS_WEIGHT[dbRun.status] ?? 0
  const liveWeight = STATUS_WEIGHT[live.status] ?? 0
  return dbWeight > liveWeight ? { ...live, ...dbRun, liveOutput: live.liveOutput } : { ...dbRun, ...live }
}
