import { ref, reactive, nextTick } from 'vue'
import { getSessionTitle, updateSessionTitle } from '../../api/chatSession'

/**
 * 会话顶部栏标题编辑、文件抽屉、标题轮询、附件预览路由
 * @param {object} deps
 * @param {import('vue').ComputedRef<string|null>} deps.sessionId
 * @param {(att: object) => void} deps.openAttachmentPreview
 */
export function useChatSessionChrome({
  sessionId,
  openAttachmentPreview,
  attachmentPreviewOpen,
  attachmentPreviewAtt,
}) {
  const sessionTitle = ref('')
  const titleEditing = ref(false)
  const titleEditValue = ref('')
  const titleInputRef = ref(null)
  const fileDrawerOpen = ref(false)
  const fileDrawerLoading = ref(false)
  const fileDrawerLoadedOnce = ref(false)
  const fileStats = reactive({ total: 0, userUpload: 0, aiGenerated: 0 })
  const fileTreeRefreshTick = ref(0)
  const sessionFilePreviewOpen = ref(false)
  const sessionFilePreviewTarget = ref(null)
  const sessionFileTreeRef = ref(null)

  /** 轮询等待会话标题生成完成（轻量接口，跳过缓存） */
  let pollTitleTimer = null

  function startTitleEdit() {
    titleEditValue.value = sessionTitle.value || '新对话'
    titleEditing.value = true
    nextTick(() => {
      const el = titleInputRef.value
      if (el) {
        const input = el.$el ? el.$el.querySelector('input') : el
        if (input) {
          input.focus()
          input.select()
        }
      }
    })
  }

  async function confirmTitleEdit() {
    titleEditing.value = false
    const newTitle = titleEditValue.value.trim()
    if (!newTitle || newTitle === sessionTitle.value) return
    sessionTitle.value = newTitle
    if (sessionId.value) {
      try {
        await updateSessionTitle(sessionId.value, newTitle)
        window.dispatchEvent(new CustomEvent('session-title-updated'))
      } catch {
        /* ignore */
      }
    }
  }

  function cancelTitleEdit() {
    titleEditing.value = false
    titleEditValue.value = ''
  }

  function tooltipPopupContainer() {
    return document.body
  }

  function openFileDrawer() {
    // 首次打开提前置 loading，避免抽屉展开动画期间闪烁空状态
    if (!fileDrawerLoadedOnce.value) {
      fileDrawerLoading.value = true
    }
    fileDrawerOpen.value = true
  }

  async function onFileDrawerOpened(open) {
    if (!open || !sessionId.value) return
    await loadSessionFiles()
  }

  function refreshSessionFiles() {
    loadSessionFiles()
  }

  async function loadSessionFiles() {
    if (!sessionId.value) return
    fileDrawerLoading.value = true
    try {
      // 触发树组件刷新；统计由 onFileTreeRefreshed 回写
      fileTreeRefreshTick.value++
      fileDrawerLoadedOnce.value = true
    } finally {
      fileDrawerLoading.value = false
    }
  }

  function onFileTreeRefreshed(stats) {
    if (stats) {
      fileStats.total = stats.total || 0
      fileStats.userUpload = stats.userUpload || 0
      fileStats.aiGenerated = stats.aiGenerated || 0
    }
  }

  function resolveAttachmentAsSessionFile(att) {
    if (!att?.objectKey || !sessionId.value) return null
    const root = `sessions/${sessionId.value}/`
    if (!att.objectKey.startsWith(root)) return null
    const path = att.objectKey.slice(root.length)
    if (!path) return null
    return {
      path,
      fileName: att.fileName,
      name: att.fileName || path.split('/').pop(),
    }
  }

  /** 对话附件预览：已落盘的文档走会话文件预览 API，与会话侧栏一致 */
  function onAttachmentPreview(att) {
    if (att?.type === 'document') {
      const sessionFile = resolveAttachmentAsSessionFile(att)
      if (sessionFile) {
        openSessionFilePreviewModal(sessionFile)
        return
      }
    }
    openAttachmentPreview(att)
  }

  function openSessionFilePreviewModal(file) {
    sessionFilePreviewTarget.value = file
    sessionFilePreviewOpen.value = true
  }

  function openSessionFilePreview(att) {
    attachmentPreviewAtt.value = att
    attachmentPreviewOpen.value = true
  }

  function pollSessionTitle(sid) {
    if (!sid) return
    // 已有自定义标题时无需轮询
    if (sessionTitle.value && sessionTitle.value !== '新对话') return
    if (pollTitleTimer) clearInterval(pollTitleTimer)
    let count = 0
    const maxRetries = 15
    const interval = 2000
    pollTitleTimer = setInterval(async () => {
      // 轮询期间用户可能手动改了标题
      if (sessionTitle.value && sessionTitle.value !== '新对话') {
        clearInterval(pollTitleTimer)
        pollTitleTimer = null
        return
      }
      try {
        const res = await getSessionTitle(sid)
        const title = res.data
        if (title && title !== '新对话') {
          clearInterval(pollTitleTimer)
          pollTitleTimer = null
          sessionTitle.value = title
          window.dispatchEvent(new CustomEvent('session-title-updated'))
          return
        }
      } catch {
        // ignore
      }
      count++
      if (count >= maxRetries) {
        clearInterval(pollTitleTimer)
        pollTitleTimer = null
      }
    }, interval)
  }

  function cleanupPollTitleTimer() {
    if (pollTitleTimer) {
      clearInterval(pollTitleTimer)
      pollTitleTimer = null
    }
  }

  return {
    sessionTitle,
    titleEditing,
    titleEditValue,
    titleInputRef,
    fileDrawerOpen,
    fileDrawerLoading,
    fileDrawerLoadedOnce,
    fileStats,
    fileTreeRefreshTick,
    sessionFilePreviewOpen,
    sessionFilePreviewTarget,
    sessionFileTreeRef,
    startTitleEdit,
    confirmTitleEdit,
    cancelTitleEdit,
    tooltipPopupContainer,
    openFileDrawer,
    onFileDrawerOpened,
    refreshSessionFiles,
    loadSessionFiles,
    onFileTreeRefreshed,
    resolveAttachmentAsSessionFile,
    onAttachmentPreview,
    openSessionFilePreviewModal,
    openSessionFilePreview,
    pollSessionTitle,
    cleanupPollTitleTimer,
  }
}
