/**
 * SSE 对话流式调用 composable
 * <p>封装 chatStream 的常用样板：AbortController 管理、组件卸载自动中止、
 * 重连状态回调。Chat.vue 因有大量自定义状态（断点续传、todos、附件、mention）仍
 * 直接调 chatStream；新业务请使用本 composable，避免重复样板。</p>
 *
 * @param {Object} options
 * @param {Object} [options.data] 固定请求体（每次 start 时可被 payload 覆盖）
 * @param {Object} [options.handlers] 静态回调，可与 start(payload, handlers) 合并
 * @returns {{
 *   start: (payload?: Object, handlers?: Object) => Promise<void>,
 *   stop: () => void,
 *   isStreaming: import('vue').Ref<boolean>,
 * }}
 */
import { onBeforeUnmount, ref } from 'vue'
import { chatStream } from '@/api/chat'

export function useSseChat(options = {}) {
  const { data: baseData = {}, handlers: baseHandlers = {} } = options
  const controller = ref(null)
  const isStreaming = ref(false)

  async function start(payload = {}, handlers = {}) {
    // 防止并发：上一次未结束先中止
    if (controller.value) {
      controller.value.abort()
      controller.value = null
    }
    const ac = new AbortController()
    controller.value = ac
    isStreaming.value = true
    try {
      await chatStream({ ...baseData, ...payload }, { ...baseHandlers, ...handlers }, ac.signal)
    } finally {
      isStreaming.value = false
      controller.value = null
    }
  }

  function stop() {
    if (controller.value) {
      controller.value.abort()
      controller.value = null
      isStreaming.value = false
    }
  }

  onBeforeUnmount(() => stop())

  return { start, stop, isStreaming }
}
