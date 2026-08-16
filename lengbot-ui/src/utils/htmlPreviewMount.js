import { HTML_PREVIEW_MIN_HEIGHT, HTML_PREVIEW_MAX_HEIGHT } from './htmlPreviewRenderer'

const HTML_PREVIEW_HEIGHT_MESSAGE = 'lengbot-html-preview-height'

// 已挂载 iframe 注册表：previewId → { iframe, slot }，供 message 监听器回查
const htmlPreviewFrames = new Map()

const getHtmlPreviewCssNumber = (slot, property, fallback) => {
  const preview = slot.closest('.html-preview-render')
  const rawValue = preview ? getComputedStyle(preview).getPropertyValue(property) : ''
  const parsedValue = Number.parseInt(rawValue, 10)
  return Number.isFinite(parsedValue) ? parsedValue : fallback
}

/**
 * 构造 srcdoc：注入基础样式（margin/box-sizing 重置）+ 高度上报脚本。
 * <p>
 * 高度上报脚本：通过 postMessage 向父窗口发送内容高度，
 * 配合 ResizeObserver 监听内容变化实现自适应。
 * </p>
 */
const createMeasuredSrcdoc = (html, previewId) => {
  const scriptEndTag = '<' + '/script>'
  const baseStyle = `<style data-lengbot-html-preview-base>
html,
body {
  margin: 0;
  min-height: 0;
}

body {
  overflow: auto;
}

*,
*::before,
*::after {
  box-sizing: border-box;
}
</style>`
  const script = `<script>
(() => {
  const previewId = ${JSON.stringify(previewId)};
  const ignoredTags = new Set(['SCRIPT', 'STYLE', 'LINK', 'META', 'TITLE']);
  const getContentHeight = () => {
    const body = document.body;
    if (!body) return 0;

    const bodyRect = body.getBoundingClientRect();
    const bodyStyle = getComputedStyle(body);
    const paddingTop = Number.parseFloat(bodyStyle.paddingTop) || 0;
    const paddingBottom = Number.parseFloat(bodyStyle.paddingBottom) || 0;
    let bottom = paddingTop + paddingBottom;

    for (const child of body.children) {
      if (ignoredTags.has(child.tagName)) continue;

      const rect = child.getBoundingClientRect();
      const style = getComputedStyle(child);
      const marginBottom = Number.parseFloat(style.marginBottom) || 0;
      bottom = Math.max(bottom, rect.bottom - bodyRect.top + marginBottom);
    }

    return Math.ceil(Math.max(bottom, body.scrollHeight));
  };
  const sendHeight = () => {
    const height = getContentHeight();
    parent.postMessage({ type: ${JSON.stringify(HTML_PREVIEW_HEIGHT_MESSAGE)}, id: previewId, height }, '*');
  };
  document.querySelectorAll('img, video').forEach((node) => {
    node.addEventListener('load', sendHeight);
    node.addEventListener('error', sendHeight);
  });
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(sendHeight).catch(() => {});
  }
  window.addEventListener('DOMContentLoaded', sendHeight);
  window.addEventListener('load', sendHeight);
  if (typeof ResizeObserver !== 'undefined') {
    const observer = new ResizeObserver(sendHeight);
    if (document.body) observer.observe(document.body);
    document.querySelectorAll('body > *').forEach((node) => observer.observe(node));
  }
  setTimeout(sendHeight, 0);
  setTimeout(sendHeight, 100);
  setTimeout(sendHeight, 500);
})();
${scriptEndTag}`

  const withBaseStyle = /<\/head\s*>/i.test(html)
    ? html.replace(/<\/head\s*>/i, `${baseStyle}</head>`)
    : `${baseStyle}${html}`

  return /<\/body\s*>/i.test(withBaseStyle)
    ? withBaseStyle.replace(/<\/body\s*>/i, `${script}</body>`)
    : `${withBaseStyle}${script}`
}

/**
 * 处理 iframe 内 postMessage 上报的高度：更新 slot 高度，超出 maxHeight 标记 overflow
 */
export function handleHtmlPreviewHeightMessage(event, root) {
  const data = event.data
  if (!data || data.type !== HTML_PREVIEW_HEIGHT_MESSAGE) return

  const entry = htmlPreviewFrames.get(data.id)
  if (!entry || event.source !== entry.iframe.contentWindow) return
  // 仅处理本组件 root 内的 iframe（多实例隔离）
  if (root && !root.contains(entry.slot)) return

  const contentHeight = Number(data.height)
  if (!Number.isFinite(contentHeight) || contentHeight <= 0) return

  const minHeight = getHtmlPreviewCssNumber(entry.slot, '--html-preview-min-height', HTML_PREVIEW_MIN_HEIGHT)
  const maxHeight = getHtmlPreviewCssNumber(entry.slot, '--html-preview-max-height', HTML_PREVIEW_MAX_HEIGHT)
  const nextHeight = Math.min(Math.max(Math.ceil(contentHeight), minHeight), maxHeight)
  entry.slot.style.height = `${nextHeight}px`
  entry.slot.dataset.overflow = contentHeight > maxHeight ? 'true' : 'false'
}

/**
 * 扫描 root 内所有未挂载 iframe 的 .html-preview-frame-slot，
 * 读取兄弟 .html-preview-srcdoc 文本作为 srcdoc，挂载 sandbox=allow-scripts iframe
 */
export function mountHtmlPreviewIframes(root) {
  if (!root) return
  root.querySelectorAll('.html-preview-frame-slot').forEach((slot) => {
    if (slot.querySelector('iframe')) return

    const iframe = document.createElement('iframe')
    const previewId = `html-preview-${
      globalThis.crypto?.randomUUID ? globalThis.crypto.randomUUID() : `${Date.now()}-${Math.random()}`
    }`
    iframe.className = 'html-preview-frame'
    iframe.title = 'HTML 预览'
    // sandbox 仅放开 allow-scripts：脚本可执行但跨域隔离，无 same-origin 访问
    iframe.setAttribute('sandbox', 'allow-scripts')
    iframe.setAttribute('loading', 'lazy')
    iframe.setAttribute('referrerpolicy', 'no-referrer')
    iframe.setAttribute('scrolling', 'auto')
    iframe.srcdoc = createMeasuredSrcdoc(
      slot.parentElement?.querySelector('.html-preview-srcdoc')?.textContent || '',
      previewId
    )
    htmlPreviewFrames.set(previewId, { iframe, slot })
    slot.appendChild(iframe)
  })
}

/**
 * 清理已不在指定 root（或文档）内的 iframe 注册项，避免 message 监听器持续命中
 */
export function cleanupHtmlPreviewFrames(root) {
  for (const [previewId, entry] of htmlPreviewFrames) {
    const stillInDoc = entry.slot.isConnected
    const stillInRoot = !root || root.contains(entry.slot)
    if (!stillInDoc || !stillInRoot) {
      htmlPreviewFrames.delete(previewId)
    }
  }
}
