<script setup>
import { DialogRoot } from 'reka-ui'

/**
 * shadcn 风格 Dialog（基于 reka-ui，无障碍能力开箱即用：
 * 焦点陷阱、ESC 关闭、aria-modal、滚动锁定）
 *
 * 与 demo 版的区别：这里做成【组合式】而非写死按钮的一体化弹窗，
 * 业务侧可自由拼装 DialogContent / DialogHeader / DialogFooter，
 * 才能覆盖真实后台里「表单弹窗」「二次确认」「详情抽屉」等不同形态。
 *
 * 用法：
 * <Dialog v-model:open="open">
 *   <DialogContent>
 *     <DialogHeader>
 *       <DialogTitle>标题</DialogTitle>
 *       <DialogDescription>描述</DialogDescription>
 *     </DialogHeader>
 *     内容
 *     <DialogFooter>...</DialogFooter>
 *   </DialogContent>
 * </Dialog>
 */
defineProps({
  open: { type: Boolean, default: false },
})

const emit = defineEmits(['update:open'])
</script>

<template>
  <DialogRoot :open="open" @update:open="emit('update:open', $event)">
    <slot />
  </DialogRoot>
</template>
