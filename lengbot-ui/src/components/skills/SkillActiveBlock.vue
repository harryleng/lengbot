<template>
  <div class="skill-active-block">
    <button type="button" class="skill-active-header" @click="toggle($event)">
      <ThunderboltOutlined class="skill-active-icon" />
      <span class="skill-active-title">{{ title }}</span>
      <LoadingOutlined v-if="!isDone" class="skill-active-spinner" />
      <RightOutlined :class="{ expanded: expanded }" class="skill-active-toggle" />
    </button>
    <CollapseTransition :open="expanded">
      <div class="skill-active-body">
        <SkillItemRenderer
          v-for="(sk, si) in skills"
          :key="sk.slug || sk.name || si"
          :skill="sk"
          class="skill-active-item"
        />
      </div>
    </CollapseTransition>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { ThunderboltOutlined, LoadingOutlined, RightOutlined } from '@ant-design/icons-vue'
import SkillItemRenderer from './SkillItemRenderer.vue'
import CollapseTransition from '../common/CollapseTransition.vue'
import { formatSkillActiveTitle } from './skillRegistry.js'

const props = defineProps({
  event: { type: Object, required: true },
  isDone: { type: Boolean, default: true },
  defaultExpanded: { type: Boolean, default: true },
})

const emit = defineEmits(['heightChange'])

const expanded = ref(props.defaultExpanded)
let userToggled = false

const skills = computed(() => props.event?.skills || [])
const title = computed(() => formatSkillActiveTitle(skills.value))

watch(
  () => props.defaultExpanded,
  (val) => {
    if (!userToggled) expanded.value = val
  },
  { immediate: true }
)

function toggle(event) {
  userToggled = true
  expanded.value = !expanded.value
  nextTick(() => emit('heightChange', event))
}
</script>

<style scoped>
.skill-active-block {
  border-radius: 8px;
  border: 1px solid var(--color-purple-border);
  background: var(--color-purple-bg);
  overflow: hidden;
}
.skill-active-header {
  appearance: none;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
  color: var(--color-text-dark);
}
.skill-active-icon {
  font-size: 14px;
  color: #db2777;
}
.skill-active-title {
  flex: 1;
  font-weight: 500;
}
.skill-active-spinner {
  color: var(--color-mute);
}
.skill-active-toggle {
  font-size: 10px;
  color: var(--color-mute);
  transition: transform 0.2s;
}
.skill-active-toggle.expanded {
  transform: rotate(90deg);
}
.skill-active-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 12px 12px;
}
.skill-active-item + .skill-active-item {
  margin-top: 0;
}
</style>

<style>
[data-theme='dark'] .skill-active-block {
  border-color: var(--color-purple-border);
}
[data-theme='dark'] .skill-active-icon {
  color: #f472b6;
}
</style>
