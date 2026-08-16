<template>
  <div class="lb-stat-card">
    <!-- 骨架屏：icon + value + label 三块 shimmer，与 Dashboard 动画一致 -->
    <template v-if="loading">
      <div class="lb-stat-card__icon">
        <div class="lb-skeleton-block lb-stat-card__sk-icon"></div>
      </div>
      <div class="lb-stat-card__info">
        <div class="lb-skeleton-block lb-stat-card__sk-value"></div>
        <div class="lb-skeleton-block lb-stat-card__sk-label"></div>
      </div>
    </template>
    <!-- 正常内容 -->
    <template v-else>
      <div class="lb-stat-card__icon" :style="iconStyle">
        <component :is="icon" v-if="icon" />
      </div>
      <div class="lb-stat-card__info">
        <div class="lb-stat-card__value" :class="valuePopClass">{{ displayValue }}</div>
        <div class="lb-stat-card__label">{{ label }}</div>
      </div>
    </template>
  </div>
</template>

<script setup>
/**
 * 统计卡片
 * 统一 DashboardView.vue / Observability.vue 中 .stat-card 的重复写法。
 * 渐变色通过 accent prop 预设，也可通过 gradient prop 自定义。
 * value 为 number 类型时自动监听变化触发 lb-count-pop 反馈动画。
 * loading 为 true 时显示 shimmer 骨架屏（icon + value + label 三块）。
 */
import { computed } from 'vue'
import { useCountPop } from '../../composables/useCountPop'

const props = defineProps({
  icon: { type: [Object, Function, String], default: null },
  label: { type: String, required: true },
  value: { type: [String, Number], default: '-' },
  accent: {
    type: String,
    default: 'blue',
    validator: (v) => ['blue', 'purple', 'teal', 'orange', 'green', 'red', 'custom'].includes(v),
  },
  gradient: { type: String, default: '' },
  loading: { type: Boolean, default: false },
})

const ACCENT_PRESETS = {
  blue: 'linear-gradient(135deg, #1890ff, #096dd9)',
  purple: 'linear-gradient(135deg, #722ed1, #531dab)',
  teal: 'linear-gradient(135deg, #13c2c2, #08979c)',
  orange: 'linear-gradient(135deg, #fa8c16, #d46b08)',
  green: 'linear-gradient(135deg, #52c41a, #389e0d)',
  red: 'linear-gradient(135deg, #f5222d, #cf1322)',
}

const iconStyle = computed(() => ({
  background: props.gradient || ACCENT_PRESETS[props.accent] || ACCENT_PRESETS.blue,
}))

const displayValue = computed(() => (props.value == null || props.value === '' ? '-' : props.value))

// 数字变化反馈动画（number → number 才触发）
const valuePopClass = useCountPop(() => props.value)
</script>

<style scoped>
.lb-stat-card__info {
  min-width: 0;
}
.lb-stat-card__sk-icon {
  width: 24px;
  height: 24px;
  border-radius: 6px;
}
.lb-stat-card__sk-value {
  width: 80px;
  height: 24px;
  margin-bottom: 8px;
}
.lb-stat-card__sk-label {
  width: 48px;
  height: 12px;
}
</style>
