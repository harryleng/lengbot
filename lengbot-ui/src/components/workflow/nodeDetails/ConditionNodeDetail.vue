<template>
  <div class="wf-detail-pill condition">
    命中分支：{{ outputs.matchedGroupLabel || handleLabel(outputs.matchedHandle) || '—' }}
  </div>
  <div v-if="step.nextNodeId" class="wf-detail-meta">下一节点：{{ step.nextNodeId }}</div>
</template>

<script setup>
import { toRef } from 'vue'
import { useWorkflowStepContext, handleLabel } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { step, outputs } = useWorkflowStepContext(toRef(props, 'step'))
</script>
