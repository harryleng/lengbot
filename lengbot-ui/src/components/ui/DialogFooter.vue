<script setup>
import { computed } from 'vue'
import { cn } from '@/utils/cn'

/**
 * 移动端按钮竖排且主操作在上（column-reverse），桌面端横排右对齐。
 * 这是 shadcn 官方的 mobile-first 处理方式。
 */
const props = defineProps({
  class: { type: [String, Array, Object], default: '' },
})

const classes = computed(() => cn('flex flex-col-reverse gap-2 sm:flex-row sm:justify-end', props.class))
</script>

<template>
  <div :class="classes">
    <slot />
  </div>
</template>
