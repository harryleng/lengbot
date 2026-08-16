<script setup>
import { computed } from 'vue'
import { cn } from '@/utils/cn'

/**
 * 表单标签。for 属性必填才能点击聚焦到输入框（a11y 基本要求）。
 */
const props = defineProps({
  for: { type: String, default: undefined },
  class: { type: [String, Array, Object], default: '' },
})

const classes = computed(() =>
  cn(
    'text-sm font-medium leading-none text-foreground peer-disabled:cursor-not-allowed peer-disabled:opacity-70',
    props.class
  )
)
</script>

<template>
  <label :for="props.for" :class="classes">
    <slot />
  </label>
</template>
