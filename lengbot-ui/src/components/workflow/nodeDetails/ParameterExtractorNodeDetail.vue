<template>
  <div v-if="extractRows.length" class="wf-param-table extractor">
    <div class="wf-detail-kv-title">提取参数</div>
    <div v-for="row in extractRows" :key="row.key" class="wf-param-row">
      <span class="wf-param-key">{{ row.key }}</span>
      <span class="wf-param-value mono">{{ row.value }}</span>
    </div>
  </div>
  <div v-else-if="step.status === 'failed'" class="wf-detail-hint">解析失败，未写入结构化字段</div>
  <details v-if="extractRawText" class="wf-raw-fold">
    <summary>模型原始回复</summary>
    <pre>{{ truncateText(extractRawText, 1200) }}</pre>
  </details>
</template>

<script setup>
import { toRef } from 'vue'
import { truncateText } from '../workflowStepUtils.js'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { step, extractRows, extractRawText } = useWorkflowStepContext(toRef(props, 'step'))
</script>
