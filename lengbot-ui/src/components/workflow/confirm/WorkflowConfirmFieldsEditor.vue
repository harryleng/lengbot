<template>
  <div class="confirm-fields-editor">
    <div
      v-for="(field, idx) in fields"
      :key="idx"
      class="confirm-field-card"
      :class="{ 'is-info': field.type === 'info' }"
    >
      <div class="confirm-field-card-head">
        <span class="field-badge">字段 {{ idx + 1 }}</span>
        <a-input
          v-model:value="field.key"
          placeholder="变量 key"
          class="field-key"
          :disabled="field.type === 'info'"
          @change="emitChange"
        />
        <a-select v-model:value="field.type" class="field-type" @change="onTypeChange(field)">
          <a-select-option v-for="opt in CONFIRM_FIELD_TYPES" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </a-select-option>
        </a-select>
        <a-switch
          v-if="field.type !== 'info'"
          v-model:checked="field.required"
          checked-children="必填"
          un-checked-children="选填"
          @change="emitChange"
        />
        <a-button type="text" danger class="field-remove" @click="removeField(idx)">
          <DeleteOutlined />
        </a-button>
      </div>

      <!-- 展示信息：独立区块，避免与 key/类型挤在一行 -->
      <template v-if="field.type === 'info'">
        <div class="confirm-field-card-body">
          <div class="body-label">展示内容</div>
          <a-textarea
            v-model:value="field.label"
            :rows="4"
            placeholder="纯展示文案，支持较长说明；可使用 {{变量}}"
            @change="emitChange"
          />
          <WorkflowConfirmInfoBlock
            v-if="getConfirmInfoText(field)"
            :field="field"
            variant="editor-preview"
            title="预览"
            class="info-preview"
          />
        </div>
      </template>

      <template v-else>
        <div class="confirm-field-card-body compact">
          <div class="body-row">
            <div class="body-col">
              <div class="body-label">显示标签</div>
              <a-input v-model:value="field.label" placeholder="表单中展示的标签" @change="emitChange" />
            </div>
            <div v-if="field.type === 'text' || field.type === 'textarea' || field.type === 'number'" class="body-col">
              <div class="body-label">默认值（可选）</div>
              <a-input v-model:value="field.defaultValue" placeholder="默认值" @change="emitChange" />
            </div>
          </div>
          <div v-if="field.type === 'radio' || field.type === 'select'" class="options-block">
            <WorkflowConfirmOptionsEditor :field="field" @change="emitChange" />
          </div>
        </div>
      </template>
    </div>

    <a-button type="dashed" block size="small" class="add-field-btn" @click="addField">
      <PlusOutlined />
      添加字段
    </a-button>
  </div>
</template>

<script setup>
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons-vue'
import WorkflowConfirmInfoBlock from './WorkflowConfirmInfoBlock.vue'
import WorkflowConfirmOptionsEditor from './WorkflowConfirmOptionsEditor.vue'
import { CONFIRM_FIELD_TYPES, getConfirmInfoText } from './confirmFormUtils.js'

const props = defineProps({
  fields: { type: Array, required: true },
})

const emit = defineEmits(['change'])

function emitChange() {
  emit('change')
}

function addField() {
  if (!Array.isArray(props.fields)) return
  props.fields.push({ key: '', label: '', type: 'text', required: false, defaultValue: '' })
  emitChange()
}

function removeField(idx) {
  props.fields?.splice(idx, 1)
  emitChange()
}

function onTypeChange(field) {
  if (field.type === 'info' && !field.key) {
    field.key = '_info'
  }
  if ((field.type === 'radio' || field.type === 'select') && !Array.isArray(field.options)) {
    field.options = []
  }
  if ((field.type === 'radio' || field.type === 'select') && field.options.length === 0) {
    field.options.push('')
  }
  emitChange()
}
</script>

<style scoped>
.confirm-fields-editor {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.confirm-field-card {
  border: 1px solid var(--color-border, #e8e8e8);
  border-radius: 8px;
  padding: 10px;
  background: var(--color-canvas, #fff);
}
.confirm-field-card.is-info {
  border-color: var(--color-warning-soft, #fcd34d);
  background: var(--color-warn-bg, #fffbeb);
}
.confirm-field-card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.field-badge {
  font-size: 11px;
  color: var(--color-mute);
  white-space: nowrap;
}
.field-key {
  flex: 1;
  min-width: 100px;
}
.field-type {
  width: 112px;
  flex-shrink: 0;
}
.field-remove {
  margin-left: auto;
}
.confirm-field-card-body {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--color-border, #eee);
}
.confirm-field-card-body.compact .body-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
@media (max-width: 480px) {
  .confirm-field-card-body.compact .body-row {
    grid-template-columns: 1fr;
  }
}
.body-label {
  font-size: 12px;
  color: var(--color-mute);
  margin-bottom: 4px;
}
.body-hint {
  margin-left: 6px;
  font-size: 11px;
  color: var(--color-mute);
  font-weight: normal;
}
.options-block {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--color-bg-soft, #f8fafc);
  border: 1px solid var(--color-border, #e8e8e8);
}
.info-preview {
  margin-top: 8px;
  margin-bottom: 0;
}
.add-field-btn {
  margin-top: 4px;
}
</style>
