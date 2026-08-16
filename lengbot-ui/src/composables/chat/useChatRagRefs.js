import { ref, nextTick } from 'vue'

/**
 * RAG 参考文献展开态管理
 * @param {object} options
 * @param {import('vue').Ref<Array>} options.messages
 * @param {(msgIndex: number, expandEl?: HTMLElement) => void} options.scrollAfterExpand
 */
export function useChatRagRefs({ messages, scrollAfterExpand }) {
  // 用于存储每条消息的展开状态，key为消息索引，value为Set<refIndex>
  const expandedRefsMap = ref(new Map())
  // 用于存储每条消息的参考文献区域是否展开，key为消息索引，value为boolean
  const refsSectionExpandedMap = ref(new Map())

  /**
   * 判断某个引用是否展开
   */
  function isReferenceExpanded(msg, index) {
    const msgIndex = messages.value.indexOf(msg)
    const key = `${msgIndex}-${index}`
    return expandedRefsMap.value.has(key)
  }

  /**
   * 切换引用展开状态
   */
  function toggleReference(msg, index) {
    const msgIndex = messages.value.indexOf(msg)
    const key = `${msgIndex}-${index}`
    const newMap = new Map(expandedRefsMap.value)
    if (newMap.has(key)) {
      newMap.delete(key)
    } else {
      newMap.set(key, true)
    }
    expandedRefsMap.value = newMap
    nextTick(() => scrollAfterExpand(msgIndex))
  }

  /**
   * 判断参考文献区域是否展开（默认展开，记录的为收起状态）
   */
  function isRefsSectionExpanded(msg) {
    const msgIndex = messages.value.indexOf(msg)
    return !refsSectionExpandedMap.value.has(msgIndex)
  }

  /**
   * 切换参考文献区域展开状态
   */
  function toggleRefsSection(msg) {
    const msgIndex = messages.value.indexOf(msg)
    const newMap = new Map(refsSectionExpandedMap.value)
    if (newMap.has(msgIndex)) {
      newMap.delete(msgIndex)
    } else {
      newMap.set(msgIndex, true)
    }
    refsSectionExpandedMap.value = newMap
    nextTick(() => scrollAfterExpand(msgIndex))
  }

  function toggleReasoningExpand(index) {
    const msg = messages.value[index]
    if (!msg) return
    msg._reasoningExpanded = !msg._reasoningExpanded
    nextTick(() => scrollAfterExpand(index))
  }

  return {
    expandedRefsMap,
    refsSectionExpandedMap,
    isReferenceExpanded,
    toggleReference,
    isRefsSectionExpanded,
    toggleRefsSection,
    toggleReasoningExpand,
  }
}
