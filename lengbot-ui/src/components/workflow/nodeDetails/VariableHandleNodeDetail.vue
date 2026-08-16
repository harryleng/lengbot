<template>
  <div v-if="hasKvData(variableHandleOutputs)" class="wf-detail-kv variable-handle">
    <div class="wf-detail-kv-title">处理结果</div>
    <pre>{{ formatKv(variableHandleOutputs) }}</pre>
  </div>
  <div v-else class="wf-detail-hint">无输出变量</div>
</template>

<script setup>
import { toRef } from 'vue'
import { formatKv, hasKvData } from '../workflowStepUtils.js'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { filteredOutputs: variableHandleOutputs } = useWorkflowStepContext(toRef(props, 'step'))
</script>
