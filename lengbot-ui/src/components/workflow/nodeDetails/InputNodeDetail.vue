<template>
  <div v-if="assignmentRows.length" class="wf-param-table">
    <div class="wf-detail-kv-title">写入变量</div>
    <div v-for="row in assignmentRows" :key="row.key" class="wf-param-row">
      <span class="wf-param-key">{{ row.key }}</span>
      <span class="wf-param-value">{{ row.value }}</span>
    </div>
  </div>
  <div v-else class="wf-detail-hint">未写入新变量</div>
</template>

<script setup>
import { toRef } from 'vue'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { assignmentRows } = useWorkflowStepContext(toRef(props, 'step'))
</script>
