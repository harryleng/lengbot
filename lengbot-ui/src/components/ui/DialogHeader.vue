<script setup>
import { computed } from 'vue'
import { cn } from '@/utils/cn'

const props = defineProps({
  class: { type: [String, Array, Object], default: '' },
})

const classes = computed(() => cn('flex flex-col space-y-1.5 text-left', props.class))
</script>

<template>
  <div :class="classes">
    <slot />
  </div>
</template>
