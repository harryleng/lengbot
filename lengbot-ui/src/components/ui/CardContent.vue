<script setup>
import { computed } from 'vue'
import { cn } from '@/utils/cn'

const props = defineProps({
  class: { type: [String, Array, Object], default: '' },
})

/* pt-0：紧跟 CardHeader 时避免上下双倍留白 */
const classes = computed(() => cn('p-6 pt-0', props.class))
</script>

<template>
  <div :class="classes">
    <slot />
  </div>
</template>
