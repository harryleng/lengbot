import { ref, watch, nextTick } from 'vue'
import { useVirtualizer } from '@tanstack/vue-virtual'

/**
 * 虚拟滚动与消息列表滚动策略
 * @param {object} deps
 * @param {import('vue').Ref<Array>} deps.messages
 * @param {import('vue').Ref<HTMLElement|null>} deps.messagesRef
 * @param {import('vue').Ref<boolean>} deps.streaming
 * @param {(msg: object) => Array} deps.getMsgRagRefs
 * @param {import('vue').Ref<Map>} deps.refsSectionExpandedMap
 */
export function useChatScroll({ messages, messagesRef, streaming, getMsgRagRefs, refsSectionExpandedMap }) {
  const isNearBottom = ref(true)
  /** 用户主动上划，暂停流式自动滚动 */
  const userScrolledUp = ref(false)

  const virtualizer = useVirtualizer({
    count: messages.value.length,
    getScrollElement: () => messagesRef.value,
    estimateSize: (index) => {
      const msg = messages.value[index]
      if (!msg) return 80
      if (msg.role === 'user') return msg._replyToMessageId ? 90 : 60
      const len = msg.content?.length || 0
      let size = Math.max(80, Math.min(600, Math.ceil(len / 40) * 22 + 60))
      // 工具事件额外占高：每个 tool_call/tool_result 约 32px，tool_status 约 24px
      const toolCount = (msg._toolEvents || []).filter((e) => e.type === 'tool_call' || e.type === 'tool_result').length
      const statusCount = (msg._toolEvents || []).filter((e) => e.type === 'tool_status').length
      if (toolCount > 0 || statusCount > 0) {
        size += 60 + toolCount * 32 + statusCount * 24
      }
      // 参考文献额外占高（默认展开，记录的为收起状态）
      const refCount = getMsgRagRefs(msg).length
      if (refCount > 0 && !refsSectionExpandedMap.value.has(index)) size += 40 + refCount * 36
      else if (refCount > 0) size += 32
      return Math.min(size, 2000)
    },
    overscan: 5,
  })

  // TanStack Virtual 默认会为视口上方的行高变化补偿 scrollTop，视觉上像内容向上展开。
  // 聊天中的折叠区统一保留标题位置，让内容自然向下撑开。
  virtualizer.value.shouldAdjustScrollPositionOnItemSizeChange = () => false

  watch(
    () => messages.value.length,
    (newLen) => {
      virtualizer.value.setOptions({
        ...virtualizer.value.options,
        count: newLen,
      })
    }
  )

  function handleScroll() {
    const el = messagesRef.value
    if (!el) return
    const threshold = 150
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < threshold
    isNearBottom.value = nearBottom
    // 流式输出期间：上划暂停自动滚动，回到底部恢复
    if (streaming.value) {
      userScrolledUp.value = !nearBottom
    }
  }

  function resolveMeasureRow(evt) {
    const t = evt?.target
    if (!t) return null
    if (t.dataset?.index != null) return t
    return t.closest?.('[data-index]') ?? null
  }

  /**
   * 内联块（SubAgent / 工具 / Skill / 参考文献等）高度变化：
   * 仅当整行位于视口上方时补偿 scrollTop（滚动锚定），
   * 展开可见面板时不补偿，让内容自然向下撑开、点击的标题保持不动。
   */
  function onCapabilityHeightChange(evt) {
    const rowEl = resolveMeasureRow(evt)
    const container = messagesRef.value
    if (!rowEl || !container) return

    const scrollTopBefore = container.scrollTop
    const heightBefore = rowEl.getBoundingClientRect().height
    const containerRect = container.getBoundingClientRect()

    container.style.overflowAnchor = 'none'
    virtualizer.value.measureElement(rowEl)

    nextTick(() => {
      requestAnimationFrame(() => {
        const heightAfter = rowEl.getBoundingClientRect().height
        const delta = heightAfter - heightBefore

        // 仅当整行已滚出视口上方时才补偿，避免展开可见面板导致视角上移
        if (Math.abs(delta) > 0.5 && rowEl.getBoundingClientRect().bottom < containerRect.top) {
          container.scrollTop = scrollTopBefore + delta
        }

        container.style.overflowAnchor = ''

        if (!evt?.preserveViewport && streaming.value && isNearBottom.value && !userScrolledUp.value) {
          scrollToBottom()
        }
      })
    })
  }

  function scrollToBottom() {
    if (!isNearBottom.value || userScrolledUp.value) return
    const el = messagesRef.value
    if (!el) return
    nextTick(() => {
      el.scrollTop = el.scrollHeight
    })
  }

  /** 流式输出期间，自动滚动深度思考面板到底部 */
  function scrollReasoningToBottom() {
    if (userScrolledUp.value) return
    nextTick(() => {
      const panels = messagesRef.value?.querySelectorAll('.reasoning-content')
      if (panels?.length) {
        const last = panels[panels.length - 1]
        last.scrollTop = last.scrollHeight
      }
    })
  }

  /** 强制滚动到底部（切换会话后使用，不检查 isNearBottom）
   *  多次延迟滚动，确保工具面板、参考文献等延迟渲染内容撑开后仍能定位到底部
   */
  function forceScrollToBottom() {
    const el = messagesRef.value
    if (!el) return
    const doScroll = () => {
      el.scrollTop = el.scrollHeight
    }
    nextTick(() => {
      doScroll()
      requestAnimationFrame(doScroll)
      setTimeout(doScroll, 100)
      setTimeout(doScroll, 300)
      setTimeout(doScroll, 600)
    })
  }

  /**
   * 消息内联块展开/收起后触发布局重测（不再把目标滚进视口，避免视角跳动）
   */
  function scrollAfterExpand(msgIndex, expandEl) {
    const container = messagesRef.value
    if (!container) return
    const rowEl = expandEl?.closest?.('[data-index]') ?? container.querySelector(`[data-index="${msgIndex}"]`)
    if (rowEl) onCapabilityHeightChange({ target: rowEl })
  }

  /**
   * 滚动事件处理器：距离检测 + 触顶加载更早消息
   * @param {object} opts
   * @param {() => Promise<void>} opts.loadOlderMessages
   * @param {import('vue').Ref<boolean>} opts.hasMoreMessages
   * @param {import('vue').Ref<boolean>} opts.loadingOlder
   */
  function createScrollHandler({ loadOlderMessages, hasMoreMessages, loadingOlder }) {
    return () => {
      handleScroll()
      const container = messagesRef.value
      if (container && container.scrollTop < 50 && hasMoreMessages.value && !loadingOlder.value && !streaming.value) {
        loadOlderMessages()
      }
    }
  }

  return {
    isNearBottom,
    userScrolledUp,
    virtualizer,
    handleScroll,
    onCapabilityHeightChange,
    scrollToBottom,
    scrollReasoningToBottom,
    forceScrollToBottom,
    scrollAfterExpand,
    createScrollHandler,
  }
}
