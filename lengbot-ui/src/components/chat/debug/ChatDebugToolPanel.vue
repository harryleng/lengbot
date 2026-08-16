<template>
  <div class="debug-tool-panel">
    <div v-if="showToolbar" class="debug-panel-toolbar">
      <a-button type="primary" @click="$emit('parse')">解析预览</a-button>
      <a-button @click="loadSample">加载样例</a-button>
      <a-button @click="loadErrorSample">错误样例</a-button>
    </div>

    <div class="debug-field">
      <div class="debug-editor-label">工具</div>
      <a-select
        v-model:value="selectedTool"
        :options="toolOptions"
        show-search
        option-filter-prop="label"
        style="width: 100%"
        @change="onToolChange"
      />
    </div>

    <div class="debug-field">
      <div class="debug-editor-label">result JSON（工具返回，不真正调用工具）</div>
      <a-textarea v-model:value="resultJson" :rows="18" class="debug-json-textarea" @change="onResultEdit" />
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
import { ref, computed } from 'vue'
import {
  getToolSelectOptions,
  getToolSampleResultJson,
  getToolErrorSampleResult,
  UNREGISTERED_TOOL_NAME,
} from '@/utils/chat/debug/toolDebugSamples'
import { getToolDisplayName } from '@/components/toolRegistry'

const emit = defineEmits(['parse', 'error'])

defineProps({
  /** 为 false 时隐藏工具栏 */
  showToolbar: { type: Boolean, default: true },
})

const toolOptions = getToolSelectOptions()
const selectedTool = ref(toolOptions[0]?.value || 'query_knowledge')
const resultJson = ref(getToolSampleResultJson(selectedTool.value))
const parseError = ref('')

const mockEvent = computed(() => buildEvent())

function buildEvent() {
  const toolName = selectedTool.value === UNREGISTERED_TOOL_NAME ? 'custom_unknown_tool' : selectedTool.value
  return {
    type: 'tool_result',
    toolName,
    displayName: getToolDisplayName(toolName),
    result: resultJson.value,
    contentOffset: 0,
  }
}

function onToolChange() {
  resultJson.value = getToolSampleResultJson(selectedTool.value)
  parseError.value = ''
}

function onResultEdit() {
  parseError.value = ''
}

function loadSample() {
  resultJson.value = getToolSampleResultJson(selectedTool.value)
  parseError.value = ''
}

function loadErrorSample() {
  resultJson.value = getToolErrorSampleResult(selectedTool.value)
  parseError.value = ''
}

function validateAndGetEvent() {
  parseError.value = ''
  try {
    JSON.parse(resultJson.value)
    return buildEvent()
  } catch (e) {
    parseError.value = e.message || 'result JSON 格式错误'
    emit('error', parseError.value)
    return null
  }
}

defineExpose({
  validateAndGetEvent,
  loadSample,
  loadErrorSample,
})
</script>

<style scoped>
.debug-tool-panel {
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

.debug-field {
  margin-bottom: 12px;
}

.debug-editor-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
  margin-bottom: 8px;
}

.debug-json-textarea {
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
}

.debug-parse-error {
  margin-top: 8px;
}
</style>
