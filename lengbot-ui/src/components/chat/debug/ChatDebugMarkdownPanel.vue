<template>
  <div class="debug-markdown-panel">
    <div v-if="showToolbar" class="debug-panel-toolbar">
      <a-button type="primary" @click="$emit('parse')">解析预览</a-button>
      <a-button @click="loadSample">加载示例</a-button>
      <a-button @click="handleClear">清空</a-button>
    </div>
    <div class="debug-editor-label">Markdown 源码</div>
    <a-textarea
      v-model:value="localSource"
      :rows="22"
      class="debug-md-textarea"
      placeholder="在此输入 Markdown，或点击「加载示例」..."
      @change="onEdit"
    />
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { MARKDOWN_DEBUG_SAMPLE } from '@/utils/chat/debug/markdownDebugSample'

const props = defineProps({
  modelValue: { type: String, default: '' },
  showToolbar: { type: Boolean, default: true },
})

const emit = defineEmits(['update:modelValue', 'parse'])

const localSource = ref(props.modelValue ?? '')

watch(
  () => props.modelValue,
  (val) => {
    if (val != null && val !== localSource.value) {
      localSource.value = val
    }
  }
)

function onEdit() {
  emit('update:modelValue', localSource.value)
}

function loadSample() {
  localSource.value = MARKDOWN_DEBUG_SAMPLE
  emit('update:modelValue', localSource.value)
  message.success('示例已加载，点击「解析预览」查看效果')
}

function handleClear() {
  localSource.value = ''
  emit('update:modelValue', '')
}

defineExpose({
  getSource: () => localSource.value,
  loadSample,
  handleClear,
})
</script>

<style scoped>
.debug-markdown-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.debug-panel-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.debug-editor-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
  margin-bottom: 8px;
}

.debug-md-textarea {
  flex: 1;
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.6;
}
</style>
