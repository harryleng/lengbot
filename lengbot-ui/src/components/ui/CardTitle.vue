<script setup>
import { computed } from 'vue'
import { cn } from '@/utils/cn'

/**
 * 标题语义标签可通过 as 指定（默认 h3），保证页面 heading 层级正确（a11y）。
 */
const props = defineProps({
  as: { type: String, default: 'h3' },
  class: { type: [String, Array, Object], default: '' },
})

const classes = computed(() => cn('text-base font-semibold leading-none tracking-tight', props.class))
</script>

<template>
  <component :is="as" :class="classes">
    <slot />
  </component>
</template>
