<template>
  <div v-if="step.message && step.status === 'done'" class="wf-detail-msg">{{ step.message }}</div>
  <div v-if="showGenericDetail" class="wf-detail-pre">{{ truncateText(step.detail, 800) }}</div>
  <div v-if="hasKvData(filteredOutputs)" class="wf-detail-kv">
    <div class="wf-detail-kv-title">出参</div>
    <pre>{{ formatKv(filteredOutputs) }}</pre>
  </div>
</template>

<script setup>
import { computed, toRef } from 'vue'
import { formatKv, hasKvData, truncateText } from '../workflowStepUtils.js'
import { HIDE_DETAIL_BODY_TYPES } from '../workflowNodeRegistry.js'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { step, filteredOutputs } = useWorkflowStepContext(toRef(props, 'step'))

const showGenericDetail = computed(
  () => props.step?.detail && String(props.step.detail).trim() && !HIDE_DETAIL_BODY_TYPES.has(props.step?.nodeType)
)
</script>
