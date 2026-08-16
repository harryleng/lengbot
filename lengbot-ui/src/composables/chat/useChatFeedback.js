import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { submitMessageFeedback, batchGetMessageFeedbacks } from '../../api/chat'

/**
 * 消息反馈：点赞/踩 + batch load + dislike 原因弹窗
 */
export function useChatFeedback() {
  // 消息反馈状态：messageId → "like"/"dislike"
  const messageFeedbackMap = ref(new Map())

  function getMessageFeedbackType(msg) {
    return messageFeedbackMap.value.get(msg._id || msg.id) || null
  }

  async function handleMessageFeedback(msg, rating) {
    const msgId = msg._id || msg.id
    if (!msgId) {
      message.warning('消息正在保存，请稍后再试')
      return
    }
    const current = messageFeedbackMap.value.get(msgId)
    // 乐观更新
    if (current === rating) {
      messageFeedbackMap.value.delete(msgId)
    } else {
      messageFeedbackMap.value.set(msgId, rating)
    }
    try {
      const res = await submitMessageFeedback(msgId, { rating })
      // 服务端返回 null 表示取消，否则更新为实际值
      if (res?.data) {
        messageFeedbackMap.value.set(msgId, res.data.rating)
        message.success(rating === 'like' ? '已反馈有用' : '已反馈无帮助')
      } else {
        messageFeedbackMap.value.delete(msgId)
        message.success('已取消反馈')
      }
    } catch {
      // 回滚
      if (current) {
        messageFeedbackMap.value.set(msgId, current)
      } else {
        messageFeedbackMap.value.delete(msgId)
      }
    }
  }

  // dislike 原因弹窗
  const dislikeModalVisible = ref(false)
  const dislikeReason = ref('')
  const dislikeTargetMsg = ref(null)

  function showDislikeModal(msg) {
    dislikeTargetMsg.value = msg
    dislikeReason.value = ''
    dislikeModalVisible.value = true
  }

  async function submitDislikeReason() {
    await submitDislikeFeedback(dislikeReason.value || null)
  }

  async function skipDislikeReason() {
    await submitDislikeFeedback(null)
  }

  function closeDislikeModal() {
    dislikeModalVisible.value = false
    dislikeReason.value = ''
    dislikeTargetMsg.value = null
  }

  async function submitDislikeFeedback(reason) {
    const msg = dislikeTargetMsg.value
    if (!msg) return
    const msgId = msg._id || msg.id
    if (!msgId) {
      dislikeModalVisible.value = false
      message.warning('消息正在保存，请稍后再试')
      return
    }
    dislikeModalVisible.value = false
    const current = messageFeedbackMap.value.get(msgId)
    messageFeedbackMap.value.set(msgId, 'dislike')
    try {
      await submitMessageFeedback(msgId, { rating: 'dislike', reason })
      message.success('已反馈无帮助')
    } catch {
      if (current) {
        messageFeedbackMap.value.set(msgId, current)
      } else {
        messageFeedbackMap.value.delete(msgId)
      }
    }
  }

  async function loadBatchFeedbacks(msgs) {
    const ids = msgs.filter((m) => m.role === 'assistant' && m._id).map((m) => m._id)
    if (ids.length === 0) return
    try {
      const res = await batchGetMessageFeedbacks(ids)
      if (res?.data) {
        const map = new Map(messageFeedbackMap.value)
        for (const [msgId, fb] of Object.entries(res.data)) {
          if (fb?.rating) map.set(msgId, fb.rating)
        }
        messageFeedbackMap.value = map
      }
    } catch {
      // interceptor handled
    }
  }

  return {
    messageFeedbackMap,
    getMessageFeedbackType,
    handleMessageFeedback,
    showDislikeModal,
    submitDislikeReason,
    skipDislikeReason,
    closeDislikeModal,
    submitDislikeFeedback,
    loadBatchFeedbacks,
    dislikeModalVisible,
    dislikeReason,
  }
}
