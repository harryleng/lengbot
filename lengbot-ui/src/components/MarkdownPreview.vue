<template>
  <div
    class="markdown-preview"
    :class="{ 'no-image-preview': !imagePreview }"
    ref="containerRef"
    @click="onContainerClick"
  >
    <div v-html="renderedHtml"></div>
    <span v-if="!finalized" class="typing-cursor">|</span>
  </div>
  <ChatMediaPreview v-if="imagePreview" v-model:open="previewOpen" :src="previewSrc" media-type="image" />
  <MermaidPreviewModal v-model:open="mermaidPreviewOpen" :source="mermaidPreviewSource" />
</template>

<script setup>
import { watch, shallowRef, ref, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { message } from 'ant-design-vue'
import { renderMarkdown } from '@/utils/markdown_preview'
import { renderMermaidDiagrams, resetMermaidTheme } from '@/utils/mermaidRender'
import {
  enhanceMarkdownInteractiveElements,
  destroyMarkdownInteractiveEnhancements,
} from '@/utils/markdownInteractiveEnhance'
import {
  mountHtmlPreviewIframes,
  cleanupHtmlPreviewFrames,
  handleHtmlPreviewHeightMessage,
} from '@/utils/htmlPreviewMount'
import { useTheme } from '@/composables/useTheme'
import { copyToClipboard } from '@/utils/clipboard'
import ChatMediaPreview from '@/components/ChatMediaPreview.vue'
import MermaidPreviewModal from '@/components/MermaidPreviewModal.vue'
import 'katex/dist/katex.min.css'

const props = defineProps({
  content: { type: String, default: '' },
  /** false=流式进行中（补全未闭合标记）；true=对话结束后的终态渲染 */
  finalized: { type: Boolean, default: true },
  /** 是否启用图片点击预览（仅 AI 对话场景需要） */
  imagePreview: { type: Boolean, default: true },
  /** 是否剥离 YAML frontmatter（SKILL.md 等文件需要） */
  stripFrontmatter: { type: Boolean, default: false },
})

const { isDark } = useTheme()
const renderedHtml = shallowRef('')
const containerRef = ref(null)

// ── 图片 / Mermaid 预览 ──
const previewOpen = ref(false)
const previewSrc = ref('')
const mermaidPreviewOpen = ref(false)
const mermaidPreviewSource = ref('')

async function copyCodeText(text) {
  if (!text) return false
  const ok = await copyToClipboard(text)
  if (!ok) message.error('复制失败')
  return ok
}

function onContainerClick(e) {
  const mermaidEl = e.target.closest('.mermaid-diagram-clickable')
  if (mermaidEl && containerRef.value?.contains(mermaidEl)) {
    e.preventDefault()
    mermaidPreviewSource.value = mermaidEl.dataset.mermaidSource || ''
    mermaidPreviewOpen.value = true
    return
  }

  // 拦截 markdown 内的链接点击：外部链接新窗口打开，相对链接阻止跳转
  const anchor = e.target.closest('a')
  if (anchor && containerRef.value?.contains(anchor)) {
    e.preventDefault()
    const href = anchor.getAttribute('href')
    if (href && (href.startsWith('http://') || href.startsWith('https://'))) {
      window.open(href, '_blank', 'noopener')
    }
    return
  }

  if (!props.imagePreview) return
  const img = e.target.closest('img')
  if (!img || !containerRef.value?.contains(img)) return
  if (img.src) {
    previewSrc.value = img.src
    previewOpen.value = true
  }
}

/** 剥离 YAML frontmatter（--- 包裹的头部元数据） */
function stripFrontmatter(text) {
  if (!text) return text
  return text.replace(/^---\n[\s\S]*?\n---\n?/, '')
}

// html:preview iframe 高度自适应消息：组件挂载时绑定、卸载时移除
const onHtmlPreviewMessage = (event) => handleHtmlPreviewHeightMessage(event, containerRef.value)
onMounted(() => window.addEventListener('message', onHtmlPreviewMessage))
onBeforeUnmount(() => {
  window.removeEventListener('message', onHtmlPreviewMessage)
  cleanupHtmlPreviewFrames(containerRef.value)
})

watch(
  () => [props.content, props.finalized, isDark.value],
  async ([val, finalized], _, onCleanup) => {
    let expired = false
    onCleanup(() => {
      expired = true
      destroyMarkdownInteractiveEnhancements(containerRef.value)
    })

    if (!val) {
      renderedHtml.value = ''
      destroyMarkdownInteractiveEnhancements(containerRef.value)
      return
    }

    const textToRender = props.stripFrontmatter ? stripFrontmatter(val) : val
    const theme = isDark.value ? 'github-dark' : 'github-light'
    const html = await renderMarkdown(textToRender, { streaming: !finalized, theme })
    if (expired) return
    renderedHtml.value = html

    if (finalized && html.includes('class="mermaid"')) {
      await nextTick()
      if (expired) return
      resetMermaidTheme()
      await renderMermaidDiagrams(containerRef.value, isDark.value)
    }

    await nextTick()
    if (expired) return
    enhanceMarkdownInteractiveElements(containerRef.value, {
      onCodeCopy: copyCodeText,
    })
    // 挂载 html:preview iframe（流式期间未闭合围栏为骨架占位，无需挂载）
    mountHtmlPreviewIframes(containerRef.value)
    // 清理已不在 DOM 中的旧 iframe 引用（避免 message 监听器内存泄漏）
    cleanupHtmlPreviewFrames(containerRef.value)
  },
  { immediate: true }
)
</script>

<style lang="less">
.markdown-preview {
  max-width: 100%;
  color: var(--gray-1000);
  font-family:
    -apple-system, BlinkMacSystemFont, 'Noto Sans SC', 'PingFang SC', 'Microsoft YaHei', 'Hiragino Sans GB',
    'Source Han Sans CN', sans-serif;
  font-size: 15px;
  line-height: 1.75;
  word-break: break-word;

  h1 {
    font-size: 1.5rem;
    margin: 1rem 0 0.5rem;
    font-weight: 700;
  }
  h2 {
    font-size: 1.25rem;
    margin: 1rem 0 0.5rem;
    font-weight: 600;
  }
  h3,
  h4 {
    font-size: 1.1rem;
    margin: 0.8rem 0 0.4rem;
  }
  h5,
  h6 {
    font-size: 1rem;
    margin: 0.6rem 0 0.3rem;
  }

  p {
    margin: 0.4rem 0;
  }
  p:last-child {
    margin-bottom: 0;
  }

  strong {
    font-weight: 600;
  }

  ul,
  ol {
    padding-left: 1.625rem;
    margin: 0.4rem 0;
  }
  ul:not(.contains-task-list) {
    list-style: disc;
  }
  ol {
    list-style: decimal;
  }
  li {
    margin: 0.15rem 0;
  }
  li > p,
  ol > p,
  ul > p {
    margin: 0.25rem 0;
  }

  ul.contains-task-list,
  ul.task-list {
    list-style: none;
    padding-left: 0;
  }
  .task-list-item {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    list-style: none;
  }
  .task-list-item input[type='checkbox'] {
    appearance: none;
    -webkit-appearance: none;
    width: 16px;
    height: 16px;
    margin: 4px 0 0;
    border: 2px solid var(--gray-300);
    border-radius: 4px;
    background: var(--color-canvas);
    flex-shrink: 0;
    cursor: default;
    position: relative;
  }
  .task-list-item input[type='checkbox']:checked {
    background: var(--green-600);
    border-color: var(--green-600);
  }
  .task-list-item input[type='checkbox']:checked::after {
    content: '';
    position: absolute;
    left: 4px;
    top: 1px;
    width: 4px;
    height: 8px;
    border: solid #fff;
    border-width: 0 2px 2px 0;
    transform: rotate(45deg);
  }

  details.md-details {
    margin: 0.8rem 0;
    border: 1px solid var(--gray-100);
    border-radius: 8px;
    background: var(--color-canvas-soft);
    overflow: hidden;
  }
  details.md-details > summary {
    cursor: pointer;
    padding: 10px 14px;
    font-weight: 600;
    color: var(--gray-800);
    list-style: none;
    user-select: none;
  }
  details.md-details > summary::-webkit-details-marker {
    display: none;
  }
  details.md-details > summary::before {
    content: '▸';
    display: inline-block;
    margin-right: 8px;
    transition: transform 0.15s;
    color: var(--gray-500);
  }
  details.md-details[open] > summary::before {
    transform: rotate(90deg);
  }
  /* details 折叠/展开高度过渡：原生 <details> toggle 是瞬时的无动画，
     用 display:block 覆盖 details:not([open])>non-summary 的 display:none，
     配合 max-height/opacity/padding 过渡实现平滑展开。闭合时 max-height:0 + overflow:hidden
     让内容高度归零。max-height 2000px 容纳常规文档；超大内容会被裁切但不影响交互。 */
  .md-details-body {
    padding: 0 14px 14px;
    border-top: 1px solid var(--gray-100);
    display: block !important;
    max-height: 0;
    overflow: hidden;
    opacity: 0;
    padding-top: 0 !important;
    padding-bottom: 0 !important;
    transition:
      max-height var(--duration-base, 0.24s) var(--easing-standard, ease),
      opacity var(--duration-fast, 0.15s) ease,
      padding-top var(--duration-base, 0.24s) var(--easing-standard, ease),
      padding-bottom var(--duration-base, 0.24s) var(--easing-standard, ease);
  }
  details.md-details[open] > .md-details-body {
    max-height: 2000px;
    opacity: 1;
    padding-bottom: 14px;
  }
  .md-details-body > :first-child {
    margin-top: 12px;
  }
  .md-details-body ul:not(.contains-task-list) {
    list-style: disc;
  }
  .md-details-body ol {
    list-style: decimal;
  }

  .code-block-wrap {
    margin: 0.6rem 0;
    border: 1px solid var(--gray-100);
    border-radius: 8px;
    overflow: hidden;
    background: var(--gray-25);
  }
  .code-block-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 6px 10px;
    border-bottom: 1px solid var(--gray-100);
    background: var(--color-canvas-soft);
  }
  .code-block-lang {
    font-size: 12px;
    color: var(--gray-600);
    font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
    text-transform: lowercase;
  }
  .code-copy-btn {
    border: none;
    background: transparent;
    color: var(--gray-600);
    cursor: pointer;
    width: 28px;
    height: 28px;
    padding: 0;
    border-radius: 6px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    font-size: 14px;
    line-height: 1;
  }
  .code-copy-btn:hover {
    background: var(--gray-25);
    color: var(--main-700);
  }
  .code-copy-btn.is-copied {
    color: var(--green-600);
  }
  .code-copy-btn-mount {
    display: inline-flex;
    align-items: center;
  }
  .code-block-wrap > pre {
    margin: 0;
    border: none;
    border-radius: 0;
  }

  a {
    color: var(--main-700);
    text-decoration: none;
  }
  a:hover {
    text-decoration: underline;
  }

  hr {
    height: 0;
    margin: 1rem 0;
    border: 0;
    border-top: 1px solid var(--gray-200);
  }
  hr.md-hr-asterisk {
    border-top-style: dashed;
    border-top-color: var(--gray-300);
  }
  hr.md-hr-underscore {
    border-top-width: 3px;
    border-top-style: double;
  }

  blockquote {
    margin: 0.6rem 0;
    padding: 0.25rem 0 0.25rem 1rem;
    border-left: 3px solid var(--gray-200);
    color: var(--gray-700);
  }

  code {
    font-family: 'Menlo', 'Monaco', 'Consolas', 'Courier New', monospace;
    font-size: 13px;
    line-height: 1.5;
  }

  :not(pre) > code {
    padding: 1px 5px;
    border-radius: 4px;
    background-color: var(--gray-25);
  }

  pre {
    margin: 0.6rem 0;
    padding: 12px 14px;
    border: 1px solid var(--gray-100);
    border-radius: 8px;
    overflow: auto;
    font-size: 13px;
    line-height: 1.5;
    background: var(--gray-25);

    code {
      padding: 0;
      background: none;
      border-radius: 0;
    }
  }

  table {
    width: 100%;
    border-collapse: collapse;
    margin: 0.8rem 0;
    font-size: 14px;
    display: table;
    border: 1px solid var(--gray-100);
    border-radius: 8px;
    overflow: hidden;
  }

  th,
  td {
    padding: 8px 12px;
    text-align: left;
    border: 1px solid var(--gray-100);
  }

  th {
    background-color: var(--gray-25);
    color: var(--gray-800);
    font-weight: 600;
  }

  td {
    color: var(--gray-800);
  }

  tbody tr:hover {
    background-color: var(--gray-25);
  }

  img {
    display: block;
    max-width: 360px;
    max-height: 260px;
    height: auto;
    border-radius: 6px;
    cursor: pointer;
    object-fit: contain;
    transition: transform 0.2s;
    margin: 0.5rem auto;
  }
  img:hover {
    transform: scale(1.02);
  }

  .mermaid-diagram {
    margin: 0.8rem 0;
    padding: 12px;
    border: 1px solid var(--gray-100);
    border-radius: 8px;
    background: var(--color-canvas);
    overflow-x: auto;
    text-align: center;
  }
  .mermaid-diagram-clickable {
    cursor: zoom-in;
  }
  .mermaid-diagram-clickable:hover {
    border-color: var(--main-700);
    box-shadow: 0 0 0 1px rgba(37, 99, 235, 0.12);
  }
  .mermaid-diagram svg {
    max-width: 100%;
    height: auto;
  }
  pre.mermaid {
    margin: 0.8rem 0;
    padding: 12px 14px;
    border: 1px solid var(--gray-100);
    border-radius: 8px;
    background: var(--gray-25);
    font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
    font-size: 13px;
    line-height: 1.5;
    overflow: auto;
    white-space: pre;
  }
  pre.mermaid.mermaid-render-error {
    border-color: var(--color-error-soft);
    color: var(--color-error);
  }

  /* Shiki 代码高亮块 */
  .code-block-wrap pre.shiki,
  pre.shiki {
    margin: 0.6rem 0;
    padding: 12px 14px;
    border: 1px solid var(--gray-100);
    border-radius: 8px;
    overflow: auto;
    font-size: 13px;
    line-height: 1.5;
  }
  .code-block-wrap pre.shiki {
    margin: 0;
    border: none;
    border-radius: 0;
  }
  pre.shiki code {
    padding: 0;
    background: none;
    border-radius: 0;
    font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
    color: inherit;
  }
  /* 无语言/纯文本代码块：保证浅色背景下文字可读 */
  pre.shiki.github-light {
    background-color: var(--gray-25) !important;
    color: var(--color-text-code) !important;
  }
  pre.shiki.github-light code,
  pre.shiki.github-light code span:not([style*='color']) {
    color: inherit;
  }

  /* ── html:preview sandboxed iframe 预览 ── */
  .html-preview-render {
    width: var(--html-preview-width, 800px);
    max-width: 100%;
    margin: 14px 0;
    overflow: hidden;
    background: #fff;
    border: 1px solid var(--gray-100);
    border-radius: 8px;
  }
  .html-preview-frame-slot {
    display: block;
    width: 100%;
    height: var(--html-preview-min-height, 1px);
    max-height: var(--html-preview-max-height, 700px);
    background: #fff;
  }
  .html-preview-frame {
    display: block;
    width: 100%;
    height: 100%;
    border: 0;
    background: #fff;
  }
  .html-preview-srcdoc {
    display: none;
  }

  /* 流式未闭合围栏：骨架占位（避免空白卡片） */
  .html-preview-loading-slot {
    display: block;
    width: 100%;
    height: clamp(var(--html-preview-height, 360px), 58vh, var(--html-preview-max-height, 700px));
    padding: 24px;
    background: linear-gradient(180deg, #fff 0%, var(--gray-25) 100%);
  }
  .html-preview-loading-canvas {
    position: relative;
    box-sizing: border-box;
    width: 100%;
    height: 100%;
    padding: 26px 28px;
  }
  .html-preview-loading-text {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
  }
  .html-preview-skeleton {
    position: relative;
    overflow: hidden;
    border-radius: 6px;
    background: var(--gray-100);
  }
  .html-preview-skeleton::after {
    content: '';
    position: absolute;
    inset: 0;
    transform: translateX(-100%);
    background: linear-gradient(90deg, transparent 0%, rgba(255, 255, 255, 0.55) 50%, transparent 100%);
    animation: html-preview-skeleton-shimmer 1.45s ease-in-out infinite;
  }
  @keyframes html-preview-skeleton-shimmer {
    100% {
      transform: translateX(100%);
    }
  }
  .html-preview-skeleton-title {
    width: min(280px, 52%);
    height: 28px;
    margin-bottom: 22px;
  }
  .html-preview-skeleton-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 14px;
    margin-bottom: 22px;
  }
  .html-preview-skeleton-card {
    height: 84px;
    border-radius: 8px;
  }
  .html-preview-skeleton-line {
    width: 70%;
    height: 14px;
    margin-top: 12px;
    border-radius: 999px;
  }
  .html-preview-skeleton-line.wide {
    width: 88%;
  }
  .html-preview-skeleton-line.short {
    width: 46%;
  }
}
.no-image-preview img {
  cursor: default;
}
.no-image-preview img:hover {
  transform: none;
}

/* 10.2 打字光标：流式输出时在末尾闪烁 */
.typing-cursor {
  display: inline;
  animation: typing-cursor-blink 0.8s step-end infinite;
  color: var(--gray-600);
  font-weight: 400;
  margin-left: 1px;
}
@keyframes typing-cursor-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}
</style>
