<script setup>
import { computed } from 'vue'
import { cn } from '@/utils/cn'

/**
 * shadcn 风格 Textarea
 * 智能体后台里 Prompt 编辑场景高频使用，与 Input 同样需要 data-tw 标记。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },
  rows: { type: [String, Number], default: 3 },
  class: { type: [String, Array, Object], default: '' },
})

defineEmits(['update:modelValue'])

const classes = computed(() =>
  cn(
    'flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground shadow-sm transition-colors',
    'placeholder:text-muted-foreground',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1',
    'disabled:cursor-not-allowed disabled:opacity-50',
    props.class
  )
)
</script>

<template>
  <textarea
    data-tw
    :rows="rows"
    :value="modelValue"
    :class="classes"
    @input="$emit('update:modelValue', $event.target.value)"
  />
</template>
