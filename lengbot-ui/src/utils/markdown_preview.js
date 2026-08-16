import MarkdownIt from 'markdown-it'
import markdownItKatex from '@vscode/markdown-it-katex'
import taskLists from 'markdown-it-task-lists'
import DOMPurify from 'dompurify'
import { createHighlighter } from 'shiki'
import { renderHtmlPreviewBlocks } from './htmlPreviewRenderer'

const markdownKatexPlugin = markdownItKatex.default || markdownItKatex

// 简单字符串哈希，用于缓存 key（避免长字符串作 Map key）
function hashStr(str) {
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash + str.charCodeAt(i)) | 0
  }
  return hash.toString(36)
}

// ── Shiki 异步初始化 ──────────────────────────────────────────────

let highlighterPromise
const getHighlighter = () => {
  if (!highlighterPromise) {
    highlighterPromise = createHighlighter({
      themes: ['github-light', 'github-dark'],
      langs: ['plaintext'],
    }).catch((error) => {
      highlighterPromise = undefined
      throw error
    })
  }
  return highlighterPromise
}

const normalizeCodeLanguage = (lang) => {
  if (!lang) return ''
  const map = {
    js: 'javascript',
    ts: 'typescript',
    py: 'python',
    rb: 'ruby',
    cs: 'csharp',
    kt: 'kotlin',
    sh: 'bash',
    shell: 'bash',
    zsh: 'bash',
    yml: 'yaml',
    md: 'markdown',
    tex: 'latex',
    dockerfile: 'docker',
    'c++': 'cpp',
    'c#': 'csharp',
  }
  return map[lang.toLowerCase()] || lang.toLowerCase()
}

const CODE_FENCE_RE = /(^|\n) {0,3}(```|~~~)/
const CODE_FENCE_LANGUAGE_RE = /(^|\n) {0,3}(```+|~~~+)[ \t]*([^\s:,`]*)/g

const hasCodeFence = (content) => CODE_FENCE_RE.test(content)

function extractDetailsBodies(text) {
  if (!text || !/<details/i.test(text)) return []
  const bodies = []
  DETAILS_BLOCK_RE.lastIndex = 0
  let match
  while ((match = DETAILS_BLOCK_RE.exec(text)) !== null) {
    const inner = match[2]
    const summaryMatch = inner.match(/^\s*<summary[\s\S]*?<\/summary>/i)
    const body = summaryMatch ? inner.slice(summaryMatch[0].length).trim() : inner.trim()
    if (body) bodies.push(body)
  }
  return bodies
}

function hasAnyCodeFence(text) {
  if (hasCodeFence(text)) return true
  return extractDetailsBodies(text).some((body) => hasCodeFence(body))
}

function collectAllCodeFenceLanguages(text) {
  const languages = new Set(collectCodeFenceLanguages(text))
  extractDetailsBodies(text).forEach((body) => {
    collectCodeFenceLanguages(body).forEach((lang) => languages.add(lang))
  })
  return [...languages]
}

const collectCodeFenceLanguages = (content) => {
  const languages = new Set()
  for (const match of String(content || '').matchAll(CODE_FENCE_LANGUAGE_RE)) {
    const language = normalizeCodeLanguage(match[3])
    if (language) languages.add(language)
  }
  return [...languages]
}

const ensureLanguages = async (highlighter, languages) => {
  const loaded = new Set(highlighter.getLoadedLanguages())
  await Promise.all(
    languages
      .filter((language) => !loaded.has(language) && language !== 'mermaid')
      .map((language) => {
        try {
          return highlighter.loadLanguage(language).catch(() => null)
        } catch {
          return null
        }
      })
  )
}

/** 复制按钮图标（与 MarkdownPreview 复制成功态共用） */
export const CODE_COPY_BTN_ICON =
  '<svg viewBox="64 64 896 896" width="1em" height="1em" fill="currentColor" aria-hidden="true"><path d="M832 64H296c-4.4 0-8 3.6-8 8v56c0 4.4 3.6 8 8 8h496v688c0 4.4 3.6 8 8 8h56c4.4 0 8-3.6 8-8V96c0-17.7-14.3-32-32-32z"/><path d="M704 192H192c-17.7 0-32 14.3-32 32v530c0 17.7 14.3 32 32 32h512c17.7 0 32-14.3 32-32V224c0-17.7-14.3-32-32-32zm-40 488H232V256h432v424z"/></svg>'

export const CODE_COPIED_BTN_ICON =
  '<svg viewBox="64 64 896 896" width="1em" height="1em" fill="currentColor" aria-hidden="true"><path d="M912 190h-69.9c-9.8 0-19.1 4.5-25.1 12.2L404.7 724.5 207 474a32 32 0 00-25.1-12.2H112c-6.7 0-10.4 7.7-6.3 12.9l273.9 347c12.8 16.2 37.4 16.2 50.3 0l488.4-618.9c4.1-5.1.4-12.8-6.3-12.8z"/></svg>'

/** mermaid 代码块走独立渲染；其余代码块加复制工具栏 */
function installEnhancedFence(md) {
  const defaultFence = md.renderer.rules.fence
  md.renderer.rules.fence = (tokens, idx, options, env, self) => {
    const token = tokens[idx]
    const lang = (token.info || '').trim().split(/\s+/)[0].toLowerCase()
    const rawLang = (token.info || '').trim().split(/\s+/)[0] || 'text'

    if (lang === 'mermaid') {
      return `<pre class="mermaid">${md.utils.escapeHtml(token.content.trimEnd())}</pre>\n`
    }

    let codeHtml
    if (typeof defaultFence === 'function') {
      codeHtml = defaultFence(tokens, idx, options, env, self)
    } else {
      codeHtml = self.renderToken(tokens, idx, options)
    }

    const langLabel = md.utils.escapeHtml(rawLang)
    return `<div class="code-block-wrap">
<div class="code-block-toolbar">
<span class="code-block-lang">${langLabel}</span>
<button type="button" class="code-copy-btn" aria-label="复制代码">${CODE_COPY_BTN_ICON}</button>
</div>
${codeHtml}
</div>\n`
  }
}

function applyCommonMarkdownPlugins(md) {
  md.use(markdownKatexPlugin, { throwOnError: false, errorColor: '#cc0000', trust: false }).use(taskLists, {
    enabled: false,
    label: false,
    labelAfter: false,
  })
  installEnhancedFence(md)
  return md
}

const DETAILS_BLOCK_RE = /<details(\s[^>]*)?>([\s\S]*?)<\/details>/gi

/**
 * 将 HTML details 块内的 Markdown 正文二次渲染（解决块级 HTML 内 Markdown 不解析问题）
 * @param {string} text
 * @param {import('markdown-it')} md
 * @returns {string}
 */
function renderWithDetailsBlocks(text, md) {
  if (!text || !/<details/i.test(text)) {
    return md.render(text)
  }

  const parts = []
  let lastIndex = 0
  let match

  DETAILS_BLOCK_RE.lastIndex = 0
  while ((match = DETAILS_BLOCK_RE.exec(text)) !== null) {
    if (match.index > lastIndex) {
      parts.push({ type: 'md', content: text.slice(lastIndex, match.index) })
    }

    const attrs = match[1] || ''
    const inner = match[2]
    const summaryMatch = inner.match(/^\s*<summary(\s[^>]*)?>([\s\S]*?)<\/summary>/i)

    if (summaryMatch) {
      const summaryAttrs = summaryMatch[1] || ''
      const summaryHtml = summaryMatch[2]
      const bodyMarkdown = inner.slice(summaryMatch[0].length).trim()
      const bodyHtml = bodyMarkdown ? md.render(preprocessDetailsBody(bodyMarkdown)) : ''
      parts.push({
        type: 'html',
        content: `<details class="md-details"${attrs}><summary${summaryAttrs}>${summaryHtml}</summary>${bodyHtml ? `<div class="md-details-body">${bodyHtml}</div>` : ''}</details>`,
      })
    } else {
      parts.push({ type: 'md', content: match[0] })
    }

    lastIndex = DETAILS_BLOCK_RE.lastIndex
  }

  if (lastIndex < text.length) {
    parts.push({ type: 'md', content: text.slice(lastIndex) })
  }

  return parts.map((part) => (part.type === 'md' ? md.render(part.content) : part.content)).join('')
}

/** 判断上一行是否为列表项 */
const LIST_ITEM_RE = /^([ \t]*)([-*+]|\d+\.)\s+/

/** 列表项内的缩进续行（无列表标记，2+ 空格开头） */
function isListContinuationLine(line) {
  return /^[ \t]{2,}\S/.test(line)
}

/**
 * 表格结束后若紧跟普通段落，补空行，避免 markdown-it 把段落吞进表格末行
 * （复用 isTableDataLine 的判定口径：含 ≥2 个 | 分隔单元格才算表格行）
 */
function ensureBlankLineAfterTables(text) {
  if (!text) return text
  const lines = text.split('\n')
  const out = []
  let inFence = false

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const trim = line.trim()

    if (trim.startsWith('```')) {
      inFence = !inFence
      out.push(line)
      continue
    }
    if (inFence) {
      out.push(line)
      continue
    }

    const isTableRow = isTableDataLine(line)
    const prevLine = out.length > 0 ? out[out.length - 1] : ''
    const prevIsTableRow = isTableDataLine(prevLine)

    if (!isTableRow && trim !== '' && prevIsTableRow) {
      out.push('')
    }

    out.push(line)
  }
  return out.join('\n')
}

/**
 * 列表结束后若紧跟普通段落，补空行，避免 markdown-it 将段落解析为列表 lazy continuation
 */
function ensureBlankLineAfterLists(text) {
  if (!text) return text
  const lines = text.split('\n')
  const out = []
  let inFence = false

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const trim = line.trim()

    if (trim.startsWith('```')) {
      inFence = !inFence
      out.push(line)
      continue
    }
    if (inFence) {
      out.push(line)
      continue
    }

    const isListItem = LIST_ITEM_RE.test(line)
    const prevLine = out.length > 0 ? out[out.length - 1] : ''
    const prevIsListItem = LIST_ITEM_RE.test(prevLine)

    if (!isListItem && trim !== '' && !isListContinuationLine(line) && prevIsListItem) {
      out.push('')
    }

    out.push(line)
  }
  return out.join('\n')
}

/**
 * 将独立成行的 --- / *** / ___ 转为 <hr> HTML（跳过代码围栏）
 * 避免 markdown-it 在 HTML 块模式下吞掉后续 Markdown。
 */
function convertStandaloneThematicBreaks(text) {
  if (!text) return text
  const lines = text.split('\n')
  const out = []
  let inFence = false

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const trim = line.trim()

    if (trim.startsWith('```')) {
      inFence = !inFence
      out.push(line)
      continue
    }
    if (inFence) {
      out.push(line)
      continue
    }

    if (/^(-{3,})$/.test(trim)) {
      // AI 输出中 --- 几乎总是章节分割线，而非 setext 标题下划线；统一转 <hr> 避免误解析为 h2
      out.push('<hr class="md-hr-dash" />')
      continue
    }
    if (/^(\*{3,})$/.test(trim)) {
      out.push('<hr class="md-hr-asterisk" />')
      continue
    }
    if (/^(_{3,})$/.test(trim)) {
      out.push('<hr class="md-hr-underscore" />')
      continue
    }

    out.push(line)
  }
  return out.join('\n')
}

/**
 * HTML 块闭合标签后若紧贴 Markdown / 分割线，markdown-it 会把后续内容当 HTML 文本。
 * 在闭合标签与后续内容之间补空行。
 */
function normalizeBlankLineAfterHtmlBlocks(text) {
  if (!text || !/<\/\w+/i.test(text)) return text
  let s = text
  s = s.replace(/(<\/[a-z][\w:-]*\s*>)[ \t]*\n(?![ \t]*\n)([ \t]*(?:-{3,}|\*{3,}|_{3,})[ \t]*(?=\n|$))/gim, '$1\n\n$2')
  s = s.replace(/(<\/[a-z][\w:-]*\s*>)[ \t]*\n(?![ \t]*\n)([ \t]*(?:#{1,6}\s|[-*+]\s|\d+\.\s|>|```))/gim, '$1\n\n$2')
  return s
}

/** 每个 <hr /> 行后补空行，避免连续 HTML 块吞掉后续 Markdown */
function ensureBlankLineAfterHtmlHr(text) {
  if (!text || !/<hr[\s>]/i.test(text)) return text
  return text.replace(/^(<hr[^>]*\/?>)[ \t]*\n(?![ \t]*\r?\n)/gim, '$1\n\n')
}

/** Markdown 预处理 */
function preprocessMarkdown(text) {
  if (!text) return ''
  let s = normalizeMarkdown(text)
  s = normalizeCodeFences(s)
  s = normalizeBlankLineAfterHtmlBlocks(s)
  s = ensureBlankLineAfterLists(s)
  s = ensureBlankLineAfterTables(s)
  s = convertStandaloneThematicBreaks(s)
  s = ensureBlankLineAfterHtmlHr(s)
  return s
}

/** details 内嵌正文预处理 */
function preprocessDetailsBody(text) {
  return preprocessMarkdown(text)
}

const PURIFY_CONFIG = {
  ADD_TAGS: ['input', 'details', 'summary', 'button', 'hr'],
  ADD_ATTR: [
    'class',
    'style',
    'target',
    'rel',
    'type',
    'checked',
    'disabled',
    'open',
    'aria-label',
    'title',
    'data-mermaid-source',
  ],
}

function sanitizeHtml(html) {
  return DOMPurify.sanitize(html, PURIFY_CONFIG)
}

/**
 * html:preview iframe 内容专用 sanitizer：
 * 允许整文档结构（html/head/body/style/link）但禁止脚本/表单/嵌套框架，
 * 配合 iframe sandbox=allow-scripts 形成两层隔离
 */
const HTML_PREVIEW_SRCDOC_CONFIG = {
  WHOLE_DOCUMENT: true,
  ADD_TAGS: ['html', 'head', 'body', 'style', 'link'],
  FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form', 'input', 'textarea', 'select'],
  FORBID_ATTR: ['srcdoc', 'sandbox'],
}

function sanitizeHtmlPreviewSrcdoc(html) {
  return DOMPurify.sanitize(html, HTML_PREVIEW_SRCDOC_CONFIG)
}

// ── markdown-it 渲染器工厂（带缓存）─────────────────────────────────

const rendererCache = new Map()

const createRenderer = ({ themeName, highlighter }) => {
  const md = new MarkdownIt({
    html: true,
    breaks: true,
    linkify: true,
    typographer: true,
    highlight: highlighter
      ? (code, lang) => {
          const language = normalizeCodeLanguage(lang)
          if (language === 'mermaid') return ''
          const loadedLanguages = highlighter.getLoadedLanguages()
          const targetLanguage = loadedLanguages.includes(language) ? language : 'plaintext'
          return highlighter.codeToHtml(code, { lang: targetLanguage, theme: themeName })
        }
      : undefined,
  })
  return applyCommonMarkdownPlugins(md)
}

const getRenderer = async (theme, needsHighlight) => {
  const cacheKey = needsHighlight ? theme : 'plain'
  const cached = rendererCache.get(cacheKey)
  if (cached) return cached

  const rendererPromise = needsHighlight
    ? getHighlighter().then((highlighter) => createRenderer({ themeName: theme, highlighter }))
    : Promise.resolve(createRenderer({ themeName: theme }))
  rendererCache.set(cacheKey, rendererPromise)
  return rendererPromise
}

// ── HTML 缓存（LRU 100 条）──────────────────────────────────────────

const renderedHtmlCache = new Map()
const MAX_RENDER_CACHE_SIZE = 100

const getCachedHtml = (cacheKey) => renderedHtmlCache.get(cacheKey)
const setCachedHtml = (cacheKey, html) => {
  if (renderedHtmlCache.size >= MAX_RENDER_CACHE_SIZE) {
    renderedHtmlCache.delete(renderedHtmlCache.keys().next().value)
  }
  renderedHtmlCache.set(cacheKey, html)
}

// ── normalizeMarkdown（精简版：仅处理后端转义问题）──────────────────

/**
 * 规范化 LLM 常见 Markdown 写法（尤其中文模型省略空格/换行的情况）。
 * markdown-it 配置 breaks/html/linkify/typographer 后，大部分问题由引擎自动处理，
 * 此处仅修复引擎无法处理的边界情况。
 */
export function normalizeMarkdown(text) {
  if (!text) return ''

  let s = text.replace(/\r\n/g, '\n')

  // 表格行被挤在一行：用 || 连接下一格/下一行（模型常见输出）
  s = s.replace(/\|\|\s*(?=\s*\*\*)/g, '|\n|')
  s = s.replace(/\|\|\s*(?=\s*-{2,})/g, '|\n|')

  // 无序列表「-文字」缺空格（markdown-it 需要 - 后有空格）
  s = s.replace(/^(-)([^\s-])/gm, '$1 $2')

  // ATX 标题缺空格：##标题 → ## 标题（markdown-it 需要 # 后有空格）
  s = s.replace(/^(#{1,6})([^\s#\n])/gm, '$1 $2')

  // 非行首标题：中文/标点后紧跟 ## → 拆行 + 补空行
  s = s.replace(/([：:；;。！？])(#{1,6})/g, '$1\n\n$2')
  s = s.replace(/([^ \t\n#])(#{1,6})([^\s#])/g, '$1$2 $3')
  s = s.replace(/([^\n# \t])(#{1,6}\s)/g, '$1\n\n$2')

  // 有序列表缺空格：1.文字 → 1. 文字
  s = s.replace(/^(\d+\.)([^\s\d*.])/gm, '$1 $2')
  // 有序列表缺空格：1.** → 1. **
  s = s.replace(/^(\d+\.)(\*\*)/gm, '$1 $2')

  // 列表项挤在同一行：「xxx- **」或「xxx - 」→ 拆行（AI 常见输出）
  // 前导字符排除 - 和 |，避免误伤表格分隔行 | --- | 与连续短横线
  s = s.replace(/([^\s|-])(- \*\*)/g, '$1\n$2')
  s = s.replace(/([^\s|-])(- [^\s-*])/g, '$1\n$2')

  // 分隔线与标题/粗体粘连在同一行
  s = s.replace(/(---)(#{1,6})/g, '$1\n\n$2')
  s = s.replace(/(---)(\*\*)/g, '$1\n\n$2')
  s = s.replace(/(---)(-\s)/g, '$1\n\n$2')
  s = s.replace(/(---)(\d+\.)/g, '$1\n\n$2')

  return s
}

function splitTableCellsForTable(line) {
  let s = String(line).trim()
  if (!s.includes('|')) return []
  if (s.startsWith('|')) s = s.slice(1).trimStart()
  if (s.endsWith('|')) s = s.slice(0, -1).trimEnd()
  if (!s) return []
  return s.split('|').map((c) => c.trim())
}

function isTableSeparatorRow(cells) {
  if (!cells || cells.length < 2) return false
  return cells.every((c) => /^:?-{3,}:? *$/.test(c))
}

function isTableDataLine(line) {
  const t = line.trim()
  if (!t || !t.includes('|')) return false
  if (t.startsWith('```')) return false
  if (/^#{1,6}\s/.test(t) && (t.match(/\|/g) || []).length < 2) return false
  const cells = splitTableCellsForTable(line)
  if (cells.length < 2) return false
  return true
}

function padTableCells(cells, n) {
  const out = cells.slice(0, n)
  while (out.length < n) out.push('')
  return out
}

function formatTableRow(cells) {
  return '| ' + cells.join(' | ') + ' |'
}

/**
 * 流式阶段补全未闭合的 GFM 表格（补分隔行、统一列数），便于 markdown-it 解析出 <table>
 */
function patchStreamingTables(text) {
  if (!text || !text.includes('|')) return text

  const lines = text.split('\n')
  const out = []
  let i = 0
  let inFence = false

  while (i < lines.length) {
    const line = lines[i]
    const trim = line.trim()

    if (trim.startsWith('```')) {
      inFence = !inFence
      out.push(line)
      i++
      continue
    }
    if (inFence) {
      out.push(line)
      i++
      continue
    }

    if (!isTableDataLine(line)) {
      out.push(line)
      i++
      continue
    }

    const block = []
    let j = i
    while (j < lines.length) {
      const L = lines[j]
      const t = L.trim()
      if (t.startsWith('```')) break
      if (t === '' && block.length > 0) break
      if (t === '' && block.length === 0) {
        j++
        break
      }
      if (t === '') break
      if (!isTableDataLine(L)) break
      block.push(L)
      j++
    }

    if (block.length === 0) {
      out.push(line)
      i++
      continue
    }

    const parsed = block.map((raw) => {
      const cells = splitTableCellsForTable(raw)
      return { raw, cells, isSep: isTableSeparatorRow(cells) }
    })

    let n = 2
    parsed.forEach((p) => {
      n = Math.max(n, p.cells.length)
    })

    const rows = []
    for (let k = 0; k < parsed.length; k++) {
      const p = parsed[k]
      rows.push({ ...p, cells: padTableCells(p.cells, n) })

      if (k === 0 && !p.isSep) {
        const next = parsed[k + 1]
        if (!next || !next.isSep) {
          rows.push({ isSep: true, synthetic: true, cells: Array(n).fill('---') })
        }
      }
    }

    rows.forEach((r) => {
      if (r.isSep) {
        const cells = r.cells.map((c) => (/^:?-{3,}:? *$/.test(c) ? c.replace(/\s+/g, '') || '---' : '---'))
        out.push(formatTableRow(padTableCells(cells, n)))
      } else {
        out.push(formatTableRow(r.cells))
      }
    })

    i = j
  }

  return out.join('\n')
}

// ── 代码围栏畸形修复 ──────────────────────────────────────────

/**
 * 修复 AI 输出中常见的代码围栏畸形：
 * 1. 语言标签和代码粘在一行：```javascriptconst x = 1;  →  ```javascript\nconst x = 1;
 * 2. 闭合 ``` 紧跟内容不在行首：code```  →  code\n```
 */
function normalizeCodeFences(text) {
  if (!text || !text.includes('```')) return text

  const KNOWN_LANGS = [
    'javascript',
    'typescript',
    'python',
    'java',
    'csharp',
    'ruby',
    'go',
    'rust',
    'c',
    'cpp',
    'php',
    'swift',
    'kotlin',
    'scala',
    'bash',
    'shell',
    'sql',
    'html',
    'css',
    'json',
    'yaml',
    'xml',
    'markdown',
    'latex',
    'r',
    'lua',
    'perl',
    'haskell',
    'elixir',
    'clojure',
    'groovy',
    'powershell',
    'dockerfile',
    'makefile',
    'toml',
  ]

  let s = text

  // 修复1：``` 后语言标签和代码粘连（无换行）
  // 标题```javascript代码 → 标题\n```javascript\n代码
  // 通过已知语言列表精确切分语言标签和代码（解决 javascriptconst 无法拆分的问题）
  s = s.replace(/(^|\n)(.*?)`{3,}([a-zA-Z0-9_+#.:-]{1,20})((?:\n)|[^\n`])/gm, (match, prefix, before, lang, after) => {
    // 尝试从 lang 中切出已知语言名
    let actualLang = lang
    let codeAfter = after
    if (!KNOWN_LANGS.includes(lang.toLowerCase())) {
      for (let i = lang.length - 1; i >= 2; i--) {
        if (KNOWN_LANGS.includes(lang.substring(0, i).toLowerCase())) {
          actualLang = lang.substring(0, i)
          codeAfter = lang.substring(i) + after
          break
        }
      }
    }
    const needsSplit = before && /\S$/.test(before)
    const beforeFixed = needsSplit ? before + '\n' : before
    if (codeAfter === '\n') {
      return prefix + beforeFixed + '```' + actualLang + '\n'
    }
    return prefix + beforeFixed + '```' + actualLang + '\n' + codeAfter
  })

  // 修复2：闭合 ``` 在行尾紧跟内容（markdown-it 要求 ``` 独占一行）
  // code```  →  code\n```
  // .*\S 匹配行内容（含反引号），``` 后仅允许空白到行尾（排除 ```language 开头的围栏）
  s = s.replace(/(^|\n)(.*\S)`{3,}[ \t]*$/gm, '$1$2\n```')

  return s
}

// ── 流式 Markdown 补丁 ──────────────────────────────────────────

function patchStreamingMarkdown(text) {
  let processed = text

  const backtickCount = (processed.match(/```/g) || []).length
  if (backtickCount % 2 !== 0) {
    processed += '\n```'
  }
  const boldCount = (processed.match(/\*\*/g) || []).length
  if (boldCount % 2 !== 0) {
    processed += '**'
  }
  return processed
}

// ── 渲染入口 ──────────────────────────────────────────────────────

/**
 * 渲染 Markdown 为 HTML（异步，支持 Shiki 语言按需加载）
 * @param {string} text 原始文本
 * @param {{ streaming?: boolean, theme?: string }} options
 * @returns {Promise<string>}
 */
export async function renderMarkdown(text, { streaming = false, theme = 'github-dark' } = {}) {
  if (!text) return ''

  let processed = preprocessMarkdown(text)
  // html:preview 围栏 → sandboxed iframe 预览块（必须在 markdown-it 之前替换，
  // 否则会被 fence 渲染器当普通代码块处理）
  processed = renderHtmlPreviewBlocks(processed, { sanitizeHtml: sanitizeHtmlPreviewSrcdoc })
  processed = patchStreamingTables(processed)
  if (streaming) {
    processed = patchStreamingMarkdown(processed)
  }

  const themeName = theme === 'github-dark' ? 'github-dark' : 'github-light'
  const needsHighlight = hasAnyCodeFence(processed)
  const cacheKey = `${needsHighlight ? themeName : 'plain'}:${hashStr(processed)}_${processed.length}`

  const cachedHtml = getCachedHtml(cacheKey)
  if (cachedHtml !== undefined) return cachedHtml

  if (needsHighlight) {
    const highlighter = await getHighlighter()
    await ensureLanguages(highlighter, collectAllCodeFenceLanguages(processed))
  }

  const md = await getRenderer(themeName, needsHighlight)
  const rawHtml = /<details/i.test(processed) ? renderWithDetailsBlocks(processed, md) : md.render(processed)

  const html = sanitizeHtml(rawHtml)

  setCachedHtml(cacheKey, html)
  return html
}

// ── 同步渲染（供 computed 等同步场景使用，无 Shiki 高亮）────────────

const syncRenderer = applyCommonMarkdownPlugins(
  new MarkdownIt({
    html: true,
    breaks: true,
    linkify: true,
    typographer: true,
  })
)

/**
 * 同步渲染 Markdown 为 HTML（无 Shiki 高亮，使用内置高亮）
 * 适用于 computed 等同步场景，如文件预览、文档编辑器等。
 * @param {string} text 原始文本
 * @returns {string}
 */
export function renderMarkdownSync(text) {
  if (!text) return ''
  let processed = preprocessMarkdown(text)
  processed = renderHtmlPreviewBlocks(processed, { sanitizeHtml: sanitizeHtmlPreviewSrcdoc })
  const rawHtml = /<details/i.test(processed)
    ? renderWithDetailsBlocks(processed, syncRenderer)
    : syncRenderer.render(processed)
  return sanitizeHtml(rawHtml)
}
