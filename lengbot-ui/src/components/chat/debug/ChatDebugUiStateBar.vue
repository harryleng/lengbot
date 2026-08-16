<template>
  <div class="debug-ui-state-bar">
    <span class="debug-ui-state-label-wrap">
      <span class="debug-ui-state-label">UI 态</span>
      <a-tooltip :title="DEBUG_UI_STATE_TOOLTIPS.overview" placement="topLeft">
        <QuestionCircleOutlined class="debug-ui-state-help" />
      </a-tooltip>
    </span>

    <span v-for="item in uiStateItems" :key="item.key" class="debug-ui-state-item">
      <a-checkbox
        :checked="localState[item.key]"
        :disabled="item.disabled?.(localState)"
        @change="(e) => patch({ [item.key]: e.target.checked })"
      >
        {{ item.label }}
      </a-checkbox>
      <a-tooltip :title="item.tip" placement="top">
        <QuestionCircleOutlined class="debug-ui-state-help" />
      </a-tooltip>
    </span>
  </div>
</template>

<script setup>
import { reactive, watch } from 'vue'
import { QuestionCircleOutlined } from '@ant-design/icons-vue'
import { DEFAULT_DEBUG_UI_STATE, DEBUG_UI_STATE_TOOLTIPS, normalizeDebugUiState } from '@/utils/chat/debug/debugUiState'

const props = defineProps({
  modelValue: { type: Object, default: () => ({ ...DEFAULT_DEBUG_UI_STATE }) },
})

const emit = defineEmits(['update:modelValue'])

const localState = reactive(normalizeDebugUiState(props.modelValue))

const uiStateItems = [
  { key: 'streaming', label: 'streaming', tip: DEBUG_UI_STATE_TOOLTIPS.streaming },
  {
    key: 'reasoningExpanded',
    label: 'reasoningExpanded',
    tip: DEBUG_UI_STATE_TOOLTIPS.reasoningExpanded,
  },
  {
    key: 'reasoningDone',
    label: 'reasoningDone',
    tip: DEBUG_UI_STATE_TOOLTIPS.reasoningDone,
    disabled: (state) => state.streaming,
  },
  {
    key: 'toolsDone',
    label: 'toolsDone',
    tip: DEBUG_UI_STATE_TOOLTIPS.toolsDone,
    disabled: (state) => state.streaming,
  },
  { key: 'toolExpanded', label: 'toolExpanded', tip: DEBUG_UI_STATE_TOOLTIPS.toolExpanded },
  {
    key: 'refsSectionExpanded',
    label: 'refsExpanded',
    tip: DEBUG_UI_STATE_TOOLTIPS.refsSectionExpanded,
  },
]

watch(
  () => props.modelValue,
  (val) => {
    Object.assign(localState, normalizeDebugUiState(val))
  },
  { deep: true }
)

function patch(partial) {
  Object.assign(localState, partial)
  if (partial.streaming === true) {
    localState.toolsDone = false
    localState.reasoningDone = false
  }
  emit('update:modelValue', { ...localState })
}
</script>

<style scoped>
.debug-ui-state-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  padding: 8px 10px;
  margin-bottom: 12px;
  border: 1px dashed var(--gray-200);
  border-radius: 6px;
  background: var(--color-canvas);
}

.debug-ui-state-label-wrap,
.debug-ui-state-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.debug-ui-state-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--gray-600);
}

.debug-ui-state-help {
  font-size: 12px;
  color: var(--gray-400);
  cursor: help;
  flex-shrink: 0;
}

.debug-ui-state-help:hover {
  color: var(--color-link);
}
</style>
