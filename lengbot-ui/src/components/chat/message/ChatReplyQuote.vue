<template>
  <a-tooltip v-if="replyToInfo" :title="replyToInfo.content" placement="topLeft" :overlay-style="{ maxWidth: '480px' }">
    <div
      class="reply-quote"
      :class="{ clickable: hasReplyTarget }"
      @click.stop="$emit('scroll-to-reply', replyToMessageId)"
    >
      <div class="reply-quote-bar"></div>
      <div class="reply-quote-content">
        <span class="reply-quote-role">{{ replyToInfo.role === 'assistant' ? 'AI' : '你' }}</span>
        <span class="reply-quote-text">{{ replyToInfo.content }}</span>
      </div>
    </div>
  </a-tooltip>
</template>

<script setup>
defineProps({
  replyToInfo: { type: Object, default: null },
  hasReplyTarget: { type: Boolean, default: false },
  replyToMessageId: { type: [String, Number], default: null },
})

defineEmits(['scroll-to-reply'])
</script>

<style scoped>
.reply-quote {
  display: flex;
  gap: 8px;
  padding: 6px 10px;
  margin-bottom: 6px;
  margin-left: 42px;
  background: rgba(0, 112, 243, 0.06);
  border-radius: 8px;
  border-left: 3px solid #0070f3;
  align-self: stretch;
  transition: background 0.15s;
}
.reply-quote.clickable {
  cursor: pointer;
}
.reply-quote.clickable:hover {
  background: rgba(0, 112, 243, 0.12);
}
.reply-quote-bar {
  display: none;
}
.reply-quote-content {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 1.4;
  overflow: hidden;
}
.reply-quote-role {
  color: var(--color-link);
  font-weight: 500;
  margin-right: 6px;
  flex-shrink: 0;
}
.reply-quote-text {
  color: var(--color-mute);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline;
  max-width: 100%;
}
</style>
