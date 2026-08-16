<script setup>
import { computed } from 'vue'
import { cn } from '@/utils/cn'

/**
 * 骨架屏占位。用于列表/详情的 loading 状态，
 * 比转圈 spinner 更少布局抖动。
 *
 * ⚠️ 用 bg-skeleton 而非 shadcn 官方的 bg-muted：
 * 本项目的 --muted 是 #fafafa，与白色画布几乎无差别，骨架屏会看不见。
 */
const props = defineProps({
  class: { type: [String, Array, Object], default: '' },
})

const classes = computed(() => cn('animate-pulse rounded-md bg-skeleton', props.class))
</script>

<template>
  <div :class="classes" aria-hidden="true" />
</template>
