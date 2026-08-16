<template>
  <div class="edit-message-outer">
    <a-tooltip title="取消">
      <button class="btn-copy edit-btn" @click="$emit('cancel-edit')">
        <CloseOutlined />
      </button>
    </a-tooltip>
    <div class="edit-message-box" @keydown="handleEditKeydown">
      <ChatMentionInput
        ref="editInputRef"
        :model-value="editContent"
        :initial-mentions="editMentions"
        :agent-id="selectedAgentId"
        :agent-version-id="selectedAgentVersionId"
        placeholder="编辑消息..."
        @update:model-value="$emit('update:editContent', $event)"
        @send="$emit('submit-edit')"
      />
    </div>
    <a-tooltip title="发送">
      <button
        class="btn-copy edit-btn edit-btn-send"
        :disabled="!editContent.trim() || loading"
        @click="$emit('submit-edit')"
      >
        <SendOutlined />
      </button>
    </a-tooltip>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { CloseOutlined, SendOutlined } from '@ant-design/icons-vue'
import ChatMentionInput from '../../ChatMentionInput.vue'

const props = defineProps({
  editContent: { type: String, default: '' },
  editMentions: { type: Array, default: () => [] },
  selectedAgentId: { type: [String, Number], default: null },
  selectedAgentVersionId: { type: [String, Number], default: null },
  loading: { type: Boolean, default: false },
})

const emit = defineEmits(['cancel-edit', 'submit-edit', 'update:editContent'])

const editInputRef = ref(null)

/** 仅在挂载时重建 chip；勿 watch editContent，否则输入 @ 时会被 setFromMessage 重置导致浮层无法点击 */
function syncMentionEditor() {
  nextTick(() => {
    nextTick(() => {
      editInputRef.value?.setFromMessage?.(props.editContent || '', props.editMentions || [])
    })
  })
}

onMounted(() => syncMentionEditor())

function handleEditKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    emit('submit-edit')
  } else if (e.key === 'Escape') {
    emit('cancel-edit')
  }
}

defineExpose({ editInputRef })
</script>

<style scoped>
.edit-message-outer {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.edit-message-box {
  flex: 1;
  min-width: 0;
  background: var(--color-canvas-soft-2);
  border-radius: 12px 12px 2px 12px;
  padding: 10px 16px;
}
.edit-message-box :deep(.mention-editor) {
  background: transparent;
  border: none;
  outline: none;
  box-shadow: none;
  font-size: 14px;
  line-height: 1.5;
  padding: 0;
  min-height: 44px;
  max-height: 200px;
}
.edit-message-box :deep(.mention-editor:focus) {
  border: none;
  outline: none;
  box-shadow: none;
}
.edit-message-box :deep(textarea) {
  background: transparent;
  border: none;
  outline: none;
  box-shadow: none;
  font-size: 14px;
  line-height: 1.5;
  padding: 0;
  resize: none;
}
.edit-message-box :deep(textarea:focus) {
  border: none;
  outline: none;
  box-shadow: none;
}
.edit-btn {
  flex-shrink: 0;
  opacity: 1;
}
.edit-btn-send:not(:disabled) {
  color: var(--color-link);
}
.edit-btn-send:disabled {
  color: var(--color-hairline-strong);
  cursor: not-allowed;
}
.btn-copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-top: 6px;
  padding: 4px 6px;
  background: none;
  border: none;
  border-radius: 4px;
  font-size: 14px;
  color: var(--color-mute);
  cursor: pointer;
  opacity: 1;
  transition:
    opacity 0.15s,
    color 0.15s;
}
.btn-copy:hover {
  color: var(--color-body);
}
</style>

<style>
[data-theme='dark'] .edit-message-box {
  background: #27272a;
}
</style>
