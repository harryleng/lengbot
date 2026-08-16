<template>
  <div class="plain-text-content">
    {{ content }}
    <span v-if="!finalized" class="typing-cursor">|</span>
  </div>
</template>

<script setup>
defineProps({
  content: { type: String, default: '' },
  finalized: { type: Boolean, default: true },
})
</script>

<style scoped>
.plain-text-content {
  white-space: pre-wrap;
  word-break: break-word;
}
.typing-cursor {
  display: inline;
  animation: typing-cursor-blink 0.8s step-end infinite;
  color: var(--gray-600);
  font-weight: 400;
  margin-left: 1px;
}
@keyframes typing-cursor-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}
</style>
