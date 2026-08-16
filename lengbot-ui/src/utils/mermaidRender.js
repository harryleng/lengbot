import mermaid from 'mermaid'

let initializedTheme = null

/**
 * 按当前主题初始化 mermaid
 * @param {boolean} isDark
 */
function initMermaid(isDark) {
  const theme = isDark ? 'dark' : 'default'
  if (initializedTheme === theme) return
  mermaid.initialize({
    startOnLoad: false,
    theme,
    securityLevel: 'strict',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Noto Sans SC", "PingFang SC", sans-serif',
  })
  initializedTheme = theme
}

/**
 * 渲染单条 mermaid 源码为 SVG
 * @param {string} code
 * @param {boolean} isDark
 * @returns {Promise<{ svg: string, bindFunctions?: Function }>}
 */
export async function renderMermaidSvg(code, isDark = false) {
  initMermaid(isDark)
  const id = `mermaid-modal-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  return mermaid.render(id, code)
}

/**
 * 将容器内 ```mermaid 代码块渲染为 SVG 图
 * @param {HTMLElement|null} container
 * @param {boolean} isDark
 */
export async function renderMermaidDiagrams(container, isDark = false) {
  if (!container) return
  const blocks = container.querySelectorAll('pre.mermaid')
  if (!blocks.length) return

  initMermaid(isDark)

  let index = 0
  for (const block of blocks) {
    const code = (block.textContent || '').trim()
    if (!code) continue
    const id = `mermaid-${Date.now()}-${index++}`
    try {
      const { svg, bindFunctions } = await mermaid.render(id, code)
      const wrapper = document.createElement('div')
      wrapper.className = 'mermaid-diagram mermaid-diagram-clickable'
      wrapper.dataset.mermaidSource = code
      wrapper.innerHTML = svg
      bindFunctions?.(wrapper)
      block.replaceWith(wrapper)
    } catch {
      block.classList.add('mermaid-render-error')
    }
  }
}

/** 主题切换后强制重新初始化 mermaid */
export function resetMermaidTheme() {
  initializedTheme = null
}
