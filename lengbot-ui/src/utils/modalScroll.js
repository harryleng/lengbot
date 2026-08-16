/**
 * 弹窗滚动条动态监听：监听 .ant-modal-body 的 scrollHeight/clientHeight，
 * 滚动条出现时加 .has-overflow class，消失时移除。
 *
 * 配合 modal-scroll.css：
 * - .ant-modal-body .dialog-scroll-body { padding-right: 0 } —— 默认无间距
 * - .ant-modal-body.has-overflow .dialog-scroll-body { padding-right: 8px } —— 有滚动条时
 *
 * 实现"无滚动条时字段贴边、有滚动条时字段与滚动条间有呼吸距离"的动态逻辑，
 * 避免 scrollbar-gutter: stable 在无滚动条时也强制预留空间的视觉问题。
 *
 * 性能注意：
 * - MutationObserver 只监听 childList/subtree（结构变化），不监听 attributes/characterData
 *   否则 antd 的 hover/focus class 切换、input 每次输入都会触发，叠加上 scrollHeight 读取
 *   会造成 layout thrashing 把主线程打满（弹窗打开时卡死）
 * - ResizeObserver + MutationObserver 的回调都用 rAF 节流，同帧内多次触发合并为一次布局读取
 * - 弹窗关闭时 disconnect observer，避免泄漏
 */

const observedBodies = new WeakMap()

// 同帧内多次触发的 checkOverflow 合并为一次布局读取，避免 layout thrashing
function scheduleCheck(body) {
  const entry = observedBodies.get(body)
  if (!entry || entry.pending) return
  entry.pending = true
  requestAnimationFrame(() => {
    entry.pending = false
    checkOverflow(body)
  })
}

function checkOverflow(body) {
  if (body.scrollHeight > body.clientHeight) {
    body.classList.add('has-overflow')
  } else {
    body.classList.remove('has-overflow')
  }
}

function observeModalBody(body) {
  if (observedBodies.has(body)) return

  // entry 持有 observer 引用 + pending 标志，关闭时 disconnect 并释放
  const entry = { ro: null, mo: null, pending: false }
  observedBodies.set(body, entry)

  // 首次检测延迟到下一帧，等 antd modal 打开动画把 body 撑到最终尺寸再读
  // 否则动画第一帧 clientHeight 还是 0，会误判为有溢出
  requestAnimationFrame(() => checkOverflow(body))

  const ro = new ResizeObserver(() => scheduleCheck(body))
  ro.observe(body)
  entry.ro = ro

  // 只监听结构变化（增删节点），attributes/characterData 会把每次输入/hover 都放大成布局读取
  const mo = new MutationObserver(() => scheduleCheck(body))
  mo.observe(body, { childList: true, subtree: true })
  entry.mo = mo
}

const bodyInsertObserver = new MutationObserver((mutations) => {
  for (const mutation of mutations) {
    for (const node of mutation.addedNodes) {
      if (node.nodeType !== 1) continue
      if (node.classList?.contains('ant-modal-body')) {
        observeModalBody(node)
      } else {
        node.querySelectorAll?.('.ant-modal-body').forEach(observeModalBody)
      }
    }
    // 弹窗关闭时 antd 会把 .ant-modal-body 从 DOM 移除，此时 disconnect 对应 observer
    for (const node of mutation.removedNodes) {
      if (node.nodeType !== 1) continue
      const bodies = node.classList?.contains('ant-modal-body')
        ? [node]
        : Array.from(node.querySelectorAll?.('.ant-modal-body') || [])
      for (const body of bodies) {
        const entry = observedBodies.get(body)
        if (entry) {
          entry.ro?.disconnect()
          entry.mo?.disconnect()
          observedBodies.delete(body)
        }
      }
    }
  }
})

export function installModalScrollObserver() {
  bodyInsertObserver.observe(document.body, { childList: true, subtree: true })
  document.querySelectorAll('.ant-modal-body').forEach(observeModalBody)
}
