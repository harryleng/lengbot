<template>
  <div class="wf-detail-pill api">HTTP {{ outputs.statusCode ?? '—' }}</div>
  <div v-if="outputs.body" class="wf-detail-pre">{{ truncateText(outputs.body, 800) }}</div>
</template>

<script setup>
import { toRef } from 'vue'
import { truncateText } from '../workflowStepUtils.js'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { outputs } = useWorkflowStepContext(toRef(props, 'step'))
</script>
