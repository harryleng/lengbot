<template>
  <div class="sandbox-file-result">
    <!-- 纯文本降级 -->
    <div v-if="isPlainText" class="sfr-plain">
      <pre>{{ displayText }}</pre>
    </div>

    <template v-else>
      <!-- 错误 -->
      <div v-if="data.success === false || data.error" class="sfr-card sfr-card-error">
        <div class="sfr-header sfr-header-error">
          <CloseCircleOutlined class="sfr-header-icon" />
          <span>执行失败</span>
        </div>
        <pre class="sfr-error-msg">{{ data.error || '未知错误' }}</pre>
      </div>

      <!-- read_file: 文件内容（FilePreview 按扩展名渲染） -->
      <div v-else-if="data.content != null" class="sfr-card">
        <div class="sfr-header">
          <FileTextOutlined class="sfr-header-icon" />
          <span class="sfr-path">{{ data.path }}</span>
          <span v-if="data.size != null" class="sfr-meta">{{ formatSize(data.size) }}</span>
          <button class="sfr-detail-btn" @click="showModal = true">
            <EyeOutlined />
            查看详情
          </button>
        </div>
        <div class="sfr-read-preview">
          <FilePreview
            :file-url="''"
            :file-name="previewFileName"
            :file-type="previewExt"
            :content="truncateText(data.content, PREVIEW_MAX)"
            :loading="false"
            :download-url="''"
          />
        </div>
        <div v-if="data.content.length > PREVIEW_MAX" class="sfr-truncated-hint">
          ... 内容已截断，点击"查看详情"查看完整内容
        </div>

        <a-modal
          v-model:open="showModal"
          title="文件内容"
          :footer="null"
          :width="680"
          :body-style="{ maxHeight: '70vh', overflow: 'auto', padding: '20px' }"
          destroy-on-close
        >
          <div class="sfr-modal-meta">
            <div class="sfr-modal-meta-item">
              <span class="sfr-modal-meta-label">文件路径</span>
              <span class="sfr-modal-meta-value">{{ data.path }}</span>
            </div>
            <div v-if="data.size != null" class="sfr-modal-meta-item sfr-modal-meta-item-right">
              <span class="sfr-modal-meta-label">文件大小</span>
              <span class="sfr-modal-meta-value sfr-modal-meta-value-strong">{{ formatSize(data.size) }}</span>
            </div>
          </div>
          <div class="sfr-modal-preview">
            <FilePreview
              :file-url="''"
              :file-name="previewFileName"
              :file-type="previewExt"
              :content="data.content || ''"
              :loading="false"
              :download-url="''"
            />
          </div>
        </a-modal>
      </div>

      <!-- list_files: 文件列表 -->
      <div v-else-if="data.files != null" class="sfr-card">
        <div class="sfr-header">
          <FolderOpenOutlined class="sfr-header-icon" />
          <span class="sfr-path">{{ data.dirPath }}</span>
          <span class="sfr-meta">{{ data.total }} 个文件</span>
          <button v-if="data.files.length > LIST_PREVIEW_MAX" class="sfr-detail-btn" @click="showModal = true">
            <EyeOutlined />
            查看全部
          </button>
        </div>
        <div v-if="data.files.length === 0" class="sfr-empty">目录为空</div>
        <div v-else class="sfr-file-list">
          <div v-for="(file, i) in previewFiles" :key="i" class="sfr-file-item">
            <FileTextOutlined class="sfr-file-icon" />
            <span class="sfr-file-name">{{ file }}</span>
          </div>
          <div v-if="data.files.length > LIST_PREVIEW_MAX" class="sfr-more">
            ... 还有 {{ data.files.length - LIST_PREVIEW_MAX }} 个文件
          </div>
        </div>

        <a-modal
          v-model:open="showModal"
          title="文件列表"
          :footer="null"
          :width="720"
          :body-style="{ maxHeight: '70vh', overflow: 'auto', padding: '20px' }"
          destroy-on-close
        >
          <div class="dialog-scroll-body">
            <div class="sfr-modal-meta">
              <div class="sfr-modal-meta-item">
                <FolderOpenOutlined class="sfr-modal-meta-icon" />
                <span class="sfr-modal-meta-label">目录路径</span>
                <span class="sfr-modal-meta-value">{{ data.dirPath }}</span>
              </div>
              <div class="sfr-modal-meta-item sfr-modal-meta-item-right">
                <FileTextOutlined class="sfr-modal-meta-icon" />
                <span class="sfr-modal-meta-label">文件总数</span>
                <span class="sfr-modal-meta-value sfr-modal-meta-value-strong">{{ data.total }}</span>
              </div>
            </div>
            <div class="sfr-modal-list">
              <div v-for="(file, i) in data.files" :key="i" class="sfr-modal-list-item">
                <FileTextOutlined class="sfr-modal-list-icon" />
                <span class="sfr-modal-list-name">{{ file }}</span>
              </div>
            </div>
          </div>
        </a-modal>
      </div>

      <!-- write_file: 写入结果（含内联文件预览） -->
      <div v-else-if="data.success === true" class="sfr-card">
        <div class="sfr-header">
          <CheckCircleOutlined class="sfr-header-icon success" />
          <span class="sfr-path">{{ data.path }}</span>
          <span class="sfr-badge success">写入成功</span>
          <span v-if="data.size != null" class="sfr-meta">{{ formatSize(data.size) }}</span>
          <button class="sfr-detail-btn" @click="togglePreview">
            <EyeOutlined />
            {{ showPreview ? '收起预览' : '查看预览' }}
          </button>
          <button v-if="data.downloadUrl" class="sfr-detail-btn" @click="handleDownload">
            <DownloadOutlined />
            下载
          </button>
        </div>
        <CollapseTransition :open="showPreview">
          <div class="sfr-inline-preview">
            <FilePreview
              :file-url="data.url || ''"
              :file-name="previewFileName"
              :file-type="previewExt"
              :content="writePreviewContent"
              :loading="false"
              :download-url="data.downloadUrl || ''"
            />
          </div>
        </CollapseTransition>
      </div>

      <!-- 兜底 -->
      <div v-else class="sfr-card">
        <pre class="sfr-json">{{ formattedJson }}</pre>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import {
  FileTextOutlined,
  FolderOpenOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  EyeOutlined,
  DownloadOutlined,
} from '@ant-design/icons-vue'
import FilePreview from '../FilePreview.vue'
import CollapseTransition from '../common/CollapseTransition.vue'
import { triggerBrowserDownload } from '@/utils/fileDownload'

const props = defineProps({
  event: { type: Object, required: true },
  /** 配对 tool_call 的 args；工作区路径无 url 时从中反查 content */
  args: { type: String, default: '' },
})

const PREVIEW_MAX = 500
const LIST_PREVIEW_MAX = 5

const showModal = ref(false)
const showPreview = ref(false)

const rawResult = computed(() => props.event.result || '')

const data = computed(() => {
  try {
    return JSON.parse(rawResult.value)
  } catch {
    return null
  }
})

const isPlainText = computed(() => !data.value || typeof data.value !== 'object')
const displayText = computed(() => (typeof data.value === 'string' ? data.value : rawResult.value))

const formattedJson = computed(() => {
  if (!data.value) return rawResult.value
  try {
    return JSON.stringify(data.value, null, 2)
  } catch {
    return rawResult.value
  }
})

const previewFiles = computed(() => {
  if (!data.value?.files) return []
  return data.value.files.slice(0, LIST_PREVIEW_MAX)
})

/** 从 path 取文件名，供 FilePreview 识别扩展名 */
const previewFileName = computed(() => {
  const p = data.value?.path || ''
  return p.includes('/') ? p.substring(p.lastIndexOf('/') + 1) : p
})

const previewExt = computed(() => {
  const name = previewFileName.value
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.substring(dot + 1).toLowerCase() : ''
})

/**
 * write_file 预览正文：outputs/ 有 url 时交给 FilePreview 拉取；
 * 工作区路径无 url 则从配对 tool_call args.content 反查。
 */
const writePreviewContent = computed(() => {
  if (data.value?.url) return ''
  if (!props.args) return ''
  try {
    const parsed = JSON.parse(props.args)
    return parsed?.content || ''
  } catch {
    return ''
  }
})

function truncateText(content, max) {
  if (!content) return ''
  return content.length > max ? content.substring(0, max) + '...' : content
}

function formatSize(n) {
  if (n < 1024) return n + ' 字符'
  return (n / 1024).toFixed(1) + ' KB'
}

function togglePreview() {
  showPreview.value = !showPreview.value
}

function handleDownload() {
  if (data.value?.downloadUrl) {
    triggerBrowserDownload(data.value.downloadUrl, previewFileName.value)
  }
}
</script>

<style lang="less" scoped>
.sandbox-file-result {
  font-size: 12px;

  .sfr-plain pre {
    margin: 0;
    padding: 8px 10px;
    background: var(--gray-25);
    border-radius: 6px;
    line-height: 1.5;
    color: var(--gray-700);
    white-space: pre-wrap;
    word-break: break-word;
  }

  // ── 卡片容器 ──
  .sfr-card {
    border: 1px solid var(--purple-300);
    border-left: 3px solid var(--purple-500);
    border-radius: 8px;
    overflow: hidden;
    background: var(--color-purple-bg);
  }
  .sfr-card-error {
    border-color: var(--color-error-soft);
    border-left-color: var(--color-error);
    background: var(--color-error-bg);
  }

  // ── Header ──
  .sfr-header {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 10px;
    background: var(--color-purple-bg);
    border-bottom: 1px solid var(--purple-300);
    font-size: 12px;
    font-weight: 600;
    color: var(--purple-800);
  }
  .sfr-header-error {
    background: var(--color-error-bg);
    border-bottom-color: var(--color-error-soft);
    color: var(--color-error-deep);
  }
  .sfr-header-icon {
    font-size: 14px;
    color: var(--purple-600);
    flex-shrink: 0;
    &.success {
      color: var(--green-600);
    }
  }
  .sfr-header-error .sfr-header-icon {
    color: var(--color-error);
  }

  .sfr-path {
    font-family: 'Monaco', 'Menlo', monospace;
    font-size: 11px;
    color: var(--purple-700);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex: 1;
    min-width: 0;
  }
  .sfr-meta {
    font-size: 11px;
    color: var(--purple-500);
    flex-shrink: 0;
    font-weight: 400;
  }
  .sfr-badge {
    font-size: 11px;
    font-weight: 600;
    padding: 1px 8px;
    border-radius: 4px;
    flex-shrink: 0;
    &.success {
      color: var(--green-600);
      background: var(--color-success-bg);
    }
  }
  .sfr-detail-btn {
    appearance: none;
    border: 1px solid var(--purple-300);
    border-radius: 6px;
    background: var(--color-canvas);
    color: var(--purple-600);
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 12px;
    cursor: pointer;
    padding: 6px 12px;
    flex-shrink: 0;
    font-weight: 500;
    transition: all 0.2s ease;
    &:hover {
      background: var(--color-purple-bg);
      transform: translateY(-1px);
      box-shadow: 0 2px 6px rgba(139, 92, 246, 0.15);
    }
    &:active {
      transform: translateY(0);
    }
  }

  // ── 错误内容 ──
  .sfr-error-msg {
    margin: 0;
    padding: 10px 12px;
    color: var(--color-error-deep);
    font-size: 12px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
  }

  // ── read_file 预览 ──
  .sfr-read-preview {
    max-height: 200px;
    overflow-y: auto;
    background: var(--color-canvas);
    :deep(.file-preview) {
      min-height: 80px;
    }
  }
  .sfr-truncated-hint {
    padding: 6px 12px;
    font-size: 11px;
    color: var(--purple-500);
    text-align: center;
    border-top: 1px solid var(--purple-200);
    background: var(--color-purple-bg);
  }

  // ── list_files ──
  .sfr-empty {
    padding: 16px 12px;
    text-align: center;
    color: var(--purple-400);
    font-style: italic;
  }
  .sfr-file-list {
    padding: 4px 0;
  }
  .sfr-file-item {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 4px 12px;
    color: var(--gray-700);
    &:hover {
      background: var(--color-purple-bg);
    }
  }
  .sfr-file-icon {
    font-size: 12px;
    color: var(--purple-400);
    flex-shrink: 0;
  }
  .sfr-file-name {
    font-size: 12px;
    font-family: 'Monaco', 'Menlo', monospace;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .sfr-more {
    padding: 6px 12px;
    font-size: 11px;
    color: var(--purple-500);
    text-align: center;
    border-top: 1px dashed var(--purple-200);
    background: var(--color-purple-bg);
  }

  // ── write_file 内联预览 ──
  .sfr-inline-preview {
    max-height: 300px;
    overflow-y: auto;
    border-top: 1px solid var(--purple-200);
    background: var(--color-canvas);
    :deep(.file-preview) {
      min-height: 200px;
    }
  }

  // ── 兜底 JSON ──
  .sfr-json {
    margin: 0;
    padding: 10px 12px;
    background: var(--gray-900);
    color: var(--gray-100);
    font-size: 12px;
    line-height: 1.5;
    white-space: pre-wrap;
    word-break: break-word;
    font-family: 'Monaco', 'Menlo', monospace;
  }

  // ── 弹窗内 ──
  .sfr-modal-meta {
    display: flex;
    align-items: center;
    gap: 24px;
    padding: 12px 16px;
    margin-bottom: 20px;
    background: var(--purple-50);
    border: 1px solid var(--purple-200);
    border-radius: 8px;
  }
  .sfr-modal-meta-item {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .sfr-modal-meta-item-right {
    margin-left: auto;
  }
  .sfr-modal-meta-icon {
    font-size: 14px;
    color: var(--purple-600);
    flex-shrink: 0;
  }
  .sfr-modal-meta-label {
    font-size: 12px;
    color: var(--purple-500);
    white-space: nowrap;
  }
  .sfr-modal-meta-value {
    font-size: 13px;
    font-weight: 500;
    color: var(--purple-700);
    font-family: 'Monaco', 'Menlo', monospace;
    word-break: break-all;
  }
  .sfr-modal-meta-value-strong {
    font-size: 14px;
    font-weight: 700;
  }
  .sfr-modal-preview {
    border-radius: 8px;
    overflow: hidden;
    background: var(--color-canvas);
    :deep(.file-preview) {
      min-height: 240px;
    }
  }
  .sfr-modal-list {
    border: 1px solid var(--purple-200);
    border-radius: 8px;
    background: var(--color-canvas);
  }
  .sfr-modal-list-item {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 4px 12px;
    color: var(--color-text-dark);
  }
  .sfr-modal-list-icon {
    font-size: 12px;
    color: var(--purple-400);
    flex-shrink: 0;
  }
  .sfr-modal-list-name {
    font-size: 12px;
    font-family: 'Monaco', 'Menlo', monospace;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
</style>
