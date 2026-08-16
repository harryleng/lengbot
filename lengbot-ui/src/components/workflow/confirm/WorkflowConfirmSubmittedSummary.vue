<template>
  <div v-if="entries.length" class="confirm-submitted" :class="variant">
    <div v-if="title" class="confirm-submitted-title">{{ title }}</div>
    <div v-for="row in entries" :key="row.key" class="confirm-submitted-row">
      <span class="confirm-submitted-label">{{ row.label }}</span>
      <span class="confirm-submitted-value">{{ row.value }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { buildConfirmSubmittedEntries } from './confirmFormUtils.js'

const props = defineProps({
  formFields: { type: Array, default: () => [] },
  submittedData: { type: Object, default: null },
  /** 仅 KV 对象时按 key 展示 */
  data: { type: Object, default: null },
  title: { type: String, default: '确认提交' },
  variant: { type: String, default: 'form' },
})

const entries = computed(() => {
  if (props.formFields?.length) {
    return buildConfirmSubmittedEntries(props.formFields, props.submittedData || props.data)
  }
  const raw = props.submittedData || props.data
  if (!raw || typeof raw !== 'object') return []
  return Object.entries(raw).map(([key, value]) => ({
    key,
    label: key,
    value: value != null ? String(value) : '—',
  }))
})
</script>

<style scoped>
.confirm-submitted {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.confirm-submitted.detail {
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid var(--color-warning-soft);
  background: var(--color-warn-bg);
}
.confirm-submitted-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-warning-deep, #b45309);
  margin-bottom: 4px;
}
.confirm-submitted-row {
  display: flex;
  gap: 10px;
  font-size: 13px;
  padding: 6px 8px;
  background: var(--color-canvas);
  border-radius: 6px;
  align-items: flex-start;
}
.confirm-submitted-label {
  color: var(--color-mute);
  min-width: 72px;
  flex-shrink: 0;
}
.confirm-submitted-value {
  color: var(--color-text-dark);
  font-weight: 500;
  word-break: break-word;
  flex: 1;
}
</style>
