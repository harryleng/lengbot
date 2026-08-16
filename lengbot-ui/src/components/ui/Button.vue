<script setup>
import { computed } from 'vue'
import { cva } from 'class-variance-authority'
import { cn } from '@/utils/cn'

/**
 * shadcn 风格 Button（Phase 2 首个落地组件）
 *
 * 与 antd 的 a-button 并存：本组件完全由 Tailwind 工具类驱动，
 * 不依赖 antd 主题，也不会影响 antd 组件样式。
 */
const props = defineProps({
  variant: { type: String, default: 'default' },
  size: { type: String, default: 'default' },
  as: { type: String, default: 'button' },
  class: { type: [String, Array, Object], default: '' },
})

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 cursor-pointer',
  {
    variants: {
      variant: {
        default: 'bg-primary text-primary-foreground hover:opacity-90',
        secondary: 'bg-secondary text-secondary-foreground hover:opacity-80',
        outline: 'border border-input bg-background text-foreground hover:bg-accent hover:text-accent-foreground',
        ghost: 'text-foreground hover:bg-accent hover:text-accent-foreground',
        /* 用 destructive-foreground 而非 primary-foreground：
           深色模式下 primary 会反转成近白，红底配近白文字对比度不足。 */
        destructive: 'bg-destructive text-destructive-foreground hover:opacity-90',
        /* link 用蓝色点缀（--ring）而非 primary：
           深色下 primary 反转成近白，会失去「链接」的视觉语义。
           这正好呼应决策 1(b)——蓝色只作为链接/聚焦点缀出现。 */
        link: 'text-ring underline-offset-4 hover:underline',
      },
      size: {
        default: 'h-9 px-4 py-2',
        sm: 'h-8 rounded-md px-3 text-xs',
        lg: 'h-10 rounded-md px-6',
        icon: 'h-9 w-9',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  }
)

const classes = computed(() => cn(buttonVariants({ variant: props.variant, size: props.size }), props.class))
</script>

<template>
  <!-- data-tw 标记：告知全局样式此元素由 Tailwind 工具类接管，
       用于绕开 antd reset 对表单元素的 inherit 压制（详见 styles/tailwind.css） -->
  <component :is="as" data-tw :class="classes">
    <slot />
  </component>
</template>
