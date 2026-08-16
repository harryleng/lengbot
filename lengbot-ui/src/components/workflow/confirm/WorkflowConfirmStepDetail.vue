<template>
  <div class="confirm-step-detail">
    <div v-if="step.status === 'suspended'" class="confirm-step-hint">请在下方表单中完成人工确认</div>
    <WorkflowConfirmSubmittedSummary v-else-if="hasSubmittedData" :data="outputs" title="确认提交" variant="detail" />
    <div v-else-if="step.message" class="confirm-step-msg">{{ step.message }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import WorkflowConfirmSubmittedSummary from './WorkflowConfirmSubmittedSummary.vue'
import { hasKvData } from '../workflowStepUtils.js'

const props = defineProps({
  step: { type: Object, required: true },
  outputs: { type: Object, default: () => ({}) },
})

const hasSubmittedData = computed(() => hasKvData(props.outputs))
</script>

<style scoped>
.confirm-step-hint {
  font-size: 13px;
  color: var(--color-warning-deep, #b45309);
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--color-warn-bg);
  border: 1px solid var(--color-warning-soft);
}
.confirm-step-msg {
  font-size: 13px;
  color: var(--color-text-secondary);
}
</style>
