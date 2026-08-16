<template>
  <div v-if="workflowToolRenderable" class="wf-tool-registry-result">
    <ToolCallRenderer :event="workflowToolEvent" :message-index="-1" />
  </div>
  <div v-else-if="toolRawResult" class="wf-detail-pre">{{ truncateText(toolRawResult, 800) }}</div>
  <div v-else class="wf-detail-hint">无工具返回结果</div>
</template>

<script setup>
import { toRef } from 'vue'
import ToolCallRenderer from '../../ToolCallRenderer.vue'
import { truncateText } from '../workflowStepUtils.js'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { workflowToolEvent, workflowToolRenderable, toolRawResult } = useWorkflowStepContext(toRef(props, 'step'))
</script>
