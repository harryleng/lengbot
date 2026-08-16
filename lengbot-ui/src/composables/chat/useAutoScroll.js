/**
 * 滚动工具 composable：提供主动调用方法，不自动跟随
 *
 * 设计：
 * - 不在内容变化时自动滚到底部（避免初始填充触发跳动）
 * - 调用方根据业务场景自行决定何时调用 scrollIntoView / scrollToBottom
 *
 * 用法：
 *   const scrollRef = ref(null)
 *   const { scrollIntoView, scrollToBottom } = useAutoScroll(scrollRef)
 *   <div ref="scrollRef">...</div>
 */

import { nextTick } from 'vue'

export function useAutoScroll(containerRef) {
  // 程序滚动期间触发的 scroll 事件不算用户操作
  let programmaticScroll = false

  async function scrollToBottom() {
    const el = containerRef.value
    if (!el) return
    await nextTick()
    programmaticScroll = true
    el.scrollTop = el.scrollHeight
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        programmaticScroll = false
      })
    })
  }

  /**
   * 滚动到容器内指定 selector 的元素，使其顶部对齐容器顶部
   * 用于"打开弹窗时跳过任务信息块，定位到 AI 输出开始"等场景
   */
  async function scrollIntoView(selector) {
    const el = containerRef.value
    if (!el || !selector) return
    await nextTick()
    const target = el.querySelector(selector)
    if (!target) return
    programmaticScroll = true
    const offset = target.getBoundingClientRect().top - el.getBoundingClientRect().top
    el.scrollTop += offset
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        programmaticScroll = false
      })
    })
  }

  function onScroll() {
    // 占位：当前无自动跟随逻辑，保留接口供未来扩展
    if (programmaticScroll) return
  }

  return {
    onScroll,
    scrollToBottom,
    scrollIntoView,
  }
}
