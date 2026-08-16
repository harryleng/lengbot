<template>
  <a-collapse v-model:active-key="activeKeys" ghost class="node-resilience-collapse">
    <a-collapse-panel key="resilience">
      <template #header>
        <span class="node-resilience-header">超时与重试</span>
        <span v-if="summaryText" class="node-resilience-summary">{{ summaryText }}</span>
      </template>

      <a-form-item v-if="profile.showConnectTimeout" class="resilience-form-item">
        <template #label>
          <ConfigFieldLabel label="连接超时(秒)" :tip="hint('connectTimeout')" />
        </template>
        <a-input-number
          v-model:value="nodeData.timeoutConfig.connectTimeout"
          :min="profile.connectTimeout.min"
          :max="profile.connectTimeout.max"
          :disabled="readonly"
          :placeholder="String(profile.connectTimeout.default)"
          @change="emitChange"
        />
      </a-form-item>

      <a-form-item v-if="profile.showReadTimeout" class="resilience-form-item">
        <template #label>
          <ConfigFieldLabel :label="readTimeoutLabel" :tip="hint('readTimeout')" />
        </template>
        <a-input-number
          v-model:value="nodeData.timeoutConfig.readTimeout"
          :min="profile.readTimeout.min"
          :max="profile.readTimeout.max"
          :disabled="readonly"
          :placeholder="String(profile.readTimeout.default)"
          @change="emitChange"
        />
      </a-form-item>

      <template v-if="profile.showRetry">
        <a-form-item class="resilience-form-item">
          <template #label>
            <ConfigFieldLabel label="启用重试" :tip="hint('retryEnabled')" />
          </template>
          <a-switch v-model:checked="nodeData.retryConfig.enabled" :disabled="readonly" @change="emitChange" />
        </a-form-item>
        <template v-if="nodeData.retryConfig?.enabled">
          <a-form-item class="resilience-form-item">
            <template #label>
              <ConfigFieldLabel label="最大次数" :tip="hint('maxAttempts')" />
            </template>
            <a-input-number
              v-model:value="nodeData.retryConfig.maxAttempts"
              :min="1"
              :max="2"
              :disabled="readonly"
              @change="emitChange"
            />
          </a-form-item>
          <a-form-item class="resilience-form-item">
            <template #label>
              <ConfigFieldLabel label="重试间隔(ms)" :tip="hint('retryDelayMs')" />
            </template>
            <a-input-number
              v-model:value="nodeData.retryConfig.delayMs"
              :min="0"
              :max="30000"
              :step="500"
              :disabled="readonly"
              @change="emitChange"
            />
          </a-form-item>
        </template>
      </template>
    </a-collapse-panel>
  </a-collapse>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import ConfigFieldLabel from './ConfigFieldLabel.vue'
import { ensureNodeResilienceConfig, getNodeResilienceProfile, getResilienceFieldHint } from '../nodeResilienceMeta.js'

const props = defineProps({
  nodeId: { type: String, default: '' },
  nodeType: { type: String, required: true },
  nodeData: { type: Object, required: true },
  readonly: { type: Boolean, default: false },
})

const emit = defineEmits(['change'])

const activeKeys = ref(['resilience'])

const profile = computed(() => getNodeResilienceProfile(props.nodeType) || {})

const readTimeoutLabel = computed(() => {
  if (props.nodeType === 'script') return '执行超时(秒)'
  if (props.nodeType === 'loop' || props.nodeType === 'batch') return '整体超时(秒)'
  if (props.nodeType === 'confirm') return '等待超时(秒)'
  return '响应超时(秒)'
})

const summaryText = computed(() => {
  const tc = props.nodeData?.timeoutConfig
  const rc = props.nodeData?.retryConfig
  if (!tc && !rc) return ''
  const parts = []
  if (profile.value.showConnectTimeout && tc?.connectTimeout != null) {
    parts.push(`连接 ${tc.connectTimeout}s`)
  }
  if (profile.value.showReadTimeout && tc?.readTimeout != null) {
    parts.push(`响应 ${tc.readTimeout}s`)
  }
  if (profile.value.showRetry && rc?.enabled) {
    parts.push(`重试 ${rc.maxAttempts}次`)
  }
  return parts.join(' · ')
})

watch(
  () => [props.nodeId, props.nodeType],
  () => {
    ensureNodeResilienceConfig(props.nodeData, props.nodeType)
  },
  { immediate: true }
)

function hint(field) {
  return getResilienceFieldHint(field)
}

function emitChange() {
  emit('change')
}
</script>

<style scoped>
.node-resilience-collapse {
  margin-top: 8px;
  margin-bottom: 4px;
  border-top: 1px solid var(--color-hairline);
  padding-top: 8px;
}
.node-resilience-collapse :deep(.ant-collapse-header) {
  align-items: center !important;
  padding: 8px 0 !important;
}
.node-resilience-collapse :deep(.ant-collapse-content-box) {
  padding: 0 0 8px !important;
}
.node-resilience-header {
  font-weight: 600;
  font-size: 13px;
  color: var(--color-ink);
}
.node-resilience-summary {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: var(--color-mute);
}
.resilience-form-item {
  margin-bottom: 12px;
}
.resilience-form-item:last-child {
  margin-bottom: 0;
}
</style>
