<template>
  <WorkflowConfirmStepDetail :step="step" :outputs="filteredOutputs" />
</template>

<script setup>
import { toRef } from 'vue'
import WorkflowConfirmStepDetail from '../confirm/WorkflowConfirmStepDetail.vue'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { step, filteredOutputs } = useWorkflowStepContext(toRef(props, 'step'))
</script>
