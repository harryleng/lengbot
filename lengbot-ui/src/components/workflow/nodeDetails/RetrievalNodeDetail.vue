<template>
  <div v-if="queryText" class="wf-detail-meta">查询内容：{{ queryText }}</div>
  <div v-if="retrievalChunks.length" class="wf-chunk-list">
    <div v-for="(c, i) in retrievalChunks" :key="i" class="wf-chunk-card">
      <div class="wf-chunk-head">
        <span class="wf-chunk-idx">#{{ i + 1 }}</span>
        <span v-if="c.score != null" class="wf-chunk-score">{{ formatScore(c.score) }}</span>
      </div>
      <div class="wf-chunk-text">{{ truncateText(c.content, 280) }}</div>
    </div>
  </div>
  <div v-else-if="outputs.retrievalResult" class="wf-detail-pre">{{ truncateText(outputs.retrievalResult, 600) }}</div>
  <div v-else class="wf-detail-hint">未命中相关内容</div>
</template>

<script setup>
import { computed, toRef } from 'vue'
import { truncateText } from '../workflowStepUtils.js'
import { useWorkflowStepContext, formatScore } from '../composables/useWorkflowStepContext.js'

const props = defineProps({
  step: { type: Object, required: true },
})

const { outputs, retrievalChunks } = useWorkflowStepContext(toRef(props, 'step'))

const queryText = computed(() => {
  const input = props.step?.input || {}
  return input.query != null ? String(input.query) : ''
})
</script>
