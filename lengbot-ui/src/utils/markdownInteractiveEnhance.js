import { createApp, h, ref } from 'vue'
import { Tooltip } from 'ant-design-vue'
import { CODE_COPY_BTN_ICON, CODE_COPIED_BTN_ICON } from '@/utils/markdown_preview'

const containerApps = new WeakMap()

function tooltipPopupContainer() {
  return document.body
}

/**
 * 卸载容器内由 enhance 挂载的 Vue 实例
 * @param {HTMLElement|null} container
 */
export function destroyMarkdownInteractiveEnhancements(container) {
  if (!container) return
  const apps = containerApps.get(container)
  if (!apps?.length) return
  apps.forEach(({ app }) => app.unmount())
  containerApps.delete(container)
}

/**
 * 为 Markdown 内代码复制按钮挂载 ant-design-vue Tooltip
 * @param {HTMLElement|null} container
 * @param {{ onCodeCopy?: (text: string) => Promise<boolean> }} options
 */
export function enhanceMarkdownInteractiveElements(container, { onCodeCopy } = {}) {
  if (!container) return
  destroyMarkdownInteractiveEnhancements(container)

  const apps = []

  container.querySelectorAll('.code-copy-btn').forEach((btn) => {
    const parent = btn.parentNode
    if (!parent) return
    const mountEl = document.createElement('span')
    mountEl.className = 'code-copy-btn-mount'
    parent.replaceChild(mountEl, btn)

    const title = ref('复制')
    const isCopied = ref(false)

    const app = createApp({
      setup() {
        const handleClick = async (e) => {
          e.preventDefault()
          e.stopPropagation()
          const wrap = mountEl.closest('.code-block-wrap')
          const codeEl = wrap?.querySelector('pre code') || wrap?.querySelector('pre')
          const text = codeEl?.textContent || ''
          if (!text) return
          const ok = await onCodeCopy?.(text)
          if (!ok) return
          isCopied.value = true
          title.value = '已复制'
          setTimeout(() => {
            isCopied.value = false
            title.value = '复制'
          }, 1500)
        }

        return () =>
          h(
            Tooltip,
            {
              title: title.value,
              placement: 'top',
              getPopupContainer: tooltipPopupContainer,
            },
            () =>
              h('button', {
                class: ['code-copy-btn', isCopied.value ? 'is-copied' : ''],
                innerHTML: isCopied.value ? CODE_COPIED_BTN_ICON : CODE_COPY_BTN_ICON,
                onClick: handleClick,
              })
          )
      },
    })
    app.mount(mountEl)
    apps.push({ app, mountEl })
  })

  if (apps.length) {
    containerApps.set(container, apps)
  }
}
