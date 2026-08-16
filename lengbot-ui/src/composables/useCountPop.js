import { ref, watch } from 'vue'

/**
 * 数字变化反馈动画
 *
 * 监听 source（ref/computed/getter）的数值变化，短暂返回 active class
 * 触发 lb-count-pop CSS 动画（缩放+短暂着色）。
 * 用于统计卡片、徽章数字等场景，让用户直观感知到数值更新。
 *
 * 用法：
 *
 * <span :class="countPopClass">{{ count }}</span>
 *
 * const count = ref(0)
 * const countPopClass = useCountPop(count)
 *
 * 或传入 getter：
 *
 * const countPopClass = useCountPop(() => store.active)
 *
 * 只在 number → number 变化时触发：
 * - 字符串占位（如 '-'）→ number：不触发（避免骨架切真实数据时集体抖动）
 * - number → number（值不同）：触发
 * - 任意值 → 字符串占位：不触发
 */
export function useCountPop(source, options = {}) {
  const duration = options.duration ?? 400
  const classRef = ref('')
  let timer = null

  function trigger() {
    if (timer) {
      clearTimeout(timer)
      classRef.value = ''
    }
    // requestAnimationFrame 保证 class 移除后再加，触发动画重启
    requestAnimationFrame(() => {
      classRef.value = 'lb-count-pop'
      timer = setTimeout(() => {
        classRef.value = ''
        timer = null
      }, duration)
    })
  }

  watch(
    source,
    (next, prev) => {
      if (typeof next !== 'number' || typeof prev !== 'number') return
      if (next === prev) return
      trigger()
    },
    { flush: 'post' }
  )

  return classRef
}
