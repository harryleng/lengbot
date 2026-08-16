<script setup>
import { computed } from 'vue'
import { cva } from 'class-variance-authority'
import { cn } from '@/utils/cn'

/**
 * shadcn 风格 Badge
 *
 * 在 AI 智能体后台里主要用于状态标记（运行中/已停止/异常/草稿），
 * 因此在官方 4 个变体基础上补了 success / warning 两个语义色，
 * 二者同样桥接到项目既有的 --color-success / --color-warning。
 */
const props = defineProps({
  variant: { type: String, default: 'default' },
  class: { type: [String, Array, Object], default: '' },
})

const badgeVariants = cva(
  'inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium transition-colors whitespace-nowrap',
  {
    variants: {
      variant: {
        default: 'border-transparent bg-primary text-primary-foreground',
        secondary: 'border-transparent bg-secondary text-secondary-foreground',
        outline: 'border-border text-foreground',
        destructive: 'border-transparent bg-destructive text-destructive-foreground',
        success: 'border-transparent bg-success text-success-foreground',
        warning: 'border-transparent bg-warning text-warning-foreground',
      },
    },
    defaultVariants: { variant: 'default' },
  }
)

const classes = computed(() => cn(badgeVariants({ variant: props.variant }), props.class))
</script>

<template>
  <span :class="classes">
    <slot />
  </span>
</template>
