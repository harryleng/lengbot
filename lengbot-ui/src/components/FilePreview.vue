<template>
  <div class="file-preview">
    <!-- 加载中 -->
    <div v-if="effectiveLoading" class="preview-loading">
      <a-spin />
      <span>加载中...</span>
    </div>

    <!-- Office 等：知识库不提供源文件预览 -->
    <div v-else-if="isBlockedSourcePreview" class="preview-unsupported">
      <FileOutlined class="preview-icon" />
      <p>{{ fileName }}</p>
      <p class="preview-hint">
        <template v-if="extension">.{{ extension }}</template>
        格式不支持在线预览
      </p>
      <button v-if="effectiveDownloadUrl" class="btn-primary-sm" @click="handleDownload">
        <DownloadOutlined />
        下载文件
      </button>
    </div>

    <!-- PDF 预览：Chrome 内置 PDF 阅读器本身是 JS 应用，需 allow-scripts 才能渲染；
         嵌入式 JS 风险由浏览器内置阅读器的二次沙箱兜底，此处不再额外禁脚本 -->
    <iframe
      v-else-if="isPdf && fileUrl"
      :src="fileUrl"
      class="preview-iframe"
      frameborder="0"
      sandbox="allow-same-origin allow-scripts"
    ></iframe>

    <!-- HTML 预览：优先用已拉取的正文 srcdoc 内联渲染，绕开 MinIO 预签名 URL 的 attachment/Content-Type 头导致的空白页；无正文时才回退到 :src -->
    <iframe
      v-else-if="isHtml && !forceText && htmlRenderable"
      :srcdoc="resolvedContent || undefined"
      :src="!resolvedContent && fileUrl ? fileUrl : undefined"
      class="preview-iframe"
      frameborder="0"
      sandbox="allow-same-origin"
      @error="htmlLoadError = true"
    ></iframe>

    <!-- 图片预览 -->
    <div v-else-if="isImage && fileUrl" class="preview-image-wrapper">
      <img :src="fileUrl" class="preview-image" :alt="fileName" />
    </div>

    <!-- Markdown 预览 -->
    <div
      v-else-if="isMarkdown && !forceText && resolvedContent"
      class="preview-markdown markdown-body"
      v-html="renderedMarkdown"
    ></div>

    <!-- 代码/文本预览 -->
    <pre v-else-if="isText && resolvedContent" class="preview-text">{{ resolvedContent }}</pre>

    <!-- 渲染型（HTML/Markdown）切换为纯文本 或 HTML 解析失败降级 -->
    <pre v-else-if="(isHtml || isMarkdown) && resolvedContent" class="preview-text">{{ resolvedContent }}</pre>

    <!-- 不支持预览 -->
    <div v-else class="preview-unsupported">
      <FileOutlined class="preview-icon" />
      <p>{{ fileName }}</p>
      <p class="preview-hint">
        <template v-if="extension">.{{ extension }}</template>
        格式不支持在线预览
      </p>
      <button v-if="effectiveDownloadUrl" class="btn-primary-sm" @click="handleDownload">
        <DownloadOutlined />
        下载文件
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { renderMarkdownSync } from '@/utils/markdown_preview'
import { FileOutlined, DownloadOutlined } from '@ant-design/icons-vue'
import {
  fetchTextContent,
  isTextSourcePreviewable,
  hasSourceFilePreview,
  IMAGE_SOURCE_PREVIEW_EXTENSIONS,
} from '@/utils/filePreview'
import { triggerBrowserDownload } from '@/utils/fileDownload'

const props = defineProps({
  /** 文件URL */
  fileUrl: {
    type: String,
    default: '',
  },
  /** 文件名 */
  fileName: {
    type: String,
    default: '',
  },
  /** 文件类型（扩展名） */
  fileType: {
    type: String,
    default: '',
  },
  /** 文本内容（用于文本/Markdown预览） */
  content: {
    type: String,
    default: '',
  },
  /** 是否加载中 */
  loading: {
    type: Boolean,
    default: false,
  },
  /** 下载 URL（可与预览 URL 不同） */
  downloadUrl: {
    type: String,
    default: '',
  },
  /** 强制以纯文本样式预览（用于 HTML/Markdown 渲染型切换为文本） */
  forceText: {
    type: Boolean,
    default: false,
  },
})

const fetchedContent = ref('')
const fetching = ref(false)
const htmlLoadError = ref(false)

const extension = computed(() => {
  if (props.fileType) return props.fileType.toLowerCase()
  if (props.fileName) {
    const dot = props.fileName.lastIndexOf('.')
    return dot > 0 ? props.fileName.substring(dot + 1).toLowerCase() : ''
  }
  return ''
})

const resolvedContent = computed(() => props.content || fetchedContent.value)
const effectiveLoading = computed(() => props.loading || fetching.value)
const effectiveDownloadUrl = computed(() => props.downloadUrl || props.fileUrl)

const isPdf = computed(() => extension.value === 'pdf')
const isImage = computed(() => IMAGE_SOURCE_PREVIEW_EXTENSIONS.has(extension.value))
const isMarkdown = computed(() => ['md', 'markdown'].includes(extension.value))
const isHtml = computed(() => ['html', 'htm'].includes(extension.value))
const isText = computed(() => isTextSourcePreviewable(extension.value))

const isBlockedSourcePreview = computed(() => {
  const ext = extension.value
  return ext ? !hasSourceFilePreview(ext) : false
})

const renderedMarkdown = computed(() => {
  if (!resolvedContent.value) return ''
  return renderMarkdownSync(resolvedContent.value)
})

// HTML 是否可渲染：有 URL 直接渲染；仅有内容时校验能否解析出有效 HTML 结构，否则降级纯文本
const htmlRenderable = computed(() => {
  if (htmlLoadError.value) return false
  const content = resolvedContent.value
  // 有内容时校验能否解析出有效 HTML 结构；无内容仅有 URL 时直接交给 iframe 渲染
  if (content) return isParsableHtml(content)
  return !!props.fileUrl
})

function isParsableHtml(content) {
  try {
    const doc = new DOMParser().parseFromString(content, 'text/html')
    // 解析出错节点或无任何元素则视为不可渲染
    if (doc.querySelector('parsererror')) return false
    return !!doc.body && doc.body.children.length > 0
  } catch {
    return false
  }
}

watch(
  () => [props.fileUrl, props.fileName, props.fileType, props.content],
  async () => {
    fetchedContent.value = ''
    htmlLoadError.value = false
    const isTextLike =
      isTextSourcePreviewable(extension.value) || ['md', 'markdown', 'html', 'htm'].includes(extension.value)
    const needFetch = !props.content && props.fileUrl && isTextLike
    if (!needFetch) {
      fetching.value = false
      return
    }
    fetching.value = true
    try {
      fetchedContent.value = await fetchTextContent(props.fileUrl)
    } catch {
      fetchedContent.value = ''
    } finally {
      fetching.value = false
    }
  },
  { immediate: true }
)

function handleDownload() {
  if (effectiveDownloadUrl.value) {
    triggerBrowserDownload(effectiveDownloadUrl.value, props.fileName || 'download')
  }
}
</script>

<style scoped>
.file-preview {
  width: 100%;
  height: 100%;
  min-height: 400px;
  display: flex;
  flex-direction: column;
}

.preview-loading {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--color-mute);
}

.preview-iframe {
  flex: 1;
  width: 100%;
  min-height: 500px;
  border: none;
}

.preview-image-wrapper {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: var(--color-canvas-soft);
}

.preview-image {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.preview-markdown {
  flex: 1;
  padding: 16px;
  overflow-y: auto;
  font-size: 14px;
  line-height: 1.8;
}

.preview-markdown :deep(h1),
.preview-markdown :deep(h2),
.preview-markdown :deep(h3) {
  margin-top: 16px;
  margin-bottom: 8px;
  font-weight: 600;
}

.preview-markdown :deep(p) {
  margin-bottom: 12px;
}

.preview-markdown :deep(code) {
  background: var(--color-canvas-soft-2);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
}

.preview-markdown :deep(pre) {
  background: var(--color-canvas-soft-2);
  padding: 16px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 12px 0;
}

.preview-markdown :deep(pre code) {
  background: none;
  padding: 0;
}

.preview-markdown :deep(ul),
.preview-markdown :deep(ol) {
  padding-left: 24px;
  margin-bottom: 12px;
}

.preview-markdown :deep(li) {
  margin-bottom: 4px;
}

.preview-markdown :deep(blockquote) {
  border-left: 4px solid #0070f3;
  padding-left: 16px;
  margin: 12px 0;
  color: var(--color-body);
}

.preview-markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 12px 0;
}

.preview-markdown :deep(th),
.preview-markdown :deep(td) {
  border: 1px solid var(--color-hairline);
  padding: 8px 12px;
  text-align: left;
}

.preview-markdown :deep(th) {
  background: var(--color-canvas-soft);
  font-weight: 600;
}

.preview-text {
  flex: 1;
  background: var(--color-canvas-soft-2);
  padding: 16px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  overflow-y: auto;
}

.preview-unsupported {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--color-mute);
}

.preview-icon {
  font-size: 48px;
  color: #d4d4d8;
}

.preview-hint {
  font-size: 13px;
  color: var(--color-mute);
}

.btn-primary-sm {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  background: var(--color-primary);
  color: #fff;
  border: none;
  border-radius: 100px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.btn-primary-sm:hover {
  background: #27272a;
}
</style>
