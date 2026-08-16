<template>
  <div class="lb-empty">
    <component :is="icon" v-if="icon" class="lb-empty__icon" />
    <p class="lb-empty__title">{{ title }}</p>
    <p v-if="desc" class="lb-empty__desc">{{ desc }}</p>
    <div v-if="$slots.action" class="lb-empty__action">
      <slot name="action" />
    </div>
  </div>
</template>

<script setup>
/**
 * 空状态展示
 * 统一项目中 15+ 处 .empty-state + .empty-icon 的重复写法。
 * 提供图标、主文案、描述、操作按钮 slot。
 */
defineProps({
  icon: { type: [Object, Function, String], default: null },
  title: { type: String, default: '暂无数据' },
  desc: { type: String, default: '' },
})
</script>

<style scoped>
.lb-empty__action {
  margin-top: 16px;
}
</style>
