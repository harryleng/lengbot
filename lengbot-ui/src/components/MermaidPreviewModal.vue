<template>
  <a-modal
    v-model:open="visible"
    title="流程图预览"
    :footer="null"
    :width="920"
    centered
    destroy-on-close
    class="mermaid-preview-modal"
    @cancel="handleClose"
  >
    <div class="mpm-toolbar">
      <button type="button" class="mpm-btn" :disabled="scale <= 0.5" @click="zoomOut">
        <ZoomOutOutlined />
      </button>
      <span class="mpm-scale">{{ Math.round(scale * 100) }}%</span>
      <button type="button" class="mpm-btn" :disabled="scale >= 3" @click="zoomIn">
        <ZoomInOutlined />
      </button>
      <button type="button" class="mpm-btn" @click="resetZoom">
        <FullscreenOutlined />
      </button>
    </div>
    <div
      ref="viewportRef"
      class="mpm-viewport"
      :class="{ 'is-dragging': dragging }"
      @wheel.prevent="onWheel"
      @mousedown="onPanStart"
    >
      <div v-if="loading" class="mpm-loading">
        <LoadingOutlined spin />
        加载中...
      </div>
      <div v-else-if="error" class="mpm-error">{{ error }}</div>
      <div v-else ref="contentRef" class="mpm-content" :style="transformStyle" v-html="svgHtml" />
    </div>
  </a-modal>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { ZoomInOutlined, ZoomOutOutlined, FullscreenOutlined, LoadingOutlined } from '@ant-design/icons-vue'
import { renderMermaidSvg, resetMermaidTheme } from '@/utils/mermaidRender'
import { useTheme } from '@/composables/useTheme'

const props = defineProps({
  open: { type: Boolean, default: false },
  source: { type: String, default: '' },
})

const emit = defineEmits(['update:open'])

const { isDark } = useTheme()
const visible = computed({
  get: () => props.open,
  set: (v) => emit('update:open', v),
})

const loading = ref(false)
const error = ref('')
const svgHtml = ref('')
const scale = ref(1)
const panX = ref(0)
const panY = ref(0)
const dragging = ref(false)
const viewportRef = ref(null)
const contentRef = ref(null)

const transformStyle = computed(() => ({
  transform: `translate(${panX.value}px, ${panY.value}px) scale(${scale.value})`,
}))

let panStart = null

watch(
  () => [props.open, props.source, isDark.value],
  async ([open, source]) => {
    if (!open || !source?.trim()) {
      svgHtml.value = ''
      error.value = ''
      return
    }
    loading.value = true
    error.value = ''
    resetZoom()
    try {
      resetMermaidTheme()
      const { svg, bindFunctions } = await renderMermaidSvg(source.trim(), isDark.value)
      svgHtml.value = svg
      await nextTickBind(bindFunctions)
    } catch (e) {
      error.value = '流程图渲染失败'
      svgHtml.value = ''
    } finally {
      loading.value = false
    }
  },
  { immediate: true }
)

async function nextTickBind(bindFunctions) {
  await Promise.resolve()
  if (contentRef.value && bindFunctions) {
    bindFunctions(contentRef.value)
  }
}

function zoomIn() {
  scale.value = Math.min(3, +(scale.value + 0.1).toFixed(2))
}

function zoomOut() {
  scale.value = Math.max(0.5, +(scale.value - 0.1).toFixed(2))
}

function resetZoom() {
  scale.value = 1
  panX.value = 0
  panY.value = 0
}

function onWheel(e) {
  const delta = e.deltaY > 0 ? -0.08 : 0.08
  scale.value = Math.min(3, Math.max(0.5, +(scale.value + delta).toFixed(2)))
}

function onPanStart(e) {
  if (e.button !== 0) return
  dragging.value = true
  panStart = { x: e.clientX - panX.value, y: e.clientY - panY.value }
  window.addEventListener('mousemove', onPanMove)
  window.addEventListener('mouseup', onPanEnd)
}

function onPanMove(e) {
  if (!panStart) return
  panX.value = e.clientX - panStart.x
  panY.value = e.clientY - panStart.y
}

function onPanEnd() {
  dragging.value = false
  panStart = null
  window.removeEventListener('mousemove', onPanMove)
  window.removeEventListener('mouseup', onPanEnd)
}

function handleClose() {
  visible.value = false
}

onUnmounted(onPanEnd)
</script>

<style scoped>
.mpm-toolbar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-bottom: 12px;
}
.mpm-btn {
  width: 32px;
  height: 32px;
  border: 1px solid var(--color-hairline);
  border-radius: 6px;
  background: var(--color-canvas);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--color-ink);
}
.mpm-btn:hover:not(:disabled) {
  background: var(--color-canvas-soft-2);
}
.mpm-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.mpm-scale {
  min-width: 48px;
  text-align: center;
  font-size: 13px;
  color: var(--color-mute);
}
.mpm-viewport {
  height: 65vh;
  overflow: hidden;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  background: var(--color-canvas-soft);
  cursor: grab;
  display: flex;
  align-items: center;
  justify-content: center;
}
.mpm-viewport.is-dragging {
  cursor: grabbing;
}
.mpm-content {
  transform-origin: center center;
  transition: transform 0.05s linear;
}
.mpm-content :deep(svg) {
  display: block;
  max-width: none;
}
.mpm-loading,
.mpm-error {
  color: var(--color-mute);
  font-size: 14px;
}
.mpm-error {
  color: var(--color-error);
}
</style>
