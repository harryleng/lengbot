<template>
  <div v-if="replyTo.active" class="reply-preview-bar">
    <a-tooltip
      :title="replyTo.content"
      placement="topLeft"
      :mouse-enter-delay="0.3"
      :overlay-style="{ maxWidth: '520px' }"
    >
      <div class="reply-preview-content">
        <span class="reply-preview-text">{{ replyTo.content }}</span>
      </div>
    </a-tooltip>
    <button class="reply-preview-close" @click="onCancel">
      <CloseOutlined />
    </button>
  </div>
</template>

<script setup>
import { CloseOutlined } from '@ant-design/icons-vue'

defineProps({
  replyTo: {
    type: Object,
    default: () => ({ active: false, content: '' }),
  },
})

const emit = defineEmits(['cancel-reply'])

function onCancel() {
  emit('cancel-reply')
}
</script>

<style scoped>
/* 引用回复预览条 */
.reply-preview-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin: 8px 12px 8px;
  background: var(--color-canvas-soft-2);
  border-radius: 8px;
  border-left: 3px solid #0070f3;
}
.reply-preview-content {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  line-height: 1.4;
  overflow: hidden;
}
.reply-preview-text {
  color: var(--color-mute);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
  width: 100%;
}
.reply-preview-close {
  flex-shrink: 0;
  background: none;
  border: none;
  cursor: pointer;
  color: var(--color-mute);
  padding: 2px;
  display: flex;
  align-items: center;
  transition: color 0.15s;
}
.reply-preview-close:hover {
  color: var(--color-ink);
}
</style>

<style>
[data-theme='dark'] .reply-preview-bar {
  background: #27272a;
}
</style>
