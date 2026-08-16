<template>
  <div
    v-if="!msg._streaming && hasActionContext"
    class="message-actions"
    :class="{ 'is-error-actions': isErrorProfile }"
  >
    <a-tooltip v-if="showTts" :title="speakingMsgKey === index ? '停止朗读' : '朗读'">
      <button class="btn-copy" :class="{ speaking: speakingMsgKey === index }" @click="$emit('speak')">
        <SoundOutlined />
      </button>
    </a-tooltip>
    <a-tooltip :title="msg._copied ? '已复制' : '复制'">
      <button class="btn-copy" :class="{ copied: msg._copied }" @click="$emit('copy')">
        <CheckOutlined v-if="msg._copied" />
        <CopyOutlined v-else />
      </button>
    </a-tooltip>
    <a-tooltip v-if="showRegenerate" title="重新生成">
      <button class="btn-copy btn-action-text" :disabled="loading" @click="$emit('regenerate')">
        <ReloadOutlined />
      </button>
    </a-tooltip>
    <a-tooltip v-if="showRequestId" :title="msg._requestIdCopied ? '已复制' : '复制 Request ID'">
      <button
        class="btn-copy btn-action-text"
        :class="{ copied: msg._requestIdCopied }"
        @click="$emit('copy-request-id')"
      >
        <CheckOutlined v-if="msg._requestIdCopied" />
        <NumberOutlined v-else />
      </button>
    </a-tooltip>
    <a-tooltip v-if="showMessageId" :title="msg._msgIdCopied ? '已复制' : '复制 Message ID'">
      <button class="btn-copy btn-action-text" :class="{ copied: msg._msgIdCopied }" @click="$emit('copy-message-id')">
        <CheckOutlined v-if="msg._msgIdCopied" />
        <TagOutlined v-else />
      </button>
    </a-tooltip>
    <a-tooltip v-if="showViewRaw" title="查看原始内容">
      <button class="btn-copy" @click="$emit('view-raw')">
        <EyeOutlined />
      </button>
    </a-tooltip>
    <a-tooltip v-if="showEdit" title="编辑">
      <button class="btn-copy" @click="$emit('edit')">
        <EditOutlined />
      </button>
    </a-tooltip>
    <a-tooltip v-if="showReply" title="引用回复">
      <button class="btn-copy" @click="$emit('reply')">
        <CommentOutlined />
      </button>
    </a-tooltip>
    <!-- 消息反馈：点赞/踩 -->
    <template v-if="showFeedback">
      <a-tooltip title="有帮助">
        <button
          class="btn-copy btn-feedback"
          :class="{ 'feedback-liked': feedbackType === 'like' }"
          @click="$emit('feedback-like')"
        >
          <LikeFilled v-if="feedbackType === 'like'" />
          <LikeOutlined v-else />
        </button>
      </a-tooltip>
      <a-tooltip title="无帮助">
        <button
          class="btn-copy btn-feedback"
          :class="{ 'feedback-disliked': feedbackType === 'dislike' }"
          @click="$emit('show-dislike')"
        >
          <DislikeFilled v-if="feedbackType === 'dislike'" />
          <DislikeOutlined v-else />
        </button>
      </a-tooltip>
    </template>
    <a-tooltip v-if="showStar" :title="msg._starred ? '取消收藏' : '收藏'">
      <button class="btn-copy" :class="{ starred: msg._starred }" @click="$emit('star')">
        <StarFilled v-if="msg._starred" />
        <StarOutlined v-else />
      </button>
    </a-tooltip>
    <a-tooltip title="删除">
      <button class="btn-copy btn-delete" @click="$emit('delete')">
        <DeleteOutlined />
      </button>
    </a-tooltip>
    <span v-if="msg._createTime" class="message-time">发表于 {{ formatTime(msg._createTime) }}</span>
  </div>
</template>

<script setup>
import {
  CopyOutlined,
  CheckOutlined,
  ReloadOutlined,
  NumberOutlined,
  TagOutlined,
  EyeOutlined,
  EditOutlined,
  CommentOutlined,
  LikeOutlined,
  DislikeOutlined,
  LikeFilled,
  DislikeFilled,
  StarOutlined,
  StarFilled,
  DeleteOutlined,
  SoundOutlined,
} from '@ant-design/icons-vue'
import { formatTime } from '../../../utils/format'
import { hasMessageActionContext } from '../../../utils/chat/messageViewState.js'
import { getMessageActionProfile } from '../../../utils/chat/messageErrorState.js'
import { computed } from 'vue'

const props = defineProps({
  msg: { type: Object, required: true },
  index: { type: Number, required: true },
  loading: { type: Boolean, default: false },
  canRegenerate: { type: Boolean, default: false },
  isLastUserMessage: { type: Boolean, default: false },
  speakingMsgKey: { type: [Number, String], default: null },
  showTtsBtn: { type: Boolean, default: false },
  feedbackType: { type: String, default: null },
})

defineEmits([
  'copy',
  'regenerate',
  'edit',
  'reply',
  'delete',
  'star',
  'feedback-like',
  'show-dislike',
  'speak',
  'view-raw',
  'copy-request-id',
  'copy-message-id',
])

const hasActionContext = computed(() => hasMessageActionContext(props.msg))
const actionProfile = computed(() => getMessageActionProfile(props.msg))
const isErrorProfile = computed(() => actionProfile.value.startsWith('error_'))

const showTts = computed(() => props.msg.role === 'assistant' && props.showTtsBtn && actionProfile.value === 'normal')
const showRegenerate = computed(
  () =>
    props.msg.role === 'assistant' && props.canRegenerate && (actionProfile.value === 'normal' || isErrorProfile.value)
)
const showRequestId = computed(
  () => props.msg.role === 'assistant' && !!props.msg._requestId && actionProfile.value === 'error_persisted'
)
const showMessageId = computed(
  () => props.msg.role === 'assistant' && !!props.msg._id && actionProfile.value === 'error_persisted'
)
const showViewRaw = computed(
  () => props.msg.role === 'assistant' && (actionProfile.value === 'normal' || isErrorProfile.value)
)
const showEdit = computed(
  () => props.msg.role === 'user' && !props.loading && props.isLastUserMessage && !props.msg._replyToMessageId
)
const showReply = computed(() => props.msg.role === 'assistant' && actionProfile.value === 'normal')
const showFeedback = computed(
  () => props.msg.role === 'assistant' && actionProfile.value === 'normal' && !!(props.msg._id || props.msg._terminated)
)
const showStar = computed(() => actionProfile.value === 'normal')
</script>

<style scoped>
.message-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}
.message-actions.is-error-actions {
  margin-top: 4px;
}
.btn-action-text {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  font-size: 12px;
}
.btn-action-text span {
  line-height: 1;
}
.btn-copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-top: 6px;
  padding: 4px 6px;
  background: none;
  border: none;
  border-radius: 4px;
  font-size: 14px;
  color: var(--color-mute);
  cursor: pointer;
  opacity: 0;
  transition:
    opacity 0.15s,
    color 0.15s;
}
.message-actions .btn-copy {
  opacity: 1;
}
.message-time {
  font-size: 12px;
  color: var(--color-mute);
  white-space: nowrap;
  opacity: 0.8;
}
.btn-copy:hover {
  color: var(--color-body);
}
.btn-copy.copied {
  color: #16a34a;
  opacity: 1;
}
.btn-copy.starred {
  color: #f59e0b;
  opacity: 1;
}
.btn-delete:hover {
  color: #ef4444;
}
.btn-feedback:hover {
  color: var(--blue-500);
}
.btn-feedback.feedback-liked {
  color: #16a34a;
  opacity: 1;
}
.btn-feedback.feedback-disliked {
  color: #ef4444;
  opacity: 1;
}
.btn-copy.speaking {
  color: var(--color-link);
}
</style>

<style>
.message.assistant .message-actions {
  justify-content: flex-start;
}
.message.user .message-actions {
  justify-content: flex-end;
}
.message.assistant .message-actions .message-time {
  margin-left: auto;
}
.message.user .message-actions .message-time {
  order: -1;
  margin-right: auto;
}
</style>
