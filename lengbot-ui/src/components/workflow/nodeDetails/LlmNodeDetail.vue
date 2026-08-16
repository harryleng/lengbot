<template>
  <div v-if="tokenSummary" class="wf-detail-pill">{{ tokenSummary }}</div>
  <div v-if="step.traceData?.llmMessages?.length" class="wf-detail-kv">
    <div class="wf-detail-kv-title">Prompt 快照</div>
    <pre>{{ formatMessages(step.traceData.llmMessages) }}</pre>
  </div>
  <div v-else class="wf-detail-hint">正文已在回复中流式展示</div>
</template>

<script setup>
import { toRef } from 'vue'
import { useWorkflowStepContext, formatMessages } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { step, tokenSummary } = useWorkflowStepContext(toRef(props, 'step'))
</script>
