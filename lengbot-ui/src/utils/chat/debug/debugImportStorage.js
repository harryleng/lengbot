export const CHAT_DEBUG_IMPORT_KEY = 'lengbot-chat-debug-import'

export function saveDebugImportPayload(payload) {
  try {
    sessionStorage.setItem(CHAT_DEBUG_IMPORT_KEY, JSON.stringify(payload))
  } catch {
    // ignore quota errors
  }
}

export function consumeDebugImportPayload() {
  try {
    const raw = sessionStorage.getItem(CHAT_DEBUG_IMPORT_KEY)
    if (!raw) return null
    sessionStorage.removeItem(CHAT_DEBUG_IMPORT_KEY)
    return JSON.parse(raw)
  } catch {
    sessionStorage.removeItem(CHAT_DEBUG_IMPORT_KEY)
    return null
  }
}

export function peekDebugImportPayload() {
  try {
    const raw = sessionStorage.getItem(CHAT_DEBUG_IMPORT_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}
