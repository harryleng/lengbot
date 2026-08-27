import { nextTick } from 'vue'
import { message } from 'ant-design-vue'

/**
 * Chat 页面语音 I/O composable
 * 管理语音识别（输入）和语音合成（TTS 输出）
 *
 * 注意：voiceListening, speakingMsgKey 由外部创建并传入
 */
export function useVoiceIO({ input, inputRef, chatCapabilities, autoResize, voiceListening, speakingMsgKey }) {
  let speechRecognition = null
  // speaking: 任意朗读进行中的全局状态（数字人面板据此驱动口型动画）
  const speaking = ref(false)

  // ===== 语音识别 =====
  function toggleVoiceInput() {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition
    if (!SR) {
      message.warning('当前浏览器不支持语音识别，请使用 Chrome/Edge')
      return
    }
    if (voiceListening.value && speechRecognition) {
      stopVoiceInput()
      return
    }
    const voiceInputBase = input.value
    speechRecognition = new SR()
    speechRecognition.lang = 'zh-CN'
    speechRecognition.interimResults = true
    speechRecognition.continuous = true
    speechRecognition.onstart = () => {
      voiceListening.value = true
    }
    speechRecognition.onend = () => {
      stopVoiceInput()
    }
    speechRecognition.onerror = () => {
      stopVoiceInput()
    }
    speechRecognition.onresult = (event) => {
      let finalText = ''
      let interimText = ''
      for (let i = 0; i < event.results.length; i++) {
        const part = event.results[i][0].transcript
        if (event.results[i].isFinal) {
          finalText += part
        } else {
          interimText += part
        }
      }
      const merged = `${voiceInputBase}${voiceInputBase && finalText ? ' ' : ''}${finalText}${interimText}`
      input.value = merged.trim() ? merged : voiceInputBase
      autoResize()
      nextTick(() => inputRef.value?.focus())
    }
    speechRecognition.start()
  }

  function stopVoiceInput() {
    voiceListening.value = false
    try {
      speechRecognition?.stop()
    } catch {
      /* ignore */
    }
  }

  // ===== TTS 语音合成 =====
  function messagePlainText(content) {
    if (!content) return ''
    return content
      .replace(/```[\s\S]*?```/g, ' ')
      .replace(/`([^`]+)`/g, '$1')
      .replace(/[#*_~>[\]()!]/g, '')
      .replace(/\s+/g, ' ')
      .trim()
  }

  function speakMessage(msg, index) {
    const text = messagePlainText(msg.content)
    if (!text) return
    if (!window.speechSynthesis) {
      message.warning('当前浏览器不支持语音朗读')
      return
    }
    if (speakingMsgKey.value === index) {
      window.speechSynthesis.cancel()
      speakingMsgKey.value = null
      speaking.value = false
      return
    }
    window.speechSynthesis.cancel()
    const utter = new SpeechSynthesisUtterance(text)
    utter.lang = 'zh-CN'
    utter.rate = 1
    speakingMsgKey.value = index
    speaking.value = true
    utter.onend = () => {
      speakingMsgKey.value = null
      speaking.value = false
    }
    utter.onerror = () => {
      speakingMsgKey.value = null
      speaking.value = false
    }
    window.speechSynthesis.speak(utter)
  }

  /**
   * 直接朗读一段纯文本（供数字人自动播报等场景使用）。
   * @param {string} text 纯文本
   * @param {object} [opts]
   * @param {object} [opts.voice] 音色配置 { voiceURI, rate, pitch }
   * @param {() => void} [opts.onStart]
   * @param {() => void} [opts.onEnd]
   */
  function speakText(text, opts = {}) {
    const plain = messagePlainText(text)
    if (!plain) return
    if (!window.speechSynthesis) {
      message.warning('当前浏览器不支持语音朗读')
      opts.onEnd?.()
      return
    }
    window.speechSynthesis.cancel()
    const utter = new SpeechSynthesisUtterance(plain)
    utter.lang = 'zh-CN'
    utter.rate = opts.voice?.rate ?? 1
    utter.pitch = opts.voice?.pitch ?? 1
    if (opts.voice?.voiceURI) {
      const v = window.speechSynthesis.getVoices().find((x) => x.voiceURI === opts.voice.voiceURI)
      if (v) utter.voice = v
    }
    speaking.value = true
    opts.onStart?.()
    const finish = () => {
      speaking.value = false
      opts.onEnd?.()
    }
    utter.onend = finish
    utter.onerror = finish
    window.speechSynthesis.speak(utter)
  }

  function cleanup() {
    window.speechSynthesis?.cancel()
    stopVoiceInput()
  }

  return {
    toggleVoiceInput,
    stopVoiceInput,
    speakMessage,
    speakText,
    speaking,
    messagePlainText,
    cleanup,
  }
}
