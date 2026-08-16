<template>
  <div v-if="msg?._reasoningContent && !msg._sensitiveBlock" class="reasoning-panel">
    <div class="reasoning-header" @click="$emit('reasoning-toggle')">
      <BulbOutlined class="reasoning-icon" />
      <span class="reasoning-title">深度思考</span>
      <LoadingOutlined v-if="msg._streaming && !msg._reasoningDone" class="reasoning-spinner" />
      <RightOutlined :class="{ expanded: msg._reasoningExpanded }" class="tool-expand-icon" />
    </div>
    <CollapseTransition :open="!!msg._reasoningExpanded">
      <div ref="contentRef" class="reasoning-content">{{ msg._reasoningContent }}</div>
    </CollapseTransition>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import { BulbOutlined, LoadingOutlined, RightOutlined } from '@ant-design/icons-vue'
import CollapseTransition from '../../common/CollapseTransition.vue'

const props = defineProps({
  msg: { type: Object, required: true },
})

defineEmits(['reasoning-toggle'])

const contentRef = ref(null)

function scrollContentToBottom() {
  if (!props.msg?._streaming || !props.msg?._reasoningExpanded) return
  nextTick(() => {
    const el = contentRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

watch(() => props.msg?._reasoningContent, scrollContentToBottom)
watch(() => props.msg?._reasoningExpanded, scrollContentToBottom)
</script>

<style scoped>
.reasoning-panel {
  width: 100%;
  margin-bottom: 8px;
  border: 1px solid var(--color-warn-bg-deep);
  border-radius: 8px;
  overflow: hidden;
}
.reasoning-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: var(--color-warn-bg);
  cursor: pointer;
  user-select: none;
  font-size: 13px;
  color: #a16207;
  transition: background 0.15s;
}
.reasoning-header:hover {
  background: var(--color-warn-bg-deep);
}
.reasoning-icon {
  color: #eab308;
  font-size: 14px;
}
.reasoning-title {
  font-weight: 500;
}
.reasoning-spinner {
  color: #eab308;
  font-size: 12px;
  animation: spin 1s linear infinite;
}
.reasoning-header .tool-expand-icon {
  margin-left: auto;
  font-size: 12px;
  color: #ca8a04;
  transition: transform 0.2s ease;
}
.reasoning-header .tool-expand-icon.expanded {
  transform: rotate(90deg);
}
.reasoning-content {
  padding: 10px 12px;
  background: var(--color-warn-bg);
  font-size: 13px;
  color: var(--color-mute);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 300px;
  overflow-y: auto;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>

<style>
[data-theme='dark'] .reasoning-panel {
  border-color: #3b2f0a;
}
[data-theme='dark'] .reasoning-header {
  background: #3b2f0a;
  color: #fbbf24;
}
[data-theme='dark'] .reasoning-header:hover {
  background: #422006;
}
[data-theme='dark'] .reasoning-content {
  background: #27272a;
}
</style>
