<template>
  <div class="debug-stream-panel">
    <div class="debug-panel-toolbar">
      <a-select
        v-model:value="selectedPresetId"
        placeholder="选择预设"
        :options="presetOptions"
        style="width: 180px"
        @change="loadPreset"
      />
      <a-button type="primary" :loading="simulating" @click="startSimulation">
        {{ simulating ? '模拟中...' : '开始流式模拟' }}
      </a-button>
      <a-button :disabled="!simulating" @click="stopSimulation">停止</a-button>
      <a-button @click="resetPreview">重置</a-button>
    </div>
    <ChatDebugUiStateBar v-model="uiState" />
    <div class="debug-editor-label">目标消息 JSON（模拟结束后与下方一致）</div>
    <a-textarea v-model:value="localJson" :rows="16" class="debug-json-textarea" />
    <div class="debug-stream-hint">
      按顺序模拟：思考链 → 工具/能力事件 → 工作流事件 → 正文流式输出；预览始终走 parseMessage + ChatMessageRow。
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import ChatDebugUiStateBar from './ChatDebugUiStateBar.vue'
import {
  apiMessageToEditorJson,
  editorJsonToApiMessage,
  buildPreviewMessage,
} from '@/utils/chat/debug/debugMessageBuilder'
import { DEBUG_PRESETS, getPresetById } from '@/utils/chat/debug/debugPresets'
import { DEFAULT_DEBUG_UI_STATE } from '@/utils/chat/debug/debugUiState'
import { runDebugStreamSimulation } from '@/utils/chat/debug/debugStreamSimulator'

const emit = defineEmits(['preview'])

const presetOptions = DEBUG_PRESETS.map((p) => ({ value: p.id, label: p.label }))
const selectedPresetId = ref('query-knowledge')
const localJson = ref('')
const uiState = ref({ ...DEFAULT_DEBUG_UI_STATE })
const simulating = ref(false)
let abortController = null

function loadPreset(id = selectedPresetId.value) {
  const msg = getPresetById(id)
  if (!msg) return
  localJson.value = apiMessageToEditorJson(msg)
  emit('preview', null)
}

async function startSimulation() {
  let apiMsg
  try {
    apiMsg = editorJsonToApiMessage(localJson.value)
  } catch (e) {
    message.error(e.message || 'JSON 格式错误')
    return
  }

  stopSimulation()
  abortController = new AbortController()
  simulating.value = true
  emit(
    'preview',
    buildPreviewMessage(
      {
        role: apiMsg.role,
        content: '',
        metadata: { ...apiMsg.metadata, reasoningContent: '', toolEvents: [], workflowEvents: [] },
      },
      { ...uiState.value, streaming: true, toolsDone: false, reasoningDone: false }
    )
  )

  try {
    await runDebugStreamSimulation(apiMsg, uiState.value, (msg) => emit('preview', msg), {
      signal: abortController.signal,
    })
    message.success('流式模拟完成')
  } catch {
    message.info('流式模拟已停止')
  } finally {
    simulating.value = false
    abortController = null
  }
}

function stopSimulation() {
  abortController?.abort()
  abortController = null
  simulating.value = false
}

function resetPreview() {
  stopSimulation()
  emit('preview', null)
}

loadPreset('query-knowledge')

defineExpose({ stopSimulation })
</script>

<style scoped>
.debug-stream-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.debug-panel-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
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

.debug-stream-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--gray-500);
}
</style>
