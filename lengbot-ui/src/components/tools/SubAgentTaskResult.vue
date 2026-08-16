<template>
  <div class="subagent-task-result">
    <div class="result-head">
      <div>
        <div class="result-title">{{ title }}</div>
        <div v-if="batchId || taskId" class="result-subtitle">
          <span v-if="batchId">批次 {{ batchId }}</span>
          <span v-if="taskId">任务 {{ taskId }}</span>
        </div>
      </div>
      <span class="status-pill" :class="statusClass">{{ statusText }}</span>
    </div>

    <div v-if="summaryLine" class="summary-line">{{ summaryLine }}</div>

    <div v-if="items.length" class="task-list">
      <div v-for="item in items" :key="item.task_id || item.subagent_name" class="task-item">
        <div class="task-item-head">
          <span class="task-name">{{ item.display_name || item.subagent_name || 'SubAgent' }}</span>
          <span class="task-status" :class="statusClassOf(item.status)">{{ statusLabel(item.status) }}</span>
        </div>
        <div v-if="item.task_id" class="task-id">任务ID：{{ item.task_id }}</div>
        <div v-if="item.reply" class="task-reply">{{ item.reply }}</div>
        <div v-else-if="item.error" class="task-error">{{ item.error }}</div>
      </div>
    </div>

    <pre v-else-if="rawText" class="raw-text">{{ rawText }}</pre>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  event: { type: Object, required: true },
})

const parsed = computed(() => parseResult(props.event?.result))

const title = computed(() => {
  const toolName = props.event?.toolName
  if (toolName === 'cancel_subagent_task') return '取消 SubAgent 任务'
  if (toolName === 'get_subagent_task_result') return 'SubAgent 任务结果'
  return 'SubAgent 委派'
})

const batchId = computed(() => parsed.value?.batch_id || '')
const taskId = computed(() => parsed.value?.task_id || '')
const items = computed(() => {
  if (Array.isArray(parsed.value?.results)) return parsed.value.results
  if (parsed.value?.task_id || parsed.value?.subagent_name) return [parsed.value]
  return []
})
const rawText = computed(() => (typeof parsed.value === 'string' ? parsed.value : ''))
const status = computed(() => parsed.value?.status || (parsed.value?.background ? 'submitted' : 'completed'))
const statusText = computed(() => statusLabel(status.value))
const statusClass = computed(() => statusClassOf(status.value))

const summaryLine = computed(() => {
  const obj = parsed.value
  if (!obj || typeof obj !== 'object') return ''
  if (obj.affected != null) return `影响任务数：${obj.affected}`
  const total = obj.total_count ?? obj.results?.length
  if (total == null) return ''
  const parts = [`共 ${total} 个任务`]
  if (obj.completed_count != null) parts.push(`完成 ${obj.completed_count}`)
  if (obj.failed_count != null) parts.push(`失败 ${obj.failed_count}`)
  if (obj.cancelled_count != null) parts.push(`取消 ${obj.cancelled_count}`)
  return parts.join(' · ')
})

function parseResult(raw) {
  if (raw == null || raw === '') return ''
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(String(raw))
  } catch {
    return String(raw)
  }
}

function statusLabel(value) {
  const map = {
    submitted: '已提交',
    pending: '等待中',
    running: '运行中',
    completed: '已完成',
    failed: '失败',
    cancelled: '已取消',
    cancel_requested: '取消中',
    not_found: '未找到',
  }
  return map[value] || value || '未知'
}

function statusClassOf(value) {
  if (value === 'completed') return 'success'
  if (value === 'failed') return 'error'
  if (value === 'cancelled' || value === 'cancel_requested') return 'warn'
  if (value === 'running' || value === 'submitted' || value === 'pending') return 'processing'
  return 'default'
}
</script>

<style scoped>
.subagent-task-result {
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  background: var(--color-canvas);
  overflow: hidden;
}
.result-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  background: var(--color-canvas-soft);
}
.result-title {
  font-weight: 600;
  color: var(--color-ink);
}
.result-subtitle,
.task-id,
.summary-line {
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-mute);
  word-break: break-all;
}
.result-subtitle {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.status-pill,
.task-status {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
}
.status-pill.success,
.task-status.success {
  background: #f0fdf4;
  color: #15803d;
}
.status-pill.error,
.task-status.error {
  background: #fef2f2;
  color: #b91c1c;
}
.status-pill.warn,
.task-status.warn {
  background: #fffbeb;
  color: #b45309;
}
.status-pill.processing,
.task-status.processing {
  background: #eff6ff;
  color: #2563eb;
}
.status-pill.default,
.task-status.default {
  background: var(--color-canvas-soft);
  color: var(--color-mute);
}
.summary-line {
  padding: 10px 14px 0;
}
.task-list {
  display: grid;
  gap: 10px;
  padding: 12px 14px 14px;
}
.task-item {
  border: 1px solid var(--color-hairline);
  border-radius: 6px;
  padding: 10px;
  background: var(--color-canvas-soft);
}
.task-item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.task-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-ink);
}
.task-reply,
.task-error {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  color: var(--color-ink);
}
.task-error {
  color: #b91c1c;
}
.raw-text {
  margin: 0;
  padding: 12px 14px;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  color: var(--color-mute);
}
</style>
