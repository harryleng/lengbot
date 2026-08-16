<template>
  <div class="debug-composer-panel">
    <div v-if="showToolbar" class="debug-panel-toolbar">
      <slot name="toolbar-extra" />
      <a-button type="primary" @click="$emit('parse')">解析预览</a-button>
      <a-button @click="handleFormat">格式化 JSON</a-button>
      <a-button @click="handleReset">重置</a-button>
    </div>
    <a-alert
      v-if="parseError"
      type="error"
      :message="parseError"
      show-icon
      closable
      class="debug-parse-error"
      @close="parseError = ''"
    />
    <div class="debug-editor-label">消息 JSON（role / content / metadata）</div>
    <a-textarea
      v-model:value="localJson"
      :rows="22"
      class="debug-json-textarea"
      placeholder='{"role":"assistant","content":"...","metadata":{...}}'
      @change="onEdit"
    />
    <div class="debug-editor-hint">
      metadata 支持 toolEvents、workflowEvents、reasoningContent、ragReferences 等字段。
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import {
  apiMessageToEditorJson,
  createDefaultApiMessage,
  editorJsonToApiMessage,
} from '@/utils/chat/debug/debugMessageBuilder'

const props = defineProps({
  modelValue: { type: String, default: '' },
  /** 为 false 时隐藏工具栏（由父级自定义） */
  showToolbar: { type: Boolean, default: true },
})

const emit = defineEmits(['update:modelValue', 'parse', 'error'])

const localJson = ref(props.modelValue || apiMessageToEditorJson(createDefaultApiMessage()))
const parseError = ref('')

watch(
  () => props.modelValue,
  (val) => {
    if (val != null && val !== localJson.value) {
      localJson.value = val
    }
  }
)

function onEdit() {
  emit('update:modelValue', localJson.value)
}

function handleFormat() {
  parseError.value = ''
  try {
    const msg = editorJsonToApiMessage(localJson.value)
    localJson.value = apiMessageToEditorJson(msg)
    emit('update:modelValue', localJson.value)
  } catch (e) {
    parseError.value = e.message || 'JSON 格式错误'
    emit('error', parseError.value)
  }
}

function handleReset() {
  parseError.value = ''
  localJson.value = apiMessageToEditorJson(createDefaultApiMessage())
  emit('update:modelValue', localJson.value)
}

function validateAndGetMessage() {
  parseError.value = ''
  try {
    return editorJsonToApiMessage(localJson.value)
  } catch (e) {
    parseError.value = e.message || 'JSON 格式错误'
    emit('error', parseError.value)
    return null
  }
}

defineExpose({ validateAndGetMessage, handleFormat, handleReset })
</script>

<style scoped>
.debug-composer-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.debug-panel-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.debug-parse-error {
  margin-bottom: 12px;
}

.debug-editor-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
  margin-bottom: 8px;
}

.debug-json-textarea {
  flex: 1;
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
}

.debug-editor-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--gray-500);
}
</style>
