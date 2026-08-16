<template>
  <div class="lb-entity-grid" :style="gridStyle">
    <slot />
    <div v-if="$slots.empty" class="lb-entity-grid__empty">
      <slot name="empty" />
    </div>
  </div>
</template>

<script setup>
/**
 * 卡片网格容器
 * 统一项目中 10 处 .agent-grid / .card-grid / .provider-grid / .knowledge-grid
 * 的重复写法，列宽通过 min-card-width prop 控制（默认 280px）。
 * 空态通过 #empty slot 透传（通常配 LbEmptyState）。
 */
import { computed } from 'vue'

const props = defineProps({
  minCardWidth: { type: Number, default: 280 },
  gap: { type: Number, default: 16 },
})

const gridStyle = computed(() => ({
  '--lb-grid-min': props.minCardWidth + 'px',
  gap: props.gap + 'px',
}))
</script>

<style scoped>
.lb-entity-grid__empty {
  grid-column: 1 / -1;
}
</style>
