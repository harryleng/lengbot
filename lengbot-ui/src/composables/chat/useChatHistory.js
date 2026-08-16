import { ref, nextTick } from 'vue'
import { message } from 'ant-design-vue'
import { getSessionMessages, getSession } from '../../api/chatSession'

/**
 * 会话历史消息加载：首屏 loadHistory + 分页 loadOlderMessages
 * @param {object} deps
 */
export function useChatHistory(deps) {
  const {
    sessionId,
    messages,
    streaming,
    virtualizer,
    messagesRef,
    isNearBottom,
    userScrolledUp,
    forceScrollToBottom,
    selectedAgentId,
    currentAgent,
    loadAgentConfigVersions,
    loadCurrentAgent,
    loadBatchFeedbacks,
    parseMessage,
    enrichMessagesAttachments,
    isAskUserUnanswered,
    showAskUserModal,
    cancelReply,
    loadingHistory,
    lastReplyElapsed,
    sessionTokenCount,
    sessionTitle,
    input,
    inputHistory,
    historyIndex,
    pendingAttachments,
    fileDrawerLoadedOnce,
    sessionFilePreviewTarget,
    sessionFilePreviewOpen,
    fileStats,
    onSessionMissing,
  } = deps

  const messagePage = ref(1)
  const hasMoreMessages = ref(false)
  const loadingOlder = ref(false)
  const initialLoadDone = ref(false)
  /** 每页加载消息条数：首次进入/切会话时只加载最近 N 条，减少首屏等待；
   *  用户上滚触顶时再加载下一页（loadOlderMessages） */
  const MESSAGE_PAGE_SIZE = 5
  /** 切换会话时 agent/版本加载中 */
  const switchingSession = ref(false)
  /** 竞态保护：每次 loadHistory 递增，过期请求不写入状态 */
  let loadHistoryRequestId = 0
  /** 流式进行中触发的 loadHistory 请求，待 streaming 结束后补执行 */
  let pendingHistoryReload = false

  function isSessionMissingError(error) {
    const text = String(error?.message || '')
    return text.includes('会话不存在') || text.includes('资源不存在')
  }

  function resetMissingSessionState() {
    messages.value = []
    hasMoreMessages.value = false
    loadingOlder.value = false
    messagePage.value = 1
    selectedAgentId.value = null
    currentAgent.value = null
    lastReplyElapsed.value = null
    sessionTokenCount.value = 0
    sessionTitle.value = ''
    pendingAttachments.value = []
    switchingSession.value = false
    message.warning('会话不存在或已删除')
    onSessionMissing?.()
  }

  async function loadHistory() {
    // 流式对话进行中不加载历史，避免替换 messages 数组破坏 stream 闭包引用
    if (streaming.value) {
      pendingHistoryReload = true
      return
    }
    pendingHistoryReload = false
    const reqId = ++loadHistoryRequestId

    if (!sessionId.value) {
      messages.value = []
      hasMoreMessages.value = false
      initialLoadDone.value = true
      loadingHistory.value = false
      loadingOlder.value = false
      messagePage.value = 1
      selectedAgentId.value = null
      currentAgent.value = null
      lastReplyElapsed.value = null
      sessionTokenCount.value = 0
      sessionTitle.value = ''
      switchingSession.value = false
      return
    }
    // 竞态保护：递增请求 ID
    // 切换对话时显示加载态；保留当前 messages 直到新数据返回，避免列表闪空
    initialLoadDone.value = false
    lastReplyElapsed.value = null
    fileDrawerLoadedOnce.value = false
    sessionFilePreviewTarget.value = null
    sessionFilePreviewOpen.value = false
    fileStats.total = 0
    fileStats.userUpload = 0
    fileStats.aiGenerated = 0
    input.value = ''
    inputHistory.value = []
    historyIndex.value = -1
    pendingAttachments.value = []
    cancelReply()
    loadingHistory.value = true
    switchingSession.value = true
    messagePage.value = 1
    try {
      // 并行加载消息（第1页）和会话详情
      const [msgRes, sessionRes] = await Promise.all([
        getSessionMessages(sessionId.value, { pageNum: 1, pageSize: MESSAGE_PAGE_SIZE }),
        getSession(sessionId.value, { silent: true }),
      ])
      // 请求已过期，丢弃结果
      if (reqId !== loadHistoryRequestId) return

      const session = sessionRes.data
      if (!session) {
        resetMissingSessionState()
        return
      }

      const records = msgRes.data?.records || []
      // API 按创建时间倒序返回，前端正序显示（旧→新）
      const parsed = records.reverse().map((m) => parseMessage(m))
      await enrichMessagesAttachments(parsed)
      if (reqId !== loadHistoryRequestId) return
      messages.value = parsed
      hasMoreMessages.value = records.length === MESSAGE_PAGE_SIZE

      // 批量加载消息反馈状态
      loadBatchFeedbacks(parsed)

      // 从会话中恢复 agentId 和 agentVersionId
      sessionTitle.value = session?.title || '新对话'
      sessionTokenCount.value = session?.totalTokens || 0
      if (session?.agentId) {
        selectedAgentId.value = session.agentId
        // 先加载版本列表（传入会话保存的版本 ID），再加载 agent 详情
        const versionDeleted = await loadAgentConfigVersions(session.agentId, session.agentVersionId)
        if (versionDeleted) {
          message.warning('当前对话Agent版本可能已被删除，已切换到草稿版本，你可以重新选择Agent版本')
        }
        if (reqId !== loadHistoryRequestId) return
        await loadCurrentAgent(session.agentId)
        if (reqId !== loadHistoryRequestId) return
      }
      isNearBottom.value = true
      userScrolledUp.value = false
      forceScrollToBottom()

      // 历史消息中自动弹出未回答的 ask_user 弹窗
      nextTick(() => {
        for (let i = messages.value.length - 1; i >= 0; i--) {
          if (messages.value[i].role === 'assistant' && isAskUserUnanswered(i)) {
            showAskUserModal(i)
            break
          }
        }
      })
    } catch (e) {
      if (reqId !== loadHistoryRequestId) return
      messages.value = []
      if (isSessionMissingError(e)) {
        resetMissingSessionState()
      }
    } finally {
      initialLoadDone.value = true
      if (reqId === loadHistoryRequestId) {
        loadingHistory.value = false
        switchingSession.value = false
        // 强制虚拟列表重新计算，确保索引与实际消息数组同步
        nextTick(() => {
          virtualizer.value.measure()
        })
      }
    }
  }

  async function loadOlderMessages() {
    if (loadingOlder.value || !hasMoreMessages.value || streaming.value) return
    // 新对话时 sessionId 为 null，不应请求
    if (!sessionId.value) return

    // 记录 prepend 前的虚拟化器总尺寸
    const oldTotalSize = virtualizer.value.getTotalSize()
    const oldScrollTop = messagesRef.value?.scrollTop || 0

    loadingOlder.value = true
    try {
      messagePage.value++
      const res = await getSessionMessages(sessionId.value, {
        pageNum: messagePage.value,
        pageSize: MESSAGE_PAGE_SIZE,
      })
      const records = res.data?.records || []
      if (records.length > 0) {
        const olderMessages = records.reverse().map((m) => parseMessage(m))
        await enrichMessagesAttachments(olderMessages)
        messages.value = [...olderMessages, ...messages.value]
        hasMoreMessages.value = records.length === MESSAGE_PAGE_SIZE
        // 保持滚动位置：prepend 后虚拟化器重新计算所有 item 位置，
        // 需要用 scrollToOffset 将 scrollTop 加上新增内容的高度
        await nextTick()
        const newTotalSize = virtualizer.value.getTotalSize()
        const sizeDelta = newTotalSize - oldTotalSize
        virtualizer.value.scrollToOffset(oldScrollTop + sizeDelta)
      } else {
        hasMoreMessages.value = false
      }
    } catch {
      // 静默失败
    } finally {
      loadingOlder.value = false
    }
  }

  function onStreamingEnded(isStreaming) {
    if (!isStreaming && pendingHistoryReload) {
      loadHistory()
    }
  }

  return {
    messagePage,
    hasMoreMessages,
    loadingOlder,
    initialLoadDone,
    switchingSession,
    loadHistory,
    loadOlderMessages,
    onStreamingEnded,
  }
}
