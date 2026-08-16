<template>
  <div class="lb-collapse" :class="{ 'is-open': open }">
    <div class="lb-collapse__inner">
      <slot />
    </div>
  </div>
</template>

<script setup>
/**
 * 通用展开/收起过渡容器
 * 用 grid-template-rows 0fr↔1fr 实现平滑高度动画，纯 CSS 可中断，
 * 内容常驻 DOM，避免 v-if 切换时的生硬跳变。内层内容自身的 max-height/滚动照常生效。
 */
defineProps({
  open: { type: Boolean, default: false },
})
</script>

<style scoped>
.lb-collapse {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.24s ease;
}
.lb-collapse.is-open {
  grid-template-rows: 1fr;
}
.lb-collapse__inner {
  min-height: 0;
  overflow: hidden;
  opacity: 0;
  transition: opacity 0.18s ease;
}
.lb-collapse.is-open .lb-collapse__inner {
  opacity: 1;
}
</style>
