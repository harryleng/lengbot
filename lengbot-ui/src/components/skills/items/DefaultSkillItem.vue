<template>
  <div class="skill-item default" :style="itemStyle">
    <component :is="meta.icon" class="skill-item-icon" />
    <div class="skill-item-body">
      <div class="skill-item-head">
        <span class="skill-item-name">{{ meta.displayName }}</span>
        <span v-if="meta.tag" class="skill-item-tag">{{ meta.tag }}</span>
        <span v-if="skill.builtin || meta.builtin" class="skill-item-badge">内置</span>
      </div>
      <p v-if="displayDescription" class="skill-item-desc">{{ displayDescription }}</p>
      <p v-if="meta.hint" class="skill-item-hint">{{ meta.hint }}</p>
      <span v-if="skill.slug" class="skill-item-slug">{{ skill.slug }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  skill: { type: Object, required: true },
  meta: { type: Object, required: true },
})

const itemStyle = computed(() => ({
  '--skill-accent': props.meta.accent,
  '--skill-bg': props.meta.bg,
  '--skill-border': props.meta.border,
}))

const displayDescription = computed(() => props.skill.description || props.meta.description || '')
</script>

<style scoped>
.skill-item {
  display: flex;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--skill-bg, var(--color-purple-bg));
  border: 1px solid var(--skill-border, #f9a8d4);
}
.skill-item-icon {
  flex-shrink: 0;
  margin-top: 2px;
  font-size: 16px;
  color: var(--skill-accent, #db2777);
}
.skill-item-body {
  flex: 1;
  min-width: 0;
}
.skill-item-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.skill-item-name {
  font-weight: 600;
  font-size: 13px;
  color: var(--skill-accent, #9d174d);
}
.skill-item-tag {
  font-size: 10px;
  padding: 0 6px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.75);
  color: var(--skill-accent);
  border: 1px solid var(--skill-border);
}
.skill-item-badge {
  font-size: 10px;
  padding: 0 5px;
  border-radius: 4px;
  background: #3b82f6;
  color: #fff;
}
.skill-item-desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.45;
  color: var(--color-body);
}
.skill-item-hint {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.4;
  color: var(--color-mute);
  font-style: italic;
}
.skill-item-slug {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--color-mute);
  font-family: ui-monospace, monospace;
}
</style>
