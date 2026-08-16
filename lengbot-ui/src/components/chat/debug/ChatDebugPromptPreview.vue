<template>
  <ChatDebugPreviewShell class="debug-prompt-preview">
    <div v-if="payload" class="prompt-preview-surface">
      <div class="prompt-preview-header">
        <div>
          <div class="prompt-preview-title">{{ payload.title || 'Prompt 预览' }}</div>
          <div class="prompt-preview-desc">{{ payload.description || '本地 Prompt fixture 预览' }}</div>
        </div>
        <a-tag :color="missingVariables.length ? 'orange' : 'green'">
          {{ missingVariables.length ? `缺失 ${missingVariables.length} 个变量` : '变量完整' }}
        </a-tag>
      </div>

      <div class="prompt-preview-section">
        <div class="prompt-section-title">变量</div>
        <div class="prompt-var-grid">
          <div v-for="v in payload.variables || []" :key="v.key" class="prompt-var-card">
            <span class="prompt-var-key">{{ v.key }}</span>
            <span class="prompt-var-label">{{ v.label || v.key }}</span>
            <span class="prompt-var-value" :class="{ empty: !v.value }">{{ v.value || '未设置' }}</span>
          </div>
        </div>
      </div>

      <div class="prompt-preview-section">
        <div class="prompt-section-title">渲染结果</div>
        <pre class="prompt-rendered">{{ renderedPrompt }}</pre>
      </div>

      <div class="prompt-preview-section">
        <div class="prompt-section-title">原始模板</div>
        <pre class="prompt-template">{{ payload.template }}</pre>
      </div>
    </div>
    <div v-else class="debug-preview-empty">点击「解析预览」查看 Prompt 渲染结果</div>
  </ChatDebugPreviewShell>
</template>

<script setup>
import { computed } from 'vue'
import ChatDebugPreviewShell from './ChatDebugPreviewShell.vue'

const props = defineProps({
  payload: { type: Object, default: null },
})

const variableMap = computed(() => {
  const map = new Map()
  for (const item of props.payload?.variables || []) {
    map.set(item.key, item.value)
  }
  return map
})

const missingVariables = computed(() => {
  const keys = new Set()
  const template = props.payload?.template || ''
  template.replace(/\{\{\s*([^}]+?)\s*\}\}/g, (_, key) => {
    const normalized = key.trim()
    if (!variableMap.value.get(normalized)) keys.add(normalized)
    return ''
  })
  return [...keys]
})

const renderedPrompt = computed(() => {
  const template = props.payload?.template || ''
  return template.replace(/\{\{\s*([^}]+?)\s*\}\}/g, (_, key) => {
    const normalized = key.trim()
    const value = variableMap.value.get(normalized)
    return value ? String(value) : `{{${normalized}}}`
  })
})
</script>

<style scoped>
.debug-prompt-preview {
  height: 100%;
}

.prompt-preview-surface {
  width: 100%;
  max-width: 880px;
  padding: 16px 20px;
  margin: 0 auto;
}

.prompt-preview-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.prompt-preview-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--gray-900);
}

.prompt-preview-desc {
  margin-top: 4px;
  font-size: 13px;
  color: var(--gray-500);
}

.prompt-preview-section {
  margin-bottom: 14px;
}

.prompt-section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
  margin-bottom: 8px;
}

.prompt-var-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 8px;
}

.prompt-var-card {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px;
  border: 1px solid var(--gray-200);
  border-radius: 6px;
  background: var(--color-canvas);
}

.prompt-var-key {
  font-family: 'Menlo', 'Consolas', monospace;
  font-size: 12px;
  color: #4f46e5;
}

.prompt-var-label,
.prompt-var-value {
  font-size: 12px;
  color: var(--gray-600);
}

.prompt-var-value.empty {
  color: #d97706;
}

.prompt-rendered,
.prompt-template {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  padding: 12px;
  border-radius: 6px;
  border: 1px solid var(--gray-200);
  background: var(--color-canvas-soft);
  color: var(--gray-800);
  font-size: 13px;
  line-height: 1.7;
}

.prompt-template {
  color: var(--gray-600);
}

.debug-preview-empty {
  padding: 48px 24px;
  text-align: center;
  color: var(--gray-400);
  font-size: 14px;
}
</style>
