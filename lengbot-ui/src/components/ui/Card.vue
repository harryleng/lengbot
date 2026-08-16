<script setup>
import { computed } from 'vue'
import { cn } from '@/utils/cn'

/**
 * shadcn 风格 Card 容器
 *
 * 与 CardHeader / CardTitle / CardDescription / CardContent / CardFooter 配套使用。
 * 全部由 Tailwind token 驱动，深色模式随项目 --color-* 自动切换。
 */
const props = defineProps({
  class: { type: [String, Array, Object], default: '' },
})

const classes = computed(() =>
  cn('rounded-lg border border-border bg-card text-card-foreground shadow-sm', props.class)
)
</script>

<template>
  <div :class="classes">
    <slot />
  </div>
</template>
