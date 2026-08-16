<template>
  <!-- 会话文件列表抽屉 -->
  <a-drawer
    :open="open"
    title="会话文件"
    :width="480"
    :mask-closable="false"
    destroy-on-close
    @update:open="$emit('update:open', $event)"
    @after-open-change="$emit('after-open-change', $event)"
  >
    <template #extra>
      <a-tooltip title="刷新" placement="bottom" :get-popup-container="tooltipPopupContainer">
        <span class="file-drawer-btn-wrap">
          <button
            class="btn-drawer-refresh"
            :class="{ refreshing: fileDrawerLoading }"
            :disabled="fileDrawerLoading"
            @click="$emit('refresh')"
          >
            <ReloadOutlined :spin="fileDrawerLoading" />
          </button>
        </span>
      </a-tooltip>
    </template>
    <template #closeIcon>
      <a-tooltip title="关闭" placement="bottom" :get-popup-container="tooltipPopupContainer">
        <span class="file-drawer-btn-wrap">
          <CloseOutlined />
        </span>
      </a-tooltip>
    </template>
    <div class="file-drawer-stats">
      共 {{ fileStats.total }} 个文件，用户上传 {{ fileStats.userUpload }} 个，AI 生成 {{ fileStats.aiGenerated }} 个
    </div>
    <div class="file-drawer-body">
      <SessionFileTree
        ref="sessionFileTreeRef"
        :session-id="sessionId"
        :refresh-tick="fileTreeRefreshTick"
        @preview="$emit('preview', $event)"
        @refreshed="$emit('refreshed', $event)"
      />
    </div>
  </a-drawer>
</template>

<script setup>
import { ref } from 'vue'
import { ReloadOutlined, CloseOutlined } from '@ant-design/icons-vue'
import SessionFileTree from '../../SessionFileTree.vue'

defineProps({
  open: { type: Boolean, default: false },
  sessionId: { type: [String, Number], default: null },
  fileStats: {
    type: Object,
    default: () => ({ total: 0, userUpload: 0, aiGenerated: 0 }),
  },
  fileDrawerLoading: { type: Boolean, default: false },
  fileTreeRefreshTick: { type: Number, default: 0 },
  tooltipPopupContainer: { type: Function, default: undefined },
})

defineEmits(['update:open', 'after-open-change', 'refresh', 'preview', 'refreshed'])

const sessionFileTreeRef = ref(null)

defineExpose({
  sessionFileTreeRef,
})
</script>

<style scoped>
.file-drawer-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 0;
}

.file-drawer-loading-icon {
  font-size: 24px;
  color: var(--color-mute);
}

.btn-drawer-refresh {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-hairline);
  background: var(--color-canvas);
  color: var(--color-mute);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  transition:
    background 0.15s,
    color 0.15s,
    border-color 0.15s;
}

.btn-drawer-refresh:hover:not(.refreshing) {
  background: var(--color-canvas-soft-2);
  color: var(--color-ink);
  border-color: var(--color-hairline-strong);
}

.btn-drawer-refresh.refreshing {
  cursor: not-allowed;
  color: var(--color-mute);
}

.file-drawer-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 24px;
  text-align: center;
  color: var(--color-mute);
}

.file-drawer-empty-icon {
  font-size: 48px;
  color: var(--color-hairline-strong);
  margin-bottom: 16px;
}

.file-drawer-empty p {
  margin: 0;
  font-size: 14px;
}

.file-drawer-empty-hint {
  font-size: 12px !important;
  color: var(--color-mute) !important;
  margin-top: 8px !important;
}

.file-drawer-stats {
  font-size: 13px;
  color: var(--color-mute);
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--color-hairline);
  line-height: 1.5;
}

.file-drawer-body {
  height: calc(100vh - 160px);
  min-height: 360px;
  overflow: auto;
}
.file-drawer-btn-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  line-height: 0;
  vertical-align: middle;
}
</style>
