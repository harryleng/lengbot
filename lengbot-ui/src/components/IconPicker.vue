<template>
  <a-select
    :value="value || undefined"
    show-search
    allow-clear
    placeholder="选择图标（可选，为空则显示首字母）"
    :filter-option="filterOption"
    :get-popup-container="getPopupContainer"
    :dropdown-match-select-width="360"
    :disabled="disabled"
    style="width: 100%"
    @change="onChange"
  >
    <template #suffixIcon>
      <DynamicIcon v-if="value" :name="value" />
    </template>
    <a-select-option v-for="name in options" :key="name" :value="name" :label="name">
      <span class="icon-option">
        <DynamicIcon :name="name" class="icon-option-glyph" />
        <span class="icon-option-name">{{ name }}</span>
      </span>
    </a-select-option>
  </a-select>
</template>

<script setup>
import DynamicIcon from './DynamicIcon.vue'
import { ICON_OPTIONS } from '../utils/iconOptions'

const props = defineProps({
  value: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['update:value'])

const options = ICON_OPTIONS

function onChange(val) {
  emit('update:value', val || '')
}

function filterOption(input, option) {
  return String(option.value).toLowerCase().includes(input.toLowerCase())
}

function getPopupContainer() {
  return document.body
}
</script>

<style scoped>
.icon-option {
  display: flex;
  align-items: center;
  gap: 10px;
}
.icon-option-glyph {
  font-size: 16px;
  color: var(--color-ink);
}
.icon-option-name {
  font-size: 13px;
  color: var(--color-body);
}
</style>
