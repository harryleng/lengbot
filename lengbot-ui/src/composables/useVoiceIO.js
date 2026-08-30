import { nextTick, ref } from 'vue'
import { message } from 'ant-design-vue'

/**
 * Chat 页面语音 I/O composable
 * 管理语音识别（输入）和语音合成（TTS 输出）
 *
 * TTS 输出支持两种后端：
 *  - 后端 TTS（默认启用）：调用 /api/tts/synthesize 获取音频字节，用 <audio> 播放，
 *    不依赖浏览器自带的 Web Speech API，无头/无语音引擎浏览器也能发声。
 *  - 浏览器 TTS（兜底）：window.speechSynthesis，后端不可用或显式关闭时回退。
 *
 * 注意：voiceListening, speakingMsgKey 由外部创建并传入
 */
export function useVoiceIO({
  input,
  inputRef,
  chatCapabilities,
  autoResize,
  voiceListening,
  speakingMsgKey,
  // 后端 TTS 开关（ref<boolean>）与实例（useBackendTts() 返回值）。不传则全程走浏览器 TTS。
  useBackendTts,
  backendTts,
}) {
  let speechRecognition = null
  // speaking: 任意朗读进行中的全局状态（数字人面板据此驱动口型动画）
  const speaking = ref(false)

  const useBackend = () => useBackendTts?.value && !!backendTts

  // ===== 数字人流式播报（逐句增量 TTS）=====
  // 仅在数字人型 agent 对话时启用：LLM 流式输出过程中按句朗读，口型随文字实时开合。
  let broadcastEnabled = false // 当前是否为数字人播报会话
  let broadcastMuted = false // 静音（不朗读，但仍可恢复）
  let broadcastVoice = null // { voiceURI, rate, pitch }
  let broadcastBuffer = '' // 尚未成句的待朗读文本
  let broadcastLastLen = 0 // 上次已喂入流式正文的累计长度，避免重复喂词
  let broadcastActive = false // 是否有正在播放/排队的语音
  let broadcastPending = 0 // 已入队但未 onend 的 utterance 计数

  // 后端 TTS 顺序播放队列
  let backendQueue = []
  let backendPlaying = false
  let currentBackendAudio = null
  let currentBackendResolve = null

  // 找到第一个句末标点的下标（含该标点本身）
  function nextBoundaryIndex(str) {
    const m = str.match(/[。！？!?；;\n]/)
    return m ? m.index : -1
  }

  // ---- 浏览器 TTS：单句入队 ----
  function enqueueBroadcastUtter(text) {
    const plain = messagePlainText(text)
    if (!plain) return
    if (window.speechSynthesis.paused) {
      try {
        window.speechSynthesis.resume()
      } catch {
        /* ignore */
      }
    }
    const utter = new SpeechSynthesisUtterance(plain)
    utter.lang = 'zh-CN'
    utter.rate = broadcastVoice?.rate ?? 1
    utter.pitch = broadcastVoice?.pitch ?? 1
    if (broadcastVoice?.voiceURI) {
      const v = window.speechSynthesis.getVoices().find((x) => x.voiceURI === broadcastVoice.voiceURI)
      if (v) utter.voice = v
    }
    broadcastPending++
    const settle = () => {
      broadcastPending = Math.max(0, broadcastPending - 1)
      if (broadcastPending <= 0 && broadcastBuffer === '') {
        broadcastActive = false
        speaking.value = false
      }
    }
    utter.onend = settle
    utter.onerror = settle
    if (!broadcastActive) {
      broadcastActive = true
      speaking.value = true
    }
    window.speechSynthesis.speak(utter)
  }

  // ---- 后端 TTS：单句播放（返回 Promise，结束/出错/中止时 resolve）----
  function playBackendSentence(text, voiceOverride) {
    const voice = voiceOverride || broadcastVoice
    return new Promise((resolve) => {
      currentBackendResolve = resolve
      const finish = () => {
        if (currentBackendResolve === resolve) currentBackendResolve = null
        resolve()
      }
      backendTts
        .synthesize(text, mapVoice(voice))
        .then((audio) => {
          currentBackendAudio = audio
          audio.onended = finish
          audio.onerror = () => finish()
          const p = audio.play()
          if (p && p.catch) p.catch(() => finish())
        })
        .catch(() => {
          // 后端失败 → 浏览器兜底
          enqueueBroadcastUtter(text)
          finish()
        })
    })
  }

  function mapVoice(voice) {
    return {
      voice: voice?.voiceURI || undefined,
      rate: voice?.rate,
      pitch: voice?.pitch,
    }
  }

  // 后端队列：逐句顺序播放，speaking 在整个队列期间保持 true
  async function runBackendQueue() {
    if (backendPlaying) return
    backendPlaying = true
    speaking.value = true
    while (backendQueue.length) {
      const sentence = backendQueue.shift()
      // eslint-disable-next-line no-await-in-loop
      await playBackendSentence(sentence)
    }
    backendPlaying = false
    speaking.value = false
    broadcastActive = false
  }

  function enqueueBackendSentence(text) {
    backendQueue.push(text)
    runBackendQueue()
  }

  function stopBackend() {
    backendQueue = []
    backendPlaying = false
    try {
      currentBackendAudio?.pause()
    } catch {
      /* ignore */
    }
    currentBackendAudio = null
    if (currentBackendResolve) {
      currentBackendResolve()
      currentBackendResolve = null
    }
    speaking.value = false
  }

  // 统一分发：按当前模式选择后端或浏览器
  function utterSentence(text) {
    if (useBackend()) {
      enqueueBackendSentence(text)
    } else {
      enqueueBroadcastUtter(text)
    }
  }

  // 把 buffer 中已成句的片段逐句入队朗读
  function flushBufferedSentences() {
    let idx
    while ((idx = nextBoundaryIndex(broadcastBuffer)) >= 0) {
      const sentence = broadcastBuffer.slice(0, idx + 1)
      broadcastBuffer = broadcastBuffer.slice(idx + 1)
      utterSentence(sentence)
    }
  }

  /** 开启一轮数字人流式播报。voice 为 null 表示非数字人，禁用播报。 */
  function startStreamBroadcast(voice) {
    broadcastVoice = voice || null
    broadcastEnabled = !!voice
    broadcastBuffer = ''
    broadcastLastLen = 0
    broadcastPending = 0
    broadcastActive = false
    // 重置后端播放状态
    backendQueue = []
    backendPlaying = false
    currentBackendAudio = null
    currentBackendResolve = null
  }

  /** 流式正文更新时调用：增量喂入新文本，成句即朗读。 */
  function feedStreamingText(fullText) {
    if (!broadcastEnabled || broadcastMuted) {
      broadcastLastLen = fullText ? fullText.length : 0
      return
    }
    const text = fullText || ''
    const appended = text.slice(broadcastLastLen)
    broadcastLastLen = text.length
    if (appended) broadcastBuffer += appended
    flushBufferedSentences()
  }

  /** 流式结束：把句末残留（无句末标点的尾巴）补读出来。 */
  function flushStreamBroadcast() {
    if (!broadcastEnabled) return
    if (broadcastBuffer) {
      utterSentence(broadcastBuffer)
      broadcastBuffer = ''
    }
  }

  /** 立即取消播报（用户点停止 / 静音 / 切换 agent）。 */
  function stopStreamBroadcast() {
    broadcastBuffer = ''
    broadcastEnabled = false
    broadcastPending = 0
    broadcastActive = false
    try {
      window.speechSynthesis?.cancel()
    } catch {
      /* ignore */
    }
    stopBackend()
    speaking.value = false
  }

  /** 切换静音：true=静音并取消当前语音；false=恢复（后续流式文本会继续朗读）。 */
  function setBroadcastMuted(muted) {
    broadcastMuted = !!muted
    if (broadcastMuted) {
      broadcastBuffer = ''
      broadcastActive = false
      broadcastPending = 0
      try {
        window.speechSynthesis?.cancel()
      } catch {
        /* ignore */
      }
      stopBackend()
      speaking.value = false
    }
  }

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

  // 浏览器 TTS 朗读（speakText 的兜底实现）
  function speakTextBrowser(text, opts = {}) {
    if (!window.speechSynthesis) {
      message.warning('当前浏览器不支持语音朗读')
      opts.onEnd?.()
      return
    }
    window.speechSynthesis.cancel()
    const utter = new SpeechSynthesisUtterance(text)
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
    if (useBackend()) {
      speaking.value = true
      opts.onStart?.()
      backendTts
        .synthesize(plain, mapVoice(opts.voice))
        .then((audio) => {
          audio.onended = () => {
            speaking.value = false
            opts.onEnd?.()
          }
          audio.onerror = () => {
            speaking.value = false
            opts.onEnd?.()
          }
          const p = audio.play()
          if (p && p.catch) {
            p.catch(() => {
              // 播放失败 → 浏览器兜底
              speakTextBrowser(plain, opts)
            })
          }
        })
        .catch(() => {
          // 合成失败 → 浏览器兜底
          speakTextBrowser(plain, opts)
        })
      return
    }
    speakTextBrowser(plain, opts)
  }

  function cleanup() {
    window.speechSynthesis?.cancel()
    stopBackend()
    stopVoiceInput()
  }

  return {
    toggleVoiceInput,
    stopVoiceInput,
    speakMessage,
    speakText,
    speaking,
    messagePlainText,
    startStreamBroadcast,
    feedStreamingText,
    flushStreamBroadcast,
    stopStreamBroadcast,
    setBroadcastMuted,
    cleanup,
  }
}
