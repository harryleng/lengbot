<template>
  <a-modal
    :open="open"
    title="Markdown 渲染测试"
    width="960px"
    :footer="null"
    :destroy-on-close="false"
    @update:open="$emit('update:open', $event)"
  >
    <div class="dialog-scroll-body">
      <div class="md-debug-toolbar">
        <a-button type="primary" @click="handleParse">解析</a-button>
        <a-button @click="handleClear">清空</a-button>
        <span class="md-debug-hint">使用与 AI 回复相同的 MarkdownPreview 组件渲染</span>
      </div>
      <div class="md-debug-layout">
        <div class="md-debug-input">
          <div class="md-debug-label">Markdown 源码</div>
          <a-textarea
            v-model:value="source"
            :rows="16"
            placeholder="在此输入 Markdown 文档..."
            class="md-debug-textarea"
          />
        </div>
        <div class="md-debug-output">
          <div class="md-debug-label">渲染结果</div>
          <div class="md-debug-preview">
            <MarkdownPreview v-if="parsedContent !== null" :content="parsedContent" :finalized="true" />
            <div v-else class="md-debug-empty">点击「解析」查看渲染效果</div>
          </div>
        </div>
      </div>
    </div>
  </a-modal>
</template>

<script setup>
import { ref, watch } from 'vue'
import MarkdownPreview from '@/components/MarkdownPreview.vue'

const props = defineProps({
  open: { type: Boolean, default: false },
})

defineEmits(['update:open'])

const source = ref('')
const parsedContent = ref(null)

function handleParse() {
  parsedContent.value = source.value
}

function handleClear() {
  source.value = ''
  parsedContent.value = null
}

watch(
  () => props.open,
  (visible) => {
    if (!visible) return
  }
)
</script>

<style scoped>
.md-debug-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.md-debug-hint {
  margin-left: auto;
  font-size: 12px;
  color: var(--gray-500);
}

.md-debug-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  min-height: 420px;
}

.md-debug-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
  margin-bottom: 8px;
}

.md-debug-textarea {
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.6;
}

.md-debug-preview {
  min-height: 360px;
  max-height: 60vh;
  overflow: auto;
  padding: 12px 14px;
  border: 1px solid var(--gray-100);
  border-radius: 8px;
  background: var(--color-canvas);
}

.md-debug-empty {
  color: var(--gray-400);
  font-size: 14px;
  padding: 24px 0;
  text-align: center;
}

@media (max-width: 768px) {
  .md-debug-layout {
    grid-template-columns: 1fr;
  }
}
</style>
