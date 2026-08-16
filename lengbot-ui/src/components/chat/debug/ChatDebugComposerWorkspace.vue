<template>
  <div class="debug-composer-workspace">
    <a-tabs v-model:active-key="composerSubMode" class="debug-composer-subtabs" @change="onSubModeChange">
      <a-tab-pane key="default" tab="默认模式">
        <div class="debug-composer-subpane">
          <ChatDebugUiStateBar :model-value="uiState" @update:model-value="$emit('update:uiState', $event)" />
          <ChatDebugComposerPanel
            ref="composerRef"
            :model-value="composerJson"
            @update:model-value="$emit('update:composerJson', $event)"
            @parse="$emit('parse')"
          >
            <template #toolbar-extra>
              <a-select
                :value="selectedPresetId"
                placeholder="加载预设"
                allow-clear
                :options="presetOptions"
                class="debug-preset-select"
                @update:value="$emit('update:selectedPresetId', $event)"
                @change="$emit('apply-preset', $event)"
              />
              <a-button @click="$emit('export-fixture')">导出 Fixture</a-button>
              <a-button @click="$emit('import-fixture')">导入 Fixture</a-button>
            </template>
          </ChatDebugComposerPanel>
        </div>
      </a-tab-pane>

      <a-tab-pane key="compare" tab="对比模式">
        <div class="debug-composer-subpane">
          <ChatDebugComparePanel @preview="$emit('compare-preview', $event)" />
        </div>
      </a-tab-pane>

      <a-tab-pane key="stream" tab="流式模拟">
        <div class="debug-composer-subpane">
          <ChatDebugStreamPanel @preview="$emit('stream-preview', $event)" />
        </div>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import ChatDebugUiStateBar from './ChatDebugUiStateBar.vue'
import ChatDebugComposerPanel from './ChatDebugComposerPanel.vue'
import ChatDebugComparePanel from './ChatDebugComparePanel.vue'
import ChatDebugStreamPanel from './ChatDebugStreamPanel.vue'

defineProps({
  composerJson: { type: String, default: '' },
  uiState: { type: Object, required: true },
  presetOptions: { type: Array, default: () => [] },
  selectedPresetId: { type: String, default: undefined },
})

const emit = defineEmits([
  'update:composerJson',
  'update:uiState',
  'update:selectedPresetId',
  'parse',
  'apply-preset',
  'export-fixture',
  'import-fixture',
  'compare-preview',
  'stream-preview',
  'sub-mode-change',
])

const composerRef = ref(null)
const composerSubMode = ref('default')

function onSubModeChange(key) {
  if (key !== 'compare') {
    emit('compare-preview', null)
  }
  emit('sub-mode-change', key)
}

defineExpose({
  validateAndGetMessage: () => composerRef.value?.validateAndGetMessage?.(),
  composerSubMode,
})
</script>

<style scoped>
.debug-composer-workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.debug-composer-subtabs :deep(.ant-tabs) {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.debug-composer-subtabs :deep(.ant-tabs-nav) {
  margin-bottom: 12px;
}

.debug-composer-subtabs :deep(.ant-tabs-nav::before) {
  border-bottom-color: var(--color-hairline);
}

.debug-composer-subtabs :deep(.ant-tabs-tab) {
  color: var(--color-body);
  padding: 6px 0;
}

.debug-composer-subtabs :deep(.ant-tabs-tab:hover) {
  color: var(--color-ink);
}

.debug-composer-subtabs :deep(.ant-tabs-tab-active .ant-tabs-tab-btn) {
  color: var(--color-ink);
  font-weight: 600;
}

.debug-composer-subtabs :deep(.ant-tabs-ink-bar) {
  background: var(--color-ink);
}

.debug-composer-subtabs {
  flex: 1;
  min-height: 0;
}

.debug-composer-subtabs :deep(.ant-tabs-content-holder) {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.debug-composer-subtabs :deep(.ant-tabs-content),
.debug-composer-subtabs :deep(.ant-tabs-tabpane-active) {
  height: 100%;
}

.debug-composer-subpane {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.debug-preset-select {
  width: 168px;
}
</style>
