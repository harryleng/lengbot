<script setup>
import { computed } from 'vue'
import { cn } from '@/utils/cn'

const props = defineProps({
  class: { type: [String, Array, Object], default: '' },
})

const classes = computed(() => cn('text-sm text-muted-foreground', props.class))
</script>

<template>
  <p :class="classes">
    <slot />
  </p>
</template>
