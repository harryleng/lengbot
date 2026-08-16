<template>
  <!-- 原始内容弹窗 -->
  <a-modal
    :open="open"
    :footer="null"
    width="680px"
    :body-style="{ maxHeight: '70vh', overflow: 'auto' }"
    @update:open="$emit('update:open', $event)"
  >
    <template #title>
      <span>{{ title }}</span>
      <a-tooltip title="复制">
        <button class="raw-modal-meta-btn" @click="$emit('copy-raw')" style="margin-left: 12px">
          <CheckOutlined v-if="copied" style="color: #16a34a" />
          <CopyOutlined v-else />
        </button>
      </a-tooltip>
      <a-tooltip v-if="metadata" title="查看 Metadata">
        <button class="raw-modal-meta-btn" @click="$emit('open-metadata')">
          <CodeOutlined />
        </button>
      </a-tooltip>
    </template>
    <div class="dialog-scroll-body">
      <pre class="raw-modal-content">{{ content }}</pre>
    </div>
  </a-modal>

  <!-- Metadata 弹窗 -->
  <a-modal
    :open="metadataOpen"
    :footer="null"
    width="680px"
    :body-style="{ maxHeight: '70vh', overflow: 'auto' }"
    @update:open="$emit('update:metadataOpen', $event)"
  >
    <template #title>
      <span>Metadata</span>
      <button class="raw-modal-meta-btn" @click="$emit('copy-metadata')" style="margin-left: 12px">
        <CheckOutlined v-if="metadataCopied" style="color: #16a34a" />
        <CopyOutlined v-else />
      </button>
    </template>
    <div class="dialog-scroll-body">
      <pre class="raw-modal-content">{{ metadataJson }}</pre>
    </div>
  </a-modal>
</template>

<script setup>
import { CheckOutlined, CopyOutlined, CodeOutlined } from '@ant-design/icons-vue'

defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' },
  content: { type: String, default: '' },
  copied: { type: Boolean, default: false },
  metadata: { type: [Object, String], default: null },
  metadataOpen: { type: Boolean, default: false },
  metadataJson: { type: String, default: '' },
  metadataCopied: { type: Boolean, default: false },
})

defineEmits(['update:open', 'update:metadataOpen', 'copy-raw', 'copy-metadata', 'open-metadata'])
</script>

<style scoped>
.raw-modal-content {
  margin: 0;
  padding: 0;
  background: none;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'Menlo', 'Monaco', 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.7;
  color: var(--color-text-code);
}

.raw-modal-meta-btn {
  appearance: none;
  border: none;
  background: none;
  color: var(--gray-400);
  font-size: 14px;
  cursor: pointer;
  padding: 2px 6px;
  margin-left: 8px;
  border-radius: 4px;
  vertical-align: middle;
  transition: all 0.15s;
  &:hover {
    color: var(--main-600);
    background: var(--gray-100);
  }
}
</style>
