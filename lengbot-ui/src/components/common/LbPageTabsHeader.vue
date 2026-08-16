<template>
  <div class="lb-page-tabs-header">
    <h2 class="lb-page-tabs-header__title">{{ title }}</h2>
    <nav v-if="tabs.length" class="lb-page-tabs-header__tabs" :aria-label="ariaLabel">
      <button
        v-for="t in tabs"
        :key="t.key"
        type="button"
        class="lb-page-tabs-header__tab"
        :class="{ active: activeKey === t.key }"
        @click="emit('update:activeKey', t.key)"
      >
        {{ t.label }}
      </button>
    </nav>
    <div v-if="$slots.actions || $slots.default" class="lb-page-tabs-header__right">
      <slot name="actions" />
      <slot />
    </div>
  </div>
</template>

<script setup>
/**
 * 多 tab 页顶部头部（Yuxi PageHeader 风格）
 * 单行布局：h2 标题 + 竖线分隔的 inline tabs + 靠右 actions。
 * 参考：TaskCenter / SessionManage 的 h2 左对齐，以及 Yuxi PageHeader 的 title | tabs 内联布局。
 * 用于 Extensions / Eval / KnowledgeDetail 等多 tab 聚合页。
 */
defineProps({
  title: { type: String, required: true },
  tabs: { type: Array, default: () => [] },
  activeKey: { type: String, default: '' },
  ariaLabel: { type: String, default: '视图切换' },
})

const emit = defineEmits(['update:activeKey'])
</script>

<style scoped>
.lb-page-tabs-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
  flex-shrink: 0;
}
.lb-page-tabs-header__title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--color-ink);
  white-space: nowrap;
}
.lb-page-tabs-header__tabs {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding-left: 16px;
  border-left: 1px solid var(--color-hairline);
  flex-shrink: 0;
}
.lb-page-tabs-header__tab {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--color-mute);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition:
    background-color 0.2s,
    color 0.2s;
}
.lb-page-tabs-header__tab:hover {
  color: var(--color-ink);
  background: var(--color-canvas-soft-2);
}
.lb-page-tabs-header__tab.active {
  color: var(--color-link);
  background: var(--color-link-bg-soft);
}
.lb-page-tabs-header__right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: auto;
}
</style>
