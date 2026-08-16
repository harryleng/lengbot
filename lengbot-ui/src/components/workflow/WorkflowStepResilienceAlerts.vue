<template>
  <div v-if="events.length" class="wf-resilience-alerts">
    <div
      v-for="(item, idx) in events"
      :key="idx"
      class="wf-resilience-alert"
      :class="item.kind === 'failure' ? 'is-failure' : 'is-retry'"
    >
      <div class="wf-resilience-alert-header">
        <LoadingOutlined v-if="item.kind === 'retry' && isStreaming" class="wf-resilience-icon" spin />
        <WarningOutlined v-else-if="item.kind === 'retry'" class="wf-resilience-icon" />
        <CloseCircleOutlined v-else class="wf-resilience-icon" />
        <span class="wf-resilience-title">{{ alertTitle(item) }}</span>
        <span v-if="item.kind === 'retry' && item.attempt != null" class="wf-resilience-count">
          {{ item.attempt }}/{{ retryTotal(item) }}
        </span>
      </div>
      <div v-if="item.message" class="wf-resilience-message">{{ item.message }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { LoadingOutlined, WarningOutlined, CloseCircleOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  step: { type: Object, required: true },
  isStreaming: { type: Boolean, default: false },
})

const events = computed(() => props.step?.resilienceEvents || [])

function retryTotal(item) {
  if (item.maxAttempts == null) return '—'
  return Math.max(1, item.maxAttempts - 1)
}

function alertTitle(item) {
  if (item.kind === 'failure') return '节点执行失败'
  const map = {
    connect_timeout: '连接超时',
    read_timeout: '响应超时',
    execution_error: '执行异常',
  }
  return map[item.reason] || '正在重试'
}
</script>

<style scoped>
.wf-resilience-alerts {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 6px;
}
.wf-resilience-alert {
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.5;
}
.wf-resilience-alert.is-retry {
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.24);
}
.wf-resilience-alert.is-failure {
  background: var(--color-error-bg);
  border: 1px solid var(--color-error-soft);
}
.wf-resilience-alert-header {
  display: flex;
  align-items: center;
  gap: 6px;
}
.wf-resilience-icon {
  flex-shrink: 0;
  font-size: 13px;
}
.is-retry .wf-resilience-icon {
  color: #d97706;
}
.is-failure .wf-resilience-icon {
  color: var(--color-error);
}
.wf-resilience-title {
  font-weight: 600;
}
.is-retry .wf-resilience-title {
  color: #92400e;
}
.is-failure .wf-resilience-title {
  color: #991b1b;
}
.wf-resilience-count {
  margin-left: auto;
  font-size: 11px;
  font-family: var(--font-mono, ui-monospace, monospace);
  padding: 1px 6px;
  border-radius: 4px;
}
.is-retry .wf-resilience-count {
  color: #92400e;
  background: rgba(245, 158, 11, 0.14);
}
.wf-resilience-message {
  margin-top: 2px;
  color: inherit;
  opacity: 0.92;
}
.is-retry .wf-resilience-message {
  color: #92400e;
}
.is-failure .wf-resilience-message {
  color: #991b1b;
}
</style>
