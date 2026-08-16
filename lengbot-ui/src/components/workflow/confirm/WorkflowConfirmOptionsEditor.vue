<template>
  <div class="confirm-options-editor">
    <div class="options-head">
      <span class="body-label">选项</span>
      <span class="body-hint">逐项添加，支持含逗号的选项文案</span>
    </div>
    <div v-if="!options.length" class="options-empty">暂无选项，点击下方按钮添加</div>
    <div v-for="(_, optIdx) in options" :key="optIdx" class="option-row">
      <span class="option-index">{{ optIdx + 1 }}</span>
      <a-input
        :value="getOptionText(optIdx)"
        placeholder="选项内容，如：通过"
        @update:value="(val) => updateOption(optIdx, val)"
      />
      <a-button type="text" danger class="option-remove" @click="removeOption(optIdx)">
        <DeleteOutlined />
      </a-button>
    </div>
    <a-button type="dashed" size="small" block class="add-option-btn" @click="addOption">
      <PlusOutlined />
      添加选项
    </a-button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons-vue'
import {
  ensureConfirmOptionsArray,
  getConfirmOptionText,
  setConfirmOptionAt,
  addConfirmOption,
  removeConfirmOptionAt,
} from './confirmFormUtils.js'

const props = defineProps({
  field: { type: Object, required: true },
})

const emit = defineEmits(['change'])

const options = computed(() => ensureConfirmOptionsArray(props.field))

function emitChange() {
  emit('change')
}

function getOptionText(index) {
  return getConfirmOptionText(options.value[index])
}

function updateOption(index, val) {
  setConfirmOptionAt(props.field, index, val)
  emitChange()
}

function addOption() {
  addConfirmOption(props.field)
  emitChange()
}

function removeOption(index) {
  removeConfirmOptionAt(props.field, index)
  emitChange()
}
</script>

<style scoped>
.confirm-options-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.options-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
}
.body-label {
  font-size: 12px;
  color: var(--color-mute);
}
.body-hint {
  font-size: 11px;
  color: var(--color-mute);
}
.options-empty {
  font-size: 12px;
  color: var(--color-mute);
  padding: 6px 0;
}
.option-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.option-index {
  width: 18px;
  flex-shrink: 0;
  font-size: 11px;
  color: var(--color-mute);
  text-align: center;
}
.option-remove {
  flex-shrink: 0;
}
.add-option-btn {
  margin-top: 2px;
}
</style>
