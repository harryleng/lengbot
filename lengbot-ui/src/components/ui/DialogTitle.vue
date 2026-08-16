<script setup>
import { computed } from 'vue'
import { DialogTitle as RekaDialogTitle } from 'reka-ui'
import { cn } from '@/utils/cn'

/**
 * 必须使用 reka-ui 的 DialogTitle，它会自动绑定 aria-labelledby，
 * 手写 <h2> 会丢失无障碍关联。
 */
const props = defineProps({
  class: { type: [String, Array, Object], default: '' },
})

const classes = computed(() => cn('text-lg font-semibold leading-none tracking-tight', props.class))
</script>

<template>
  <RekaDialogTitle :class="classes">
    <slot />
  </RekaDialogTitle>
</template>
