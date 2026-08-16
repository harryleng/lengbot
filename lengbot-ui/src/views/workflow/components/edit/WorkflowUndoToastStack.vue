<template>
  <div class="workflow-undo-toast-stack" aria-live="polite">
    <TransitionGroup name="workflow-undo-toast">
      <div v-for="item in toasts" :key="item.id" class="workflow-undo-toast-item" :title="item.text">
        {{ item.text }}
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup>
import { onUnmounted, ref } from 'vue'
import {
  bindWorkflowUndoToastStack,
  WORKFLOW_UNDO_TOAST_DURATION_MS,
  WORKFLOW_UNDO_TOAST_MAX,
} from '../../utils/workflowUndoToast.js'

const toasts = ref([])
const timers = new Map()

function pushToast(text) {
  const id = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
  toasts.value = [...toasts.value, { id, text }].slice(-WORKFLOW_UNDO_TOAST_MAX)
  const timer = window.setTimeout(() => {
    toasts.value = toasts.value.filter((t) => t.id !== id)
    timers.delete(id)
  }, WORKFLOW_UNDO_TOAST_DURATION_MS)
  timers.set(id, timer)
}

bindWorkflowUndoToastStack(pushToast)

onUnmounted(() => {
  bindWorkflowUndoToastStack(null)
  timers.forEach((t) => window.clearTimeout(t))
  timers.clear()
})
</script>

<style scoped>
.workflow-undo-toast-stack {
  position: absolute;
  top: 16px;
  right: 16px;
  z-index: 15;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  pointer-events: none;
  max-width: calc(100% - 32px);
}

.workflow-undo-toast-item {
  pointer-events: auto;
  width: max-content;
  max-width: 100%;
  padding: 10px 14px;
  border-left: 4px solid #7c3aed;
  border-radius: 8px;
  box-shadow: 0 6px 18px rgba(91, 33, 182, 0.1);
  background: linear-gradient(135deg, #faf5ff 0%, #ffffff 72%);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
  color: #5b21b6;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.workflow-undo-toast-enter-active,
.workflow-undo-toast-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}

.workflow-undo-toast-enter-from,
.workflow-undo-toast-leave-to {
  opacity: 0;
  transform: translateX(12px);
}

.workflow-undo-toast-move {
  transition: transform 0.2s ease;
}
</style>
