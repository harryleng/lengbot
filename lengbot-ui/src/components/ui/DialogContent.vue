<script setup>
import { computed } from 'vue'
import { DialogPortal, DialogOverlay, DialogContent as RekaDialogContent, DialogClose } from 'reka-ui'
import { cn } from '@/utils/cn'

/**
 * Dialog 内容区（含遮罩 + 定位 + 右上角关闭按钮）
 *
 * z-index 说明：antd 的 Modal 默认 z-index 为 1000，
 * 这里用 z-1000/1001 与其保持同一量级，避免与 antd 弹层互相盖住。
 */
const props = defineProps({
  class: { type: [String, Array, Object], default: '' },
  showClose: { type: Boolean, default: true },
})

const classes = computed(() =>
  cn(
    'fixed left-1/2 top-1/2 z-[1001] grid w-full max-w-lg -translate-x-1/2 -translate-y-1/2 gap-4',
    'rounded-lg border border-border bg-card p-6 text-card-foreground shadow-lg',
    'focus:outline-none',
    props.class
  )
)
</script>

<template>
  <DialogPortal>
    <DialogOverlay class="fixed inset-0 z-[1000] bg-black/50 backdrop-blur-[2px]" />
    <RekaDialogContent :class="classes">
      <slot />

      <DialogClose
        v-if="showClose"
        data-tw
        aria-label="关闭"
        class="absolute right-4 top-4 inline-flex h-6 w-6 items-center justify-center rounded-sm text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring cursor-pointer"
      >
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="M18 6 6 18" />
          <path d="m6 6 12 12" />
        </svg>
      </DialogClose>
    </RekaDialogContent>
  </DialogPortal>
</template>
