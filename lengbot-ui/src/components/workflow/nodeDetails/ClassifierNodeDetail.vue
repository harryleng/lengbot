<template>
  <div class="wf-detail-pill intent">意图：{{ outputs.subject || '—' }}</div>
  <div v-if="outputs.thought" class="wf-detail-pre thought">{{ outputs.thought }}</div>
  <div v-if="tokenSummary" class="wf-detail-meta">{{ tokenSummary }}</div>
</template>

<script setup>
import { toRef } from 'vue'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { outputs, tokenSummary } = useWorkflowStepContext(toRef(props, 'step'))
</script>
