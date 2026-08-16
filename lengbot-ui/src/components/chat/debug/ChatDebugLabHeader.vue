<template>
  <a-layout-header class="debug-header">
    <div class="debug-header-brand">
      <BugOutlined class="debug-brand-icon" />
      <span class="debug-brand-title">Chat Debug Lab</span>
    </div>
    <a-menu
      :selected-keys="selectedKeys"
      mode="horizontal"
      :items="navItems"
      class="debug-header-nav"
      @click="onNavClick"
    />
    <div class="debug-header-actions">
      <a-tooltip title="渲染注册表">
        <button type="button" class="debug-icon-btn" @click="$emit('open-registry')">
          <TableOutlined />
        </button>
      </a-tooltip>
      <a-tooltip :title="isDark ? '切换浅色模式' : '切换深色模式'">
        <button type="button" class="debug-icon-btn" @click="toggleTheme">
          <BulbFilled v-if="isDark" />
          <BulbOutlined v-else />
        </button>
      </a-tooltip>
    </div>
  </a-layout-header>
</template>

<script setup>
import { BugOutlined, BulbFilled, BulbOutlined, TableOutlined } from '@ant-design/icons-vue'
import { useTheme } from '@/composables/useTheme'

defineProps({
  navItems: { type: Array, default: () => [] },
  selectedKeys: { type: Array, default: () => [] },
})

const emit = defineEmits(['nav-click', 'open-registry'])

const { isDark, toggleTheme } = useTheme()

function onNavClick(info) {
  emit('nav-click', info)
}
</script>

<style scoped>
.debug-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 24px;
  height: 56px;
  line-height: 56px;
  background: var(--color-canvas);
  border-bottom: 1px solid var(--color-hairline);
}

.debug-header-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  color: var(--color-ink);
}

.debug-brand-icon {
  font-size: 20px;
  color: var(--color-warning);
}

.debug-brand-title {
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
}

.debug-header-nav {
  flex: 1;
  min-width: 0;
  background: transparent;
  border-bottom: none;
  line-height: 54px;
}

.debug-header-nav :deep(.ant-menu-item) {
  color: var(--color-body);
  transition: color 0.2s;
}

.debug-header-nav :deep(.ant-menu-item:hover) {
  color: var(--color-ink);
}

.debug-header-nav :deep(.ant-menu-item-selected) {
  color: var(--color-ink) !important;
}

.debug-header-nav :deep(.ant-menu-item-selected::after) {
  border-bottom-color: var(--color-ink) !important;
}

.debug-header-nav :deep(.ant-menu-item::after) {
  border-bottom-width: 2px;
}

.debug-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.debug-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-sm);
  background: var(--color-canvas-soft);
  color: var(--color-body);
  cursor: pointer;
  font-size: 16px;
  transition:
    background 0.2s,
    color 0.2s,
    border-color 0.2s;
}

.debug-icon-btn:hover {
  background: var(--color-canvas-soft-2);
  border-color: var(--color-hairline-strong);
  color: var(--color-ink);
}

@media (max-width: 960px) {
  .debug-header {
    flex-wrap: wrap;
    height: auto;
    padding: 8px 16px;
  }

  .debug-header-nav {
    order: 3;
    width: 100%;
  }
}
</style>
