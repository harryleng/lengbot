<template>
  <div class="debug-compare-panel">
    <div class="debug-panel-toolbar">
      <a-button type="primary" @click="parseBoth">解析两侧</a-button>
      <a-button @click="formatLeft">格式化左</a-button>
      <a-button @click="formatRight">格式化右</a-button>
      <a-button @click="syncRightFromLeft">右 ← 左</a-button>
    </div>
    <div class="debug-compare-grid">
      <div class="debug-compare-col">
        <div class="debug-editor-label">消息 A</div>
        <a-textarea v-model:value="jsonLeft" :rows="18" class="debug-json-textarea" />
      </div>
      <div class="debug-compare-col">
        <div class="debug-editor-label">消息 B</div>
        <a-textarea v-model:value="jsonRight" :rows="18" class="debug-json-textarea" />
      </div>
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
  </div>
</template>

<script setup>
import { ref } from 'vue'
import {
  apiMessageToEditorJson,
  editorJsonToApiMessage,
  createDefaultApiMessage,
} from '@/utils/chat/debug/debugMessageBuilder'

const emit = defineEmits(['preview'])

const jsonLeft = ref(apiMessageToEditorJson(createDefaultApiMessage()))
const jsonRight = ref(
  apiMessageToEditorJson({
    ...createDefaultApiMessage(),
    content: '对比版本：你好，这是 Debug Lab 预览消息（修改版）。',
  })
)
const parseError = ref('')

function parseSide(text) {
  return editorJsonToApiMessage(text)
}

function parseBoth() {
  parseError.value = ''
  try {
    emit('preview', {
      left: parseSide(jsonLeft.value),
      right: parseSide(jsonRight.value),
    })
  } catch (e) {
    parseError.value = e.message || 'JSON 格式错误'
    emit('preview', null)
  }
}

function formatLeft() {
  try {
    jsonLeft.value = apiMessageToEditorJson(parseSide(jsonLeft.value))
  } catch (e) {
    parseError.value = e.message || 'JSON 格式错误'
  }
}

function formatRight() {
  try {
    jsonRight.value = apiMessageToEditorJson(parseSide(jsonRight.value))
  } catch (e) {
    parseError.value = e.message || 'JSON 格式错误'
  }
}

function syncRightFromLeft() {
  jsonRight.value = jsonLeft.value
}
</script>

<style scoped>
.debug-compare-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.debug-panel-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.debug-compare-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  flex: 1;
  min-height: 0;
}

.debug-compare-col {
  display: flex;
  flex-direction: column;
  min-height: 0;
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

.debug-parse-error {
  margin-top: 8px;
}

@media (max-width: 960px) {
  .debug-compare-grid {
    grid-template-columns: 1fr;
  }
}
</style>
