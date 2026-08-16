<template>
  <div v-if="userInputText" class="wf-detail-pill start">用户输入 · {{ userInputText.length }} 字</div>
  <div v-if="userInputText" class="wf-detail-pre muted">{{ truncateText(userInputText, 400) }}</div>
  <div v-else class="wf-detail-hint">流程已启动</div>
  <div v-if="userInputText" class="wf-detail-hint">完整内容已在上方用户消息中展示</div>
</template>

<script setup>
import { toRef } from 'vue'
import { truncateText } from '../workflowStepUtils.js'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { userInputText } = useWorkflowStepContext(toRef(props, 'step'))
</script>
