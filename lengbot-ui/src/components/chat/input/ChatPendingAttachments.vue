<template>
  <div v-if="uploading || pendingAttachments.length > 0" class="pending-attachments">
    <span class="pending-att-count">
      <template v-if="uploading">附件上传中…</template>
      <template v-else>已选 {{ pendingAttachments.length }} 个附件</template>
    </span>
    <div class="pending-att-thumbs">
      <ChatAttachmentTile v-if="uploading" :att="{ type: 'uploading', fileName: '上传中' }" uploading />
      <ChatAttachmentTile
        v-for="(att, i) in pendingAttachments"
        :key="att.id || i"
        :att="att"
        :thumb-url="getAttThumbUrl(att)"
        removable
        @preview="onPreview(att)"
        @remove="onRemove(i)"
      />
    </div>
  </div>
</template>

<script setup>
import ChatAttachmentTile from '../../ChatAttachmentTile.vue'

defineProps({
  uploading: { type: Boolean, default: false },
  pendingAttachments: { type: Array, default: () => [] },
  getAttThumbUrl: { type: Function, default: () => '' },
})

const emit = defineEmits(['remove-attachment', 'preview'])

function onPreview(att) {
  emit('preview', att)
}

function onRemove(index) {
  emit('remove-attachment', index)
}
</script>

<style scoped>
.pending-attachments {
  margin-top: 8px;
}
.pending-att-count {
  font-size: 12px;
  color: var(--color-mute);
  display: block;
  margin-bottom: 6px;
}
.pending-att-thumbs {
  display: flex;
  flex-direction: row-reverse;
  flex-wrap: wrap-reverse;
  justify-content: flex-end;
  gap: 6px;
}
.pending-att-item {
  position: relative;
  flex-shrink: 0;
}
.att-thumb-wrap {
  position: relative;
  width: 48px;
  height: 48px;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid var(--color-hairline);
  padding: 0;
  background: var(--color-canvas-soft-2);
  cursor: pointer;
}
.att-thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.pending-att-item .att-name {
  font-size: 12px;
  color: var(--color-body);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pending-att-item .att-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  padding: 0;
  cursor: pointer;
  color: var(--color-mute);
  z-index: 1;
}
.att-remove {
  border: none;
  background: transparent;
  color: var(--color-mute);
  cursor: pointer;
  padding: 0;
  line-height: 1;
}
</style>
