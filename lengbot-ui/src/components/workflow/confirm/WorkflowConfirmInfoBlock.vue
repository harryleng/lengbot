<template>
  <div class="confirm-info-block" :class="variant">
    <div v-if="title" class="confirm-info-title">{{ title }}</div>
    <div class="confirm-info-content">{{ text }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { getConfirmInfoText } from './confirmFormUtils.js'

const props = defineProps({
  field: { type: Object, default: null },
  /** 直接传文案时优先使用 */
  text: { type: String, default: '' },
  title: { type: String, default: '' },
  /** form | detail | editor-preview */
  variant: { type: String, default: 'form' },
})

const text = computed(() => props.text || getConfirmInfoText(props.field))
</script>

<style scoped>
.confirm-info-block {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--color-canvas);
  border: 1px dashed var(--color-warning-soft);
}
.confirm-info-block.detail {
  border-style: solid;
  border-color: var(--color-border);
  background: var(--color-bg-soft, #fafafa);
}
.confirm-info-title {
  font-size: 12px;
  color: var(--color-mute);
  margin-bottom: 6px;
}
.confirm-info-content {
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-text-dark);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow-y: auto;
}
</style>
