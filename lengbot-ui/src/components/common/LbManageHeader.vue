<template>
  <div class="lb-manage-header">
    <h2 class="lb-manage-header__title">{{ title }}</h2>
    <div class="lb-manage-header__actions">
      <slot name="filters" />
      <a-input
        v-if="searchable"
        v-model:value="keywordModel"
        :placeholder="searchPlaceholder"
        :style="{ width: searchWidth + 'px' }"
        allow-clear
        @change="emit('searchChange', keywordModel)"
        @press-enter="emit('searchEnter', keywordModel)"
      >
        <template v-if="$slots.searchPrefix" #prefix><slot name="searchPrefix" /></template>
      </a-input>
      <button
        v-if="showRefresh"
        type="button"
        class="lb-btn lb-btn--ghost"
        :disabled="refreshDisabled"
        @click="emit('refresh')"
      >
        <ReloadOutlined :spin="refreshDisabled" />
        刷新
      </button>
      <button
        v-if="showCreate"
        type="button"
        class="lb-btn"
        :class="createClass"
        @click="emit('create')"
      >
        <PlusOutlined />
        {{ createText }}
      </button>
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup>
/**
 * 管理页头部（h2 标题 + 右侧 action bar）
 * 统一项目中管理列表页的 header 写法（参考 TaskCenter / SessionManage 模式）。
 * 提供：h2 标题（左上角）、搜索框（v-model）、筛选 slot、刷新、新建按钮（全部靠右）。
 * 复杂筛选（tag 下拉、时间筛选等）通过 #filters slot 透传。
 * 特殊操作按钮（如导入、批量操作）通过 #actions slot 透传。
 *
 * create-variant:
 *   - 'default'（默认）= 普通主按钮
 *   - 'neon'           = 青紫霓虹渐变 + 光晕（用于 Agent 管理页主操作）
 */
import { ReloadOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { computed } from 'vue'

const props = defineProps({
  title: { type: String, required: true },
  searchable: { type: Boolean, default: true },
  searchPlaceholder: { type: String, default: '搜索...' },
  searchWidth: { type: Number, default: 220 },
  modelValue: { type: String, default: '' },
  showRefresh: { type: Boolean, default: true },
  refreshDisabled: { type: Boolean, default: false },
  showCreate: { type: Boolean, default: true },
  createText: { type: String, default: '新建' },
  createVariant: { type: String, default: 'default' },
})

const emit = defineEmits(['update:modelValue', 'refresh', 'create', 'searchChange', 'searchEnter'])

const keywordModel = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const createClass = computed(() => {
  if (props.createVariant === 'neon') return 'lb-btn--neon'
  return 'lb-btn--primary'
})
</script>

<style scoped>
.lb-manage-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.lb-manage-header__title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #ffffff;
  text-shadow: 0 0 18px rgba(34, 211, 238, 0.25);
}
.lb-manage-header__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
