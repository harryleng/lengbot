<template>
  <div class="wf-detail-pill output">已合并至回复</div>
  <div v-if="outputText" class="wf-detail-meta">模板渲染 {{ outputText.length }} 字</div>
  <div class="wf-detail-hint">正文已在消息区展示，此处不重复全文</div>
</template>

<script setup>
import { toRef } from 'vue'
import { useWorkflowStepContext } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { outputText } = useWorkflowStepContext(toRef(props, 'step'))
</script>
