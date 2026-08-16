<script setup>
import { computed } from 'vue'
import { cn } from '@/utils/cn'

/**
 * 分隔线。装饰性用途时 aria-hidden，避免屏幕阅读器读出无意义节点。
 */
const props = defineProps({
  orientation: { type: String, default: 'horizontal' },
  decorative: { type: Boolean, default: true },
  class: { type: [String, Array, Object], default: '' },
})

const classes = computed(() =>
  cn('shrink-0 bg-border', props.orientation === 'vertical' ? 'h-full w-px' : 'h-px w-full', props.class)
)
</script>

<template>
  <div
    :class="classes"
    :role="decorative ? 'none' : 'separator'"
    :aria-hidden="decorative ? 'true' : undefined"
    :aria-orientation="decorative ? undefined : orientation"
  />
</template>
