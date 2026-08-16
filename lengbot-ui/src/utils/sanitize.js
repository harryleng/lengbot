import DOMPurify from 'dompurify'

/**
 * 净化 HTML 字符串，防止 XSS 攻击
 * @param {string} html 原始 HTML
 * @param {object} [options] DOMPurify 配置选项
 * @returns {string} 净化后的 HTML
 */
export function sanitizeHtml(html, options = {}) {
  if (!html) return ''
  return DOMPurify.sanitize(html, {
    ADD_TAGS: ['input'],
    ADD_ATTR: ['class', 'style', 'target', 'rel', 'type', 'checked', 'disabled'],
    ...options,
  })
}

/**
 * 净化图谱 / 弹窗用的结构化 HTML（仅保留少量展示标签和内联样式）
 * 用于知识图谱节点、边、tooltip、Modal.confirm 内容等用户可控场景
 * @param {string} html 原始 HTML
 * @returns {string} 净化后的 HTML，无脚本无事件绑定
 */
export function sanitizeGraphHtml(html) {
  if (!html) return ''
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['div', 'span', 'p', 'strong', 'em', 'br', 'b', 'i', 'ul', 'ol', 'li'],
    ALLOWED_ATTR: ['class', 'style'],
    ALLOWED_CSS: ['color', 'font-size', 'font-weight', 'background-color', 'margin', 'padding'],
    FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'link', 'style', 'form'],
    FORBID_ATTR: ['onerror', 'onload', 'onclick', 'onmouseover', 'onmouseenter', 'onsubmit'],
  })
}

/**
 * HTML 转义为纯文本（用于完全不需要任何 HTML 渲染的场景）
 * @param {string} text 原始文本
 * @returns {string} 转义后的纯文本
 */
export function escapeHtml(text) {
  if (text == null) return ''
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
