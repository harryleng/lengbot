<template>
  <!-- 重试态（AI / 工作流） -->
  <div
    v-if="retryState"
    class="error-retry-block"
    :class="{ 'is-streaming': retryState.streaming, 'is-workflow': retryState.type === 'WORKFLOW_NODE_RETRY' }"
  >
    <div class="error-retry-header">
      <LoadingOutlined v-if="retryState.streaming" class="error-retry-icon" spin />
      <WarningOutlined v-else class="error-retry-icon" />
      <span class="error-retry-title">{{ retryState.title }}</span>
      <span v-if="retryState.attempt != null" class="error-retry-count">
        {{ retryState.attempt }}/{{ retryMaxDisplay(retryState) }}
      </span>
    </div>
    <div class="error-retry-message">{{ retryState.message }}</div>
  </div>

  <!-- 致命错误态 -->
  <div v-if="fatalState && !retryState" class="error-block" :class="fatalBlockClass(fatalState)">
    <div class="error-block-header">
      <CloseCircleOutlined class="error-block-icon" />
      <span class="error-block-title">{{ fatalState.title }}</span>
      <span v-if="fatalState.code" class="error-block-code">{{ fatalState.code }}</span>
    </div>
    <div v-if="fatalState.nodeLabel" class="error-block-sub">{{ fatalState.nodeLabel }}</div>
    <div class="error-block-message">{{ fatalState.message }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { LoadingOutlined, WarningOutlined, CloseCircleOutlined } from '@ant-design/icons-vue'
import { resolveMessageRetryState, resolveMessageFatalErrorState } from '../../../utils/chat/messageErrorState.js'

const props = defineProps({
  msg: { type: Object, required: true },
})

const retryState = computed(() => resolveMessageRetryState(props.msg))
const fatalState = computed(() => resolveMessageFatalErrorState(props.msg))

function retryMaxDisplay(state) {
  if (state.maxRetries == null) return state.attempt || '—'
  if (state.type === 'WORKFLOW_NODE_RETRY') {
    return Math.max(1, state.maxRetries - 1)
  }
  return state.maxRetries
}

function fatalBlockClass(state) {
  if (state.type === 'SENSITIVE_BLOCK') return 'is-sensitive'
  if (state.type === 'USER_ABORT') return 'is-abort'
  if (state.type === 'TOOL_STEP_LIMIT') return 'is-tool-limit'
  if (state.type === 'WORKFLOW_ERROR' || state.type === 'WORKFLOW_NODE_FAILURE') return 'is-workflow'
  return ''
}
</script>

<style scoped>
.error-retry-block {
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.24);
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 10px;
  font-size: 14px;
  line-height: 1.6;
  animation: errorFadeIn 0.3s ease;
}
.error-retry-block.is-streaming {
  animation:
    errorFadeIn 0.3s ease,
    retryPulse 1.6s ease-in-out infinite;
}
.error-retry-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.error-retry-icon {
  color: #d97706;
  font-size: 15px;
  flex-shrink: 0;
}
.error-retry-title {
  font-weight: 600;
  color: #92400e;
}
.error-retry-count {
  margin-left: auto;
  font-size: 12px;
  color: #92400e;
  background: rgba(245, 158, 11, 0.14);
  padding: 1px 8px;
  border-radius: 4px;
  font-family: var(--font-mono);
}
.error-retry-message {
  color: #92400e;
}
.error-block {
  background: var(--color-error-bg);
  border: 1px solid var(--color-error-soft);
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 10px;
  font-size: 14px;
  line-height: 1.6;
  animation: errorFadeIn 0.3s ease;
}
.error-block.is-sensitive {
  background: var(--color-warn-bg);
  border-color: rgba(245, 158, 11, 0.35);
}
.error-block.is-sensitive .error-block-icon,
.error-block.is-sensitive .error-block-title,
.error-block.is-sensitive .error-block-message {
  color: #9a3412;
}
.error-block.is-sensitive .error-block-code {
  color: #9a3412;
  background: rgba(245, 158, 11, 0.14);
}
.error-block.is-abort {
  background: rgba(113, 113, 122, 0.08);
  border-color: rgba(113, 113, 122, 0.25);
}
.error-block.is-abort .error-block-icon,
.error-block.is-abort .error-block-title,
.error-block.is-abort .error-block-message {
  color: #52525b;
}
.error-block.is-abort .error-block-code {
  color: #52525b;
  background: rgba(113, 113, 122, 0.12);
}
.error-block.is-tool-limit,
.error-block.is-workflow {
  background: var(--color-error-bg);
}
.error-block-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.error-block-icon {
  color: #ef4444;
  font-size: 16px;
  flex-shrink: 0;
}
.error-block-title {
  font-weight: 600;
  color: #991b1b;
}
.error-block-code {
  margin-left: auto;
  font-size: 12px;
  color: #991b1b;
  background: rgba(239, 68, 68, 0.1);
  padding: 1px 8px;
  border-radius: 4px;
  font-family: var(--font-mono);
}
.error-block-sub {
  font-size: 13px;
  color: #7f1d1d;
  margin-bottom: 4px;
  font-weight: 500;
}
.error-block-message {
  color: #991b1b;
  white-space: pre-wrap;
  word-break: break-word;
}
@keyframes errorFadeIn {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
@keyframes retryPulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(245, 158, 11, 0);
  }
  50% {
    box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.12);
  }
}
</style>
