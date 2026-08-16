<template>
  <div ref="rootRef" class="debug-split-pane" :class="{ 'is-dragging': dragging }">
    <div class="debug-split-editor" :style="{ width: `${editorRatio * 100}%` }">
      <slot name="editor" />
    </div>
    <div
      class="debug-split-divider"
      role="separator"
      aria-orientation="vertical"
      aria-label="调整编辑区与预览区宽度"
      @mousedown.prevent="startDrag"
    />
    <div class="debug-split-preview">
      <slot name="preview" />
    </div>
  </div>
</template>

<script setup>
import { ref, onBeforeUnmount, watch } from 'vue'

const props = defineProps({
  /** 编辑区占比 0~1，默认预览区更大 */
  modelValue: { type: Number, default: 0.38 },
  minEditorRatio: { type: Number, default: 0.22 },
  maxEditorRatio: { type: Number, default: 0.72 },
})

const emit = defineEmits(['update:modelValue'])

const rootRef = ref(null)
const dragging = ref(false)
const editorRatio = ref(props.modelValue)

watch(
  () => props.modelValue,
  (v) => {
    editorRatio.value = v
  }
)

function clampRatio(ratio) {
  return Math.min(props.maxEditorRatio, Math.max(props.minEditorRatio, ratio))
}

function onPointerMove(e) {
  const el = rootRef.value
  if (!el || !dragging.value) return
  const rect = el.getBoundingClientRect()
  const next = (e.clientX - rect.left) / rect.width
  editorRatio.value = clampRatio(next)
  emit('update:modelValue', editorRatio.value)
}

function stopDrag() {
  dragging.value = false
  document.removeEventListener('mousemove', onPointerMove)
  document.removeEventListener('mouseup', stopDrag)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
}

function startDrag() {
  dragging.value = true
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  document.addEventListener('mousemove', onPointerMove)
  document.addEventListener('mouseup', stopDrag)
}

onBeforeUnmount(stopDrag)
</script>

<style scoped>
.debug-split-pane {
  display: flex;
  align-items: stretch;
  height: calc(100vh - 56px - 52px - 32px);
  min-height: 480px;
  gap: 0;
}

.debug-split-pane.is-dragging {
  cursor: col-resize;
}

.debug-split-editor {
  flex-shrink: 0;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.debug-split-divider {
  flex-shrink: 0;
  width: 10px;
  margin: 0 -2px;
  cursor: col-resize;
  position: relative;
  z-index: 2;
  touch-action: none;
}

.debug-split-divider::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 50%;
  width: 2px;
  transform: translateX(-50%);
  background: var(--gray-200);
  border-radius: 1px;
  transition: background 0.15s;
}

.debug-split-divider:hover::after,
.debug-split-pane.is-dragging .debug-split-divider::after {
  background: var(--color-link);
  width: 3px;
}

.debug-split-preview {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

@media (max-width: 960px) {
  .debug-split-pane {
    flex-direction: column;
    height: auto;
  }

  .debug-split-editor {
    width: 100% !important;
  }

  .debug-split-divider {
    display: none;
  }
}
</style>
