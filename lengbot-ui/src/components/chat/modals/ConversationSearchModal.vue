<template>
  <a-modal
    :open="open"
    :footer="null"
    :width="640"
    :mask-closable="false"
    :destroy-on-close="true"
    wrap-class-name="conversation-search-modal"
    @update:open="onOpenChange"
  >
    <template #title>
      <span>搜索历史对话</span>
    </template>

    <div class="dialog-scroll-body">
      <div class="conv-search-input">
        <a-input-search
          ref="inputRef"
          v-model:value="keyword"
          placeholder="输入关键词搜索消息内容…"
          allow-clear
          enter-button="搜索"
          :loading="loading"
          @search="runSearch"
          @press-enter="runSearch"
        />
      </div>

      <div class="conv-search-body">
        <div v-if="loading" class="conv-search-state">
          <a-spin size="small" />
          <span>搜索中…</span>
        </div>

        <div v-else-if="!searched" class="conv-search-state conv-search-hint">
          <SearchOutlined />
          <span>跨所有会话搜索消息内容，命中后可跳转到原对话</span>
        </div>

        <div v-else-if="results.length === 0" class="conv-search-state">
          <span>未命中任何消息</span>
        </div>

        <div v-else class="conv-search-list">
          <div v-for="item in results" :key="item.messageId" class="conv-search-item" @click="onPick(item)">
            <div class="conv-search-item-head">
              <span class="conv-search-session">
                <PushpinFilled v-if="item.pinned" class="conv-search-pin" />
                <span class="conv-search-session-title">{{ item.sessionTitle || '新对话' }}</span>
              </span>
              <span class="conv-search-time">{{ formatTime(item.messageCreateTime) }}</span>
            </div>
            <div class="conv-search-snippet" v-html="renderSnippet(item.snippet, item.messageRole)"></div>
          </div>
        </div>
      </div>
    </div>
  </a-modal>
</template>

<script setup>
import { ref, nextTick, watch } from 'vue'
import { SearchOutlined, PushpinFilled } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { searchConversations } from '@/api/chatSession'
import { formatTime } from '@/utils/format'

const props = defineProps({
  open: { type: Boolean, default: false },
})
const emit = defineEmits(['update:open', 'pick'])

const inputRef = ref(null)
const keyword = ref('')
const results = ref([])
const loading = ref(false)
const searched = ref(false)

function onOpenChange(val) {
  emit('update:open', val)
  if (!val) {
    // 关闭时重置内部状态
    keyword.value = ''
    results.value = []
    searched.value = false
  }
}

watch(
  () => props.open,
  async (val) => {
    if (val) {
      await nextTick()
      inputRef.value?.focus?.()
    }
  }
)

async function runSearch() {
  const kw = keyword.value.trim()
  if (!kw) {
    message.warning('请输入搜索关键词')
    return
  }
  loading.value = true
  searched.value = true
  try {
    const res = await searchConversations(kw, 20)
    results.value = res.data || []
  } catch (e) {
    results.value = []
  } finally {
    loading.value = false
  }
}

function onPick(item) {
  emit('pick', item)
  emit('update:open', false)
}

function escapeHtml(str) {
  if (!str) return ''
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function renderSnippet(snippet, role) {
  const safe = escapeHtml(snippet || '')
  const prefix =
    role === 'user'
      ? '<span class="conv-role conv-role-user">用户</span>'
      : '<span class="conv-role conv-role-ai">AI</span>'
  const kw = (keyword.value || '').trim()
  if (!kw) return `${prefix}${safe}`
  // 高亮关键字（大小写不敏感）
  const escapedKw = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const highlighted = safe.replace(new RegExp(escapedKw, 'gi'), (m) => `<mark>${m}</mark>`)
  return `${prefix}${highlighted}`
}

defineExpose({ runSearch })
</script>

<style lang="less">
.conversation-search-modal .ant-modal-body {
  padding: 12px 16px 16px;
}
.conv-search-input {
  margin-bottom: 12px;
}
/* input 和搜索按钮分离：间距足够大以避开 input focus 时的 2px 蓝色光晕，
   否则光晕会盖住按钮左边缘，视觉上像被遮挡 */
.conv-search-input .ant-input-affix-wrapper {
  border-top-right-radius: 6px;
  border-bottom-right-radius: 6px;
}
.conv-search-input .ant-input-group > .ant-input-group-addon:last-child {
  padding-left: 16px;
  background: transparent;
}
/* antd 对 .ant-input-search-button 用逻辑属性 border-start-start-radius:0 把左侧角削平，
   选择器特异性 (0,5,0)。这里必须用同样的逻辑属性 + 同等或更高特异性才能生效，
   否则按钮左侧没圆角、像被切掉。 */
.conv-search-input .ant-input-group > .ant-input-group-addon:last-child .ant-input-search-button {
  border-start-start-radius: 6px;
  border-end-start-radius: 6px;
}
.conv-search-body {
  max-height: 60vh;
  overflow-y: auto;
}
.conv-search-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 32px 12px;
  font-size: 13px;
  color: var(--color-mute);
}
.conv-search-hint {
  text-align: center;
}
.conv-search-hint :deep(.anticon) {
  font-size: 22px;
}
.conv-search-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.conv-search-item {
  padding: 10px 12px;
  border: 1px solid var(--gray-100);
  border-radius: 8px;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background 0.15s;
}
.conv-search-item:hover {
  border-color: var(--color-link);
  background: var(--gray-25);
}
.conv-search-item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--color-mute);
}
.conv-search-session {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}
.conv-search-pin {
  color: #818cf8;
  font-size: 11px;
  flex-shrink: 0;
}
.conv-search-session-title {
  font-size: 13px;
  color: var(--gray-900, var(--color-ink));
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 420px;
}
.conv-search-time {
  flex-shrink: 0;
  font-size: 11px;
}
.conv-search-snippet {
  font-size: 13px;
  line-height: 1.55;
  color: var(--color-body);
  word-break: break-word;
}
.conv-search-snippet mark {
  background: rgba(250, 204, 21, 0.35);
  color: inherit;
  padding: 0 1px;
  border-radius: 2px;
}
.conv-role {
  display: inline-block;
  padding: 1px 6px;
  margin-right: 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
}
.conv-role-user {
  background: rgba(99, 102, 241, 0.12);
  color: #6366f1;
}
.conv-role-ai {
  background: rgba(16, 185, 129, 0.12);
  color: #10b981;
}
</style>
