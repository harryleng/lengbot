<template>
  <Transition name="lb-graph-panel">
    <div v-if="visible" class="lb-graph-node-panel" :class="{ 'lb-graph-node-panel--edge': isEdge }">
      <div class="lb-graph-node-panel__header">
        <span class="lb-graph-node-panel__title">
          <component :is="isEdge ? iconEdge : iconNode" v-if="isEdge ? iconEdge : iconNode" />
          {{ title || (isEdge ? '边详情' : '节点详情') }}
        </span>
        <button class="lb-graph-node-panel__close" @click="emit('close')">
          <CloseOutlined />
        </button>
      </div>
      <div class="lb-graph-node-panel__body">
        <a-descriptions :column="1" size="small" bordered>
          <a-descriptions-item v-for="f in fields" :key="f.label" :label="f.label">
            <span :class="{ 'lb-graph-node-panel__empty': f.value == null || f.value === '' }">
              {{ f.value == null || f.value === '' ? '-' : f.value }}
            </span>
          </a-descriptions-item>
        </a-descriptions>
      </div>
      <div v-if="$slots.actions" class="lb-graph-node-panel__actions">
        <slot name="actions" />
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { CloseOutlined } from '@ant-design/icons-vue'

/**
 * 图谱节点/边详情浮窗（替代原 Drawer 方案）
 *
 * 设计意图：
 * - absolute 定位在画布右上角，不遮挡其他节点
 * - 不走 antd Drawer 的 portal 到 body，保留在图谱容器内，视觉聚焦
 * - 节点/边共用同一组件，通过 isEdge 切换标题与色彩
 *
 * 父容器必须 position: relative（图谱画布外层已有）
 */
defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '' },
  isEdge: { type: Boolean, default: false },
  fields: { type: Array, default: () => [] },
  iconNode: { type: [Object, Function], default: null },
  iconEdge: { type: [Object, Function], default: null },
})

const emit = defineEmits(['close'])
</script>

<style scoped>
.lb-graph-node-panel {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 320px;
  max-height: calc(100% - 32px);
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-4);
  z-index: 10;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.lb-graph-node-panel--edge {
  border-left: 3px solid var(--purple-500);
}

.lb-graph-node-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--color-hairline);
  background: var(--color-canvas-soft);
}
.lb-graph-node-panel__title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-ink);
}
.lb-graph-node-panel__close {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--color-mute);
  cursor: pointer;
  padding: 4px;
  border-radius: var(--radius-xs);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  line-height: 1;
}
.lb-graph-node-panel__close:hover {
  background: var(--color-canvas-soft-2);
  color: var(--color-ink);
}

.lb-graph-node-panel__body {
  padding: 12px;
  overflow-y: auto;
  flex: 1;
}
.lb-graph-node-panel__body :deep(.ant-descriptions) {
  font-size: 12px;
}
.lb-graph-node-panel__body :deep(.ant-descriptions-item-label) {
  width: 72px;
  color: var(--color-mute);
  font-weight: 500;
}
.lb-graph-node-panel__body :deep(.ant-descriptions-item-content) {
  color: var(--color-body);
  word-break: break-word;
}
.lb-graph-node-panel__empty {
  color: var(--color-mute);
  font-style: italic;
}

.lb-graph-node-panel__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid var(--color-hairline);
  background: var(--color-canvas-soft);
}

/* 浮窗进出场动画：从右上角淡入并轻微滑入 */
.lb-graph-panel-enter-active,
.lb-graph-panel-leave-active {
  transition:
    opacity var(--duration-fast, 0.15s) ease,
    transform var(--duration-fast, 0.15s) ease;
}
.lb-graph-panel-enter-from,
.lb-graph-panel-leave-to {
  opacity: 0;
  transform: translateX(12px);
}

/* 深色模式：浮窗阴影更强、与画布对比度提升 */
:deep([data-theme='dark']) .lb-graph-node-panel,
[data-theme='dark'] .lb-graph-node-panel {
  background: #1f1f1f;
  border-color: var(--color-hairline-strong);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5);
}
</style>
