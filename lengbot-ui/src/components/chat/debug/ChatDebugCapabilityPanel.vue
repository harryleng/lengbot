<template>
  <div class="debug-capability-panel">
    <div class="debug-panel-toolbar">
      <a-select v-model:value="selectedSample" :options="sampleOptions" style="width: 200px" @change="loadSample" />
      <a-button type="primary" @click="$emit('parse')">解析预览</a-button>
      <a-button @click="handleFormat">格式化 JSON</a-button>
    </div>
    <div class="debug-editor-label">能力块消息 JSON（Skill / SubAgent / 思考 / RAG）</div>
    <a-textarea v-model:value="localJson" :rows="20" class="debug-json-textarea" @change="onEdit" />
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
import { apiMessageToEditorJson, editorJsonToApiMessage } from '@/utils/chat/debug/debugMessageBuilder'
import {
  getCapabilitySampleMessage,
  getCapabilitySampleOptions,
  CAPABILITY_DEBUG_SAMPLES,
} from '@/utils/chat/debug/debugCapabilitySamples'

const emit = defineEmits(['parse', 'update:modelValue'])

const sampleOptions = getCapabilitySampleOptions()
const selectedSample = ref(sampleOptions[0]?.value || 'skill-active')
const localJson = ref('')
const parseError = ref('')

function loadSample(id = selectedSample.value) {
  const msg = getCapabilitySampleMessage(id)
  if (!msg) return
  localJson.value = apiMessageToEditorJson(msg)
  emit('update:modelValue', localJson.value)
}

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
  }
}

function validateAndGetMessage() {
  parseError.value = ''
  try {
    return editorJsonToApiMessage(localJson.value)
  } catch (e) {
    parseError.value = e.message || 'JSON 格式错误'
    return null
  }
}

loadSample(CAPABILITY_DEBUG_SAMPLES[0]?.id)

defineExpose({ validateAndGetMessage, loadSample, handleFormat })
</script>

<style scoped>
.debug-capability-panel {
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
</style>
