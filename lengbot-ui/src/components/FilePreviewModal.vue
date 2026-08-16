<template>
  <a-modal
    v-model:open="visible"
    :width="900"
    :footer="null"
    centered
    destroy-on-close
    class="file-preview-modal"
    :body-style="{ padding: 0, height: '75vh', overflow: 'hidden' }"
    @cancel="handleClose"
  >
    <template #title>
      <div class="fpm-header">
        <div class="fpm-title-main">
          <FileTypeIcon :name="displayName" :size="18" />
          <span class="fpm-title-text" :title="displayName">{{ displayName }}</span>
        </div>
        <div class="fpm-actions">
          <a-tooltip
            v-if="isRenderable"
            :title="forceText ? '切换为渲染预览' : '切换为文本预览'"
            placement="bottom"
            :get-popup-container="tooltipPopupContainer"
          >
            <button type="button" class="fpm-icon-btn" @click.stop="forceText = !forceText">
              <FileTextOutlined v-if="!forceText" />
              <EyeOutlined v-else />
            </button>
          </a-tooltip>
          <a-tooltip
            v-if="effectiveDownloadUrl"
            title="下载文件"
            placement="bottom"
            :get-popup-container="tooltipPopupContainer"
          >
            <button type="button" class="fpm-download" @click.stop="handleDownload">
              <DownloadOutlined />
              下载
            </button>
          </a-tooltip>
        </div>
      </div>
    </template>

    <div class="fpm-body">
      <div v-if="combinedLoading" class="fpm-loading">
        <LoadingOutlined spin />
        加载中...
      </div>
      <video v-else-if="isVideo && fileUrl" :src="fileUrl" controls class="fpm-video" />
      <FilePreview
        v-else
        :file-url="fileUrl"
        :file-name="displayName"
        :file-type="fileTypeExt"
        :content="content"
        :loading="false"
        :download-url="effectiveDownloadUrl"
        :force-text="forceText"
      />
    </div>
  </a-modal>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { DownloadOutlined, LoadingOutlined, FileTextOutlined, EyeOutlined } from '@ant-design/icons-vue'
import FileTypeIcon from './FileTypeIcon.vue'
import FilePreview from './FilePreview.vue'
import { getFileExtension } from '../utils/filePreview'
import { triggerBrowserDownload } from '../utils/fileDownload'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 展示用文件名 */
  fileName: { type: String, default: '文件预览' },
  /** 预览 URL（PDF/图片/Office 等） */
  fileUrl: { type: String, default: '' },
  /** 下载 URL，默认同 fileUrl */
  downloadUrl: { type: String, default: '' },
  /** 文本/Markdown 等内容（后端已读取时传入） */
  content: { type: String, default: '' },
  /** 外部加载态 */
  loading: { type: Boolean, default: false },
  /** 是否为视频预览 */
  isVideo: { type: Boolean, default: false },
  /** 扩展名（可选，不传则从 fileName 解析） */
  fileType: { type: String, default: '' },
})

const emit = defineEmits(['update:open'])

const visible = computed({
  get: () => props.open,
  set: (v) => emit('update:open', v),
})

const displayName = computed(() => props.fileName || '文件预览')

const fileTypeExt = computed(() => {
  if (props.fileType) return props.fileType.toLowerCase()
  return getFileExtension(displayName.value)
})

const effectiveDownloadUrl = computed(() => props.downloadUrl || props.fileUrl || '')

const combinedLoading = computed(() => props.loading)

// 仅 HTML 提供「渲染 / 纯文本」切换（Markdown 纯文本效果不佳，不提供切换）
const isRenderable = computed(() => ['html', 'htm'].includes(fileTypeExt.value))

const forceText = ref(false)

// 切换文件时重置为默认渲染视图
watch(
  () => [props.fileUrl, props.fileName, props.content],
  () => {
    forceText.value = false
  }
)

function tooltipPopupContainer() {
  return document.body
}

function handleClose() {
  visible.value = false
}

function handleDownload() {
  if (effectiveDownloadUrl.value) {
    triggerBrowserDownload(effectiveDownloadUrl.value, displayName.value || 'download')
  }
}
</script>

<style scoped>
.fpm-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  min-width: 0;
  padding-right: 28px;
}
.fpm-title-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}
.fpm-title-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}
.fpm-actions {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.fpm-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  font-size: 15px;
  color: var(--color-mute);
  border: none;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  transition:
    background 0.15s,
    color 0.15s;
}
.fpm-icon-btn:hover {
  background: var(--color-canvas-soft);
  color: var(--color-body);
}
.fpm-download {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-link);
  text-decoration: none;
  padding: 4px 10px;
  border-radius: 6px;
  border: none;
  background: transparent;
  cursor: pointer;
  transition: background 0.15s;
}
.fpm-download:hover {
  background: var(--color-canvas-soft);
  color: var(--color-link);
}
.fpm-body {
  height: 100%;
  overflow: auto;
  scrollbar-gutter: stable;
  padding-right: var(--scroll-content-gap, 8px);
}
.fpm-body :deep(.file-preview) {
  min-height: 100%;
  height: 100%;
}
.fpm-loading {
  height: 100%;
  min-height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-mute);
  gap: 8px;
}
.fpm-video {
  width: 100%;
  max-height: 75vh;
  display: block;
  margin: 0 auto;
  background: #000;
}
</style>

<style>
/* 标题栏与下载按钮同排展示（避免 #extra 插槽无效） */
.file-preview-modal .ant-modal-header {
  padding: 12px 16px;
}
.file-preview-modal .ant-modal-title {
  width: 100%;
}
</style>
