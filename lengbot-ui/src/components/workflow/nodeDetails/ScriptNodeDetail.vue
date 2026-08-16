<template>
  <div v-if="hasKvData(filteredOutputs)" class="wf-detail-kv script">
    <div class="wf-detail-kv-title">脚本出参</div>
    <pre>{{ formatKv(filteredOutputs) }}</pre>
  </div>
  <div v-else class="wf-detail-hint">脚本无返回字段</div>
</template>

<script setup>
import { toRef } from 'vue'
import { formatKv, hasKvData } from '../workflowStepUtils.js'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { filteredOutputs } = useWorkflowStepContext(toRef(props, 'step'))
</script>
