<template>
  <component :is="iconComp" v-if="iconComp" />
  <span v-else class="dynamic-icon-fallback">{{ fallbackLetter }}</span>
</template>

<script setup>
import { computed } from 'vue'
import { resolveIcon } from '../utils/iconRegistry'

const props = defineProps({
  // Ant Design 图标组件名，如 GlobalOutlined
  name: { type: String, default: '' },
  // 无法解析图标时的降级文本（取首字母）
  fallback: { type: String, default: '' },
})

const iconComp = computed(() => resolveIcon(props.name))
const fallbackLetter = computed(() => (props.fallback || '?')[0].toUpperCase())
</script>

<style scoped>
.dynamic-icon-fallback {
  font-weight: 700;
}
</style>
