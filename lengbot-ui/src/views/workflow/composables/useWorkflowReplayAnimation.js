/** 与测试运行画布回放一致的节点动画间隔 */
export const REPLAY_NODE_START_MS = 700
export const REPLAY_NODE_COMPLETE_MS = 400

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 按 nodeEvents 顺序回放节点执行动画（与测试运行一致）
 * @param {Array} events workflow_node_start / workflow_node_complete
 * @param {object} options
 * @param {() => boolean} [options.isCancelled] 返回 true 时中止回放
 * @param {() => void} [options.onClear] 回放开始前清空节点状态
 * @param {(ev: object) => void} [options.onNodeStart] workflow_node_start
 * @param {(ev: object) => void} [options.onNodeComplete] workflow_node_complete
 * @param {number} [options.startDelayMs]
 * @param {number} [options.completeDelayMs]
 */
export async function replayWorkflowNodeEvents(
  events,
  {
    isCancelled = () => false,
    onClear,
    onNodeStart,
    onNodeComplete,
    startDelayMs = REPLAY_NODE_START_MS,
    completeDelayMs = REPLAY_NODE_COMPLETE_MS,
  } = {}
) {
  onClear?.()
  for (const ev of events || []) {
    if (isCancelled()) return
    if (ev.type === 'workflow_node_start' && ev.nodeId) {
      onNodeStart?.(ev)
      await sleep(startDelayMs)
      if (isCancelled()) return
    }
    if (ev.type === 'workflow_node_complete' && ev.nodeId) {
      onNodeComplete?.(ev)
      await sleep(completeDelayMs)
      if (isCancelled()) return
    }
  }
}
