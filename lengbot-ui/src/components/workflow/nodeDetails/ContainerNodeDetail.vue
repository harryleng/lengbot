<template>
  <div class="wf-detail-pill">{{ step.nodeType === 'loop' ? '循环容器' : '批处理容器' }}</div>
  <div v-if="step.children?.length" class="wf-detail-meta">共 {{ step.children.length }} 个内部步骤</div>
  <div v-else class="wf-detail-hint">无子步骤记录</div>
</template>

<script setup>
defineProps({
  step: { type: Object, required: true },
})
</script>
