<template>
  <!-- RAG引用列表 -->
  <div v-if="msg?.role === 'assistant' && ragRefs.length > 0 && !msg._streaming" class="rag-references">
    <div class="rag-header" @click="$emit('rag-toggle', { kind: 'section' })">
      <RightOutlined :class="{ expanded: refsSectionExpanded }" />
      <FileTextOutlined />
      <span>参考文献 ({{ ragRefs.length }})</span>
    </div>
    <CollapseTransition :open="refsSectionExpanded">
      <div class="rag-list">
        <div v-for="(ref, ri) in ragRefs" :key="ri" class="rag-item">
          <div class="rag-item-header" @click="$emit('rag-toggle', { kind: 'item', refIndex: ri })">
            <div class="rag-title-left">
              <RightOutlined :class="{ expanded: isRefExpanded(ri) }" />
              <template v-if="ref.sourceType === 'qa_pair'">
                <a-tag color="success" class="rag-qa-tag">问答对</a-tag>
                <span class="rag-doc-name">{{ getRagQaQuestion(ref) }}</span>
              </template>
              <span v-else class="rag-doc-name">{{ ref.documentName }}</span>
            </div>
            <div class="rag-title-right">
              <span class="rag-score">{{ (ref.score * 100).toFixed(1) }}%</span>
              <a-tooltip v-if="ref.knowledgeId" title="查看知识库">
                <LinkOutlined
                  class="rag-nav-btn"
                  @click.stop="$emit('go-knowledge', { knowledgeId: ref.knowledgeId, documentId: ref.documentId })"
                />
              </a-tooltip>
            </div>
          </div>
          <CollapseTransition :open="isRefExpanded(ri)">
            <div class="rag-item-content">
              {{ ref.contentPreview }}
            </div>
          </CollapseTransition>
        </div>
      </div>
    </CollapseTransition>
  </div>
  <!-- 耗时显示 -->
  <div v-if="showReplyElapsed" class="reply-elapsed">
    {{ formatElapsed(lastReplyElapsed) }}
  </div>
</template>

<script setup>
import { FileTextOutlined, RightOutlined, LinkOutlined } from '@ant-design/icons-vue'
import CollapseTransition from '../../common/CollapseTransition.vue'
import { getRagQaQuestion, formatElapsed } from '../../../composables/chat/useChatMessageModel.js'

defineProps({
  msg: { type: Object, required: true },
  ragRefs: { type: Array, default: () => [] },
  refsSectionExpanded: { type: Boolean, default: true },
  isRefExpanded: { type: Function, required: true },
  showReplyElapsed: { type: Boolean, default: false },
  lastReplyElapsed: { type: Number, default: null },
})

defineEmits(['rag-toggle', 'go-knowledge'])
</script>

<style scoped>
.rag-references {
  margin-top: 12px;
  padding: 12px;
  background: var(--blue-50);
  border-radius: 8px;
  border: 1px solid var(--blue-200);
}
.rag-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--blue-700);
  cursor: pointer;
  user-select: none;
}
.rag-header :deep(.anticon:first-child) {
  font-size: 10px;
  transition: transform 0.2s;
}
.rag-header :deep(.anticon:first-child.expanded) {
  transform: rotate(90deg);
}
.rag-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}
.rag-item {
  background: var(--color-canvas);
  border-radius: 6px;
  border: 1px solid var(--blue-200);
  border-left: 3px solid var(--blue-400);
  overflow: hidden;
}
.rag-item-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  cursor: pointer;
  transition: background 0.15s;
}
.rag-item-header:hover {
  background: var(--blue-50);
}
.rag-item-header :deep(.anticon) {
  font-size: 10px;
  color: var(--blue-400);
  transition: transform 0.2s;
}
.rag-item-header :deep(.anticon.expanded) {
  transform: rotate(90deg);
}
.rag-title-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}
.rag-title-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: auto;
}
.rag-doc-name {
  flex: 1;
  font-size: 13px;
  color: var(--gray-700);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rag-qa-tag {
  flex-shrink: 0;
  font-size: 12px;
}
.rag-score {
  font-size: 12px;
  color: var(--blue-600);
  font-weight: 600;
  background: var(--blue-50);
  border: 1px solid var(--blue-200);
  border-radius: 4px;
  padding: 1px 6px;
}
.rag-nav-btn {
  font-size: 12px;
  color: var(--blue-500);
  margin-left: 4px;
  cursor: pointer;
  transition: color 0.2s;
}
.rag-nav-btn:hover {
  color: var(--blue-600);
}
.rag-item-content {
  padding: 12px;
  background: var(--gray-25, #fafafa);
  border-top: 1px solid var(--blue-100);
  font-size: 12px;
  color: var(--gray-600);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow-y: auto;
}
.reply-elapsed {
  margin-top: 8px;
  font-size: 12px;
  color: var(--color-mute);
}
</style>
