<script setup>
import { computed } from 'vue'
import { cn } from '@/utils/cn'

const props = defineProps({
  class: { type: [String, Array, Object], default: '' },
})

const classes = computed(() => cn('flex items-center p-6 pt-0', props.class))
</script>

<template>
  <div :class="classes">
    <slot />
  </div>
</template>
