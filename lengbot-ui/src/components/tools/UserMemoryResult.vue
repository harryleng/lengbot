<template>
  <div class="memory-tool-result">
    <div v-if="isPlainText" class="memory-plain">
      <pre>{{ rawResult }}</pre>
    </div>

    <template v-else>
      <div class="memory-summary" :class="{ failed: !data.success }">
        <DatabaseOutlined class="memory-summary-icon" />
        <span>{{ summaryText }}</span>
      </div>

      <div v-if="data.memory" class="memory-card">
        <div class="memory-card-head">
          <span class="memory-type">{{ memoryTypeLabel(data.memory.memoryType) }}</span>
          <span v-if="data.memory.confidence != null" class="memory-confidence">
            置信度 {{ data.memory.confidence }}
          </span>
        </div>
        <div class="memory-content">{{ data.memory.content }}</div>
        <div v-if="data.memory.keywords?.length" class="memory-keywords">
          <span v-for="keyword in data.memory.keywords" :key="keyword">{{ keyword }}</span>
        </div>
      </div>

      <div v-if="data.memories?.length" class="memory-list">
        <div v-for="memory in data.memories" :key="memory.id" class="memory-list-item">
          <span class="memory-type">{{ memoryTypeLabel(memory.memoryType) }}</span>
          <span class="memory-list-content">{{ memory.content }}</span>
        </div>
      </div>

      <div v-if="data.error" class="memory-error">{{ data.error }}</div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { DatabaseOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  event: { type: Object, required: true },
})

const rawResult = computed(() => props.event.result || '')
const data = computed(() => {
  try {
    const parsed = JSON.parse(rawResult.value)
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
})
const isPlainText = computed(() => !data.value)

const summaryText = computed(() => {
  if (!data.value?.success) return '长期记忆操作失败'
  if (data.value.memory) return '长期记忆已保存'
  if (data.value.memories) return `找到 ${data.value.memories.length} 条长期记忆`
  if (data.value.memoryId) return '长期记忆已停用'
  return '长期记忆操作完成'
})

function memoryTypeLabel(type) {
  return (
    {
      preference: '用户偏好',
      profile: '用户背景',
      project_fact: '项目事实',
      instruction: '长期指令',
    }[type] || '记忆'
  )
}
</script>

<style scoped>
.memory-tool-result {
  font-size: 13px;
}

.memory-plain {
  padding: 8px 10px;
  background: var(--color-canvas-soft);
  border-radius: 6px;
}

.memory-plain pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.memory-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--green-200);
  border-radius: 8px;
  background: var(--green-50);
  color: var(--green-700);
  font-weight: 600;
}

.memory-summary.failed {
  border-color: var(--color-error-soft);
  background: var(--color-error-bg);
  color: var(--color-error);
}

.memory-summary-icon {
  font-size: 14px;
}

.memory-card,
.memory-list {
  margin-top: 10px;
}

.memory-card {
  padding: 12px;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  background: var(--color-canvas-soft);
}

.memory-card-head,
.memory-list-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.memory-card-head {
  justify-content: space-between;
  margin-bottom: 8px;
}

.memory-type {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--blue-100);
  color: var(--blue-700);
  font-size: 12px;
  font-weight: 600;
}

.memory-confidence {
  font-size: 12px;
  color: var(--color-mute);
}

.memory-content {
  line-height: 1.7;
  color: var(--color-ink);
  white-space: pre-wrap;
  word-break: break-word;
}

.memory-keywords {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.memory-keywords span {
  padding: 2px 7px;
  border-radius: 4px;
  background: var(--color-canvas);
  color: var(--color-body);
  font-size: 12px;
}

.memory-list {
  display: grid;
  gap: 8px;
}

.memory-list-item {
  padding: 8px 10px;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  background: var(--color-canvas-soft);
}

.memory-list-content {
  min-width: 0;
  color: var(--color-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.memory-error {
  margin-top: 8px;
  color: var(--color-error);
}
</style>
