<template>
  <div class="wf-detail-pill end">流程结束</div>
  <div v-if="endResultText" class="wf-detail-pre">{{ truncateText(endResultText, 600) }}</div>
  <div v-else class="wf-detail-hint">无独立结果字段，请查看回复正文</div>
</template>

<script setup>
import { toRef } from 'vue'
import { truncateText } from '../workflowStepUtils.js'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { endResultText } = useWorkflowStepContext(toRef(props, 'step'))
</script>
