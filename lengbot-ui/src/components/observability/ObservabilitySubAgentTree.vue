<template>
  <section v-if="batches.length" class="subagent-trace-tree">
    <h4>SubAgent 调用树</h4>
    <div v-for="batch in batches" :key="batch.spanId" class="batch-node">
      <div class="node-row batch-row">
        <span class="node-dot batch-dot"></span>
        <strong>批次 {{ batch.attributes?.batchId || '-' }}</strong>
        <span class="node-meta">{{ modeLabel(batch.attributes?.mode) }}</span>
        <a-tag :color="statusColor(batch.status)">{{ statusLabel(batch.status) }}</a-tag>
      </div>
      <div v-for="task in batch.tasks" :key="task.spanId" class="node-row task-row">
        <span class="tree-line"></span>
        <span class="node-dot task-dot"></span>
        <span>{{ task.attributes?.subagentName || 'SubAgent' }}</span>
        <code>{{ shortTaskId(task.attributes?.taskId) }}</code>
        <span class="node-meta">{{ task.attributes?.status || '-' }}</span>
        <a-tag :color="statusColor(task.status)">{{ statusLabel(task.status) }}</a-tag>
        <p v-if="task.attributes?.error" class="task-error">{{ task.attributes.error }}</p>
        <p v-else-if="task.attributes?.replyPreview" class="task-reply">{{ task.attributes.replyPreview }}</p>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ spans: { type: Array, default: () => [] } })
const batches = computed(() =>
  props.spans
    .filter((span) => span?.name === 'subagent_batch')
    .map((batch) => ({
      ...batch,
      tasks: props.spans.filter((task) => task?.name === 'subagent_task' && task.parentSpanId === batch.spanId),
    }))
)
function shortTaskId(value) {
  return !value ? '-' : String(value).length > 24 ? `${String(value).slice(0, 24)}…` : value
}
function modeLabel(value) {
  return { sync: '同步', parallel: '并行', background: '后台' }[value] || '任务编排'
}
function statusLabel(value) {
  return value === 'ERROR' ? '失败' : value === 'OK' ? '完成' : value || '未知'
}
function statusColor(value) {
  return value === 'ERROR' ? 'error' : value === 'OK' ? 'success' : 'processing'
}
</script>

<style scoped>
.subagent-trace-tree {
  margin: 16px 0;
  padding: 14px;
  border: 1px solid #f6d998;
  border-radius: 8px;
  background: #fffdf7;
}
.subagent-trace-tree h4 {
  margin: 0 0 10px;
  color: var(--color-ink);
  font-size: 14px;
}
.batch-node + .batch-node {
  margin-top: 12px;
}
.node-row {
  position: relative;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 28px;
  font-size: 13px;
}
.batch-row {
  color: #8a5a00;
}
.task-row {
  margin-left: 22px;
  padding: 5px 0 5px 14px;
  color: var(--color-body);
}
.node-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
}
.batch-dot {
  background: #d48806;
}
.task-dot {
  background: #4b86d1;
}
.tree-line {
  position: absolute;
  left: -5px;
  top: -8px;
  height: 24px;
  border-left: 1px solid #e7c86f;
  border-bottom: 1px solid #e7c86f;
  width: 9px;
}
.node-meta {
  color: var(--color-mute);
  font-size: 12px;
}
.task-row code {
  max-width: 230px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-mute);
  font-size: 11px;
}
.task-error,
.task-reply {
  flex-basis: 100%;
  margin: 2px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.5;
}
.task-error {
  color: #b91c1c;
}
.task-reply {
  color: var(--color-mute);
}
</style>
