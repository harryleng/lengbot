<script setup>
/**
 * 透传 reka-ui 的 DialogClose，配合 as-child 可以把任意元素变成关闭触发器：
 * <DialogClose as-child><Button variant="outline">取消</Button></DialogClose>
 */
import { DialogClose as RekaDialogClose } from 'reka-ui'
</script>

<template>
  <RekaDialogClose>
    <slot />
  </RekaDialogClose>
</template>
