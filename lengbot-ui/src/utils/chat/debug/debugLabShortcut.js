import { message } from 'ant-design-vue'

/** 连按 D 键 5 次打开 Debug Lab（1.5 秒内，输入框内不触发） */
export const DEBUG_LAB_SHORTCUT = '连按 D 键 5 次'
const DEBUG_PRESS_COUNT = 5
const DEBUG_PRESS_WINDOW_MS = 1500

const pressTimestamps = []

function isTypingTarget(target) {
  if (!target) return false
  const tag = target.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  return !!target.isContentEditable
}

/** 在新窗口打开 Debug Lab */
export function openDebugLabInNewWindow() {
  const base = import.meta.env.BASE_URL || '/'
  const path = `${base.replace(/\/$/, '')}/debug`
  window.open(path, '_blank', 'noopener,noreferrer')
  message.info('已在新窗口打开 Debug Lab')
}

/**
 * 全局快捷键：任意页面连按 D ×5 → 新窗口打开 /debug
 * @param {KeyboardEvent} e
 * @returns {boolean}
 */
export function handleDebugLabShortcut(e) {
  if (isTypingTarget(e.target)) return false
  if (e.ctrlKey || e.altKey || e.metaKey) return false
  if (e.key !== 'D' && e.key !== 'd') {
    pressTimestamps.length = 0
    return false
  }

  const now = Date.now()
  while (pressTimestamps.length && now - pressTimestamps[0] > DEBUG_PRESS_WINDOW_MS) {
    pressTimestamps.shift()
  }
  pressTimestamps.push(now)

  if (pressTimestamps.length >= DEBUG_PRESS_COUNT) {
    pressTimestamps.length = 0
    e.preventDefault()
    openDebugLabInNewWindow()
    return true
  }
  return false
}
