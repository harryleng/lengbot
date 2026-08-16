<script setup>
import { computed } from 'vue'
import { cn } from '@/utils/cn'

/**
 * shadcn 风格 Input
 *
 * ⚠️ 必须带 data-tw 标记：antd 的 reset.css 有一条【不在任何 @layer 中】的规则
 *   input, button, select, textarea { font-size/color/line-height: inherit }
 * 会压制 Tailwind 工具类。data-tw 触发 styles/tailwind.css 里的 revert-layer 补丁。
 */
const props = defineProps({
  modelValue: { type: [String, Number], default: '' },
  type: { type: String, default: 'text' },
  class: { type: [String, Array, Object], default: '' },
})

defineEmits(['update:modelValue'])

const classes = computed(() =>
  cn(
    'flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm text-foreground shadow-sm transition-colors',
    'placeholder:text-muted-foreground',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1',
    'disabled:cursor-not-allowed disabled:opacity-50',
    props.class
  )
)
</script>

<template>
  <input
    data-tw
    :type="type"
    :value="modelValue"
    :class="classes"
    @input="$emit('update:modelValue', $event.target.value)"
  />
</template>
