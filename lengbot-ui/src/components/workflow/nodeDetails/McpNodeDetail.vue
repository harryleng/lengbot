<template>
  <ToolCallRenderer v-if="mcpPseudoEvent.toolName" :event="mcpPseudoEvent" />
  <div v-else-if="toolRawResult" class="wf-detail-pre">{{ truncateText(toolRawResult, 800) }}</div>
</template>

<script setup>
import { toRef } from 'vue'
import ToolCallRenderer from '../../ToolCallRenderer.vue'
import { truncateText } from '../workflowStepUtils.js'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { mcpPseudoEvent, toolRawResult } = useWorkflowStepContext(toRef(props, 'step'))
</script>
