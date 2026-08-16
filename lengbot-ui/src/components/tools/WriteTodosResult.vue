<template>
  <div class="write-todos-result">
    <div
      v-if="hasTodoPayload"
      class="todo-result-card"
      :class="{ 'is-failed': data.success === false, 'is-loading': isLoading }"
    >
      <div class="todo-result-summary">
        <LoadingOutlined v-if="isLoading" class="summary-icon is-loading" spin />
        <CheckCircleOutlined v-else-if="data.success !== false" class="summary-icon is-success" />
        <CloseCircleOutlined v-else class="summary-icon is-error" />
        <span>{{ summaryText }}</span>
        <span v-if="todos.length" class="summary-progress">{{ completedCount }}/{{ todos.length }} 已完成</span>
      </div>

      <div v-if="todos.length" class="todo-result-list">
        <div
          v-for="(todo, index) in todos"
          :key="todo.id || index"
          class="todo-result-item"
          :class="`is-${todo.status}`"
        >
          <SyncOutlined v-if="todo.status === 'in_progress'" spin class="todo-status-icon" />
          <CheckCircleFilled v-else-if="todo.status === 'completed'" class="todo-status-icon" />
          <CloseCircleFilled v-else-if="todo.status === 'cancelled'" class="todo-status-icon" />
          <ClockCircleOutlined v-else class="todo-status-icon" />
          <span class="todo-content">{{ todo.content || '未命名待办' }}</span>
          <span class="todo-status-label">{{ statusLabel(todo.status) }}</span>
        </div>
      </div>

      <div v-else-if="data.success === false && (data.message || data.error)" class="todo-result-message">
        {{ data.message || data.error }}
      </div>
    </div>

    <div v-else class="todo-result-fallback">
      <InfoCircleOutlined />
      <span>{{ fallbackText }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import {
  CheckCircleOutlined,
  CheckCircleFilled,
  CloseCircleOutlined,
  CloseCircleFilled,
  ClockCircleOutlined,
  InfoCircleOutlined,
  LoadingOutlined,
  SyncOutlined,
} from '@ant-design/icons-vue'

const props = defineProps({
  event: { type: Object, required: true },
})

const rawResult = computed(() => props.event.result || '')
const data = computed(() => {
  if (rawResult.value && typeof rawResult.value === 'object') return rawResult.value
  try {
    return JSON.parse(rawResult.value)
  } catch {
    return null
  }
})

const hasTodoPayload = computed(
  () =>
    data.value &&
    typeof data.value === 'object' &&
    (Array.isArray(data.value.todos) ||
      Object.prototype.hasOwnProperty.call(data.value, 'success') ||
      data.value.loading === true)
)
const isLoading = computed(() => data.value?.loading === true)
const todos = computed(() =>
  Array.isArray(data.value?.todos)
    ? data.value.todos.map((todo) => ({
        id: todo?.id,
        content: String(todo?.content || '').trim(),
        status: normalizeStatus(todo?.status),
      }))
    : []
)
const completedCount = computed(() => todos.value.filter((todo) => todo.status === 'completed').length)
const summaryText = computed(() => {
  if (isLoading.value) return data.value?.message || '正在更新待办，请稍候'
  if (data.value?.success === false) return data.value?.message || data.value?.error || '更新待办失败'
  return `已创建/更新 ${todos.value.length} 条待办，可在状态栏查看`
})
const fallbackText = computed(() => {
  if (data.value && typeof data.value === 'object') {
    return data.value.message || data.value.error || '待办已更新，可在状态栏查看'
  }
  const text = typeof rawResult.value === 'string' ? rawResult.value.trim() : ''
  return text || '待办已更新，可在状态栏查看'
})

function normalizeStatus(status) {
  return ['pending', 'in_progress', 'completed', 'cancelled'].includes(status) ? status : 'pending'
}

function statusLabel(status) {
  return { pending: '待处理', in_progress: '进行中', completed: '已完成', cancelled: '已取消' }[status] || '待处理'
}
</script>

<style lang="less" scoped>
.write-todos-result {
  font-size: 12px;
}

.todo-result-card {
  overflow: hidden;
  border: 1px solid #bbf7d0;
  border-left: 3px solid var(--color-success-500, #22c55e);
  border-radius: 8px;
  background: var(--color-canvas);

  &.is-failed {
    border-color: #fecaca;
    border-left-color: var(--color-error-500, #ef4444);
  }

  &.is-loading {
    border-color: #bae6fd;
    border-left-color: var(--main-500, #3b82f6);
  }
}

.todo-result-summary {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 10px;
  color: #166534;
  background: #f0fdf4;
  font-weight: 600;

  .todo-result-card.is-failed & {
    color: #b91c1c;
    background: #fef2f2;
  }

  .todo-result-card.is-loading & {
    color: var(--main-700, #1d4ed8);
    background: var(--main-25, #f0f9ff);
  }
}

.summary-icon {
  flex: 0 0 auto;
  font-size: 14px;
}
.summary-icon.is-success {
  color: var(--color-success-500, #22c55e);
}
.summary-icon.is-error {
  color: var(--color-error-500, #ef4444);
}
.summary-icon.is-loading {
  color: var(--main-600, #2563eb);
}
.summary-progress {
  margin-left: auto;
  color: var(--gray-500);
  font-size: 11px;
  font-weight: 400;
  white-space: nowrap;
}

.todo-result-list {
  padding: 4px 10px;
}
.todo-result-item {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  padding: 6px 0;
  color: var(--gray-700);
  border-bottom: 1px solid var(--gray-100);

  &:last-child {
    border-bottom: 0;
  }
  &.is-completed .todo-content {
    color: var(--gray-500);
    text-decoration: line-through;
  }
}

.todo-status-icon {
  flex: 0 0 auto;
  color: var(--gray-400);
  font-size: 13px;
}
.is-in_progress .todo-status-icon {
  color: var(--main-600);
}
.is-completed .todo-status-icon {
  color: var(--color-success-500, #22c55e);
}
.is-cancelled .todo-status-icon {
  color: var(--color-error-500, #ef4444);
}
.todo-content {
  overflow: hidden;
  flex: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.todo-status-label {
  flex: 0 0 auto;
  color: var(--gray-400);
  font-size: 11px;
}
.todo-result-message {
  padding: 8px 10px;
  color: #b91c1c;
  background: #fef2f2;
}

.todo-result-fallback {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 8px 10px;
  color: var(--gray-600);
  background: var(--gray-25);
  border-radius: 6px;
  line-height: 1.5;

  > :first-child {
    margin-top: 2px;
    color: var(--gray-400);
  }
}
</style>
