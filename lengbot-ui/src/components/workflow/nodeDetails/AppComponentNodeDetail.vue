<template>
  <div v-if="outputs.componentName" class="wf-detail-pill app">子流程 · {{ outputs.componentName }}</div>
  <div v-else-if="outputs.result" class="wf-detail-pre">{{ truncateText(outputs.result, 600) }}</div>
  <div v-if="step.children?.length" class="wf-detail-hint">
    展开上方子节点查看 {{ step.children.length }} 个内部步骤
  </div>
</template>

<script setup>
import { toRef } from 'vue'
import { truncateText } from '../workflowStepUtils.js'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { step, outputs } = useWorkflowStepContext(toRef(props, 'step'))
</script>
