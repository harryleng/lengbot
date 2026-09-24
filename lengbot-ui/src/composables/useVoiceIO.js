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

  // 流式代码块跳过状态：true 表示正处于 ``` 围栏内部，这段内容不播报
  let fenceSkipping = false

  // 句末标点（硬边界）：遇到就切一句
  const SENTENCE_END = /[。！？!?；;\n]/
  // 句内标点（软边界）：仅当句子过长时兜底切分，避免一口气念太长
  const SOFT_BREAK = /[，,、：:]/
  const SOFT_LIMIT = 60 // 累计到该长度仍无句末标点时，启用软边界切分
  const MIN_SENTENCE_LEN = 16 // 软边界切分的最小片段长度，避免切得太碎

  // 找到切分点下标（含该标点本身）；-1 表示暂无可切分的完整句
  function nextBoundaryIndex(str) {
    const hard = str.match(SENTENCE_END)
    if (hard) return hard.index
    if (str.length < SOFT_LIMIT) return -1
    // 长句兜底：取第一个满足最小长度的软边界，保证片段语义相对完整
    const re = new RegExp(SOFT_BREAK.source, 'g')
    let m
    while ((m = re.exec(str))) {
      if (m.index >= MIN_SENTENCE_LEN) return m.index
    }
    return -1
  }

  /**
   * 把 LLM 输出的 Markdown 文本转成「像人说话」的播报文本。
   *
   * 设计目标：只念文字，不念符号。LLM 回复天然带 Markdown 标记、表格竖线、
   * 列表符号、URL、emoji、代码块等，这些内容直接喂 TTS 会被逐字念出来
   * （"井号"、"星号"、"竖线"、"h-t-t-p-s 冒号斜杠斜杠"），非常不自然。
   *
   * 清洗顺序有讲究：先去大块结构（代码块/HTML/链接），再去行内装饰符号，
   * 最后才做单位口语化——否则百分号等会先被当成装饰符号删掉。
   *
   * @param {string} content 原始文本（可为空）
   * @returns {string} 可播报的纯文本；若清洗后无实际内容则返回空串
   */
  function toSpokenText(content) {
    if (!content) return ''
    let s = String(content)

    // 1) HTML 标签
    s = s.replace(/<[^>]+>/g, ' ')

    // 2) 围栏代码块（已闭合的整块去掉）
    s = s.replace(/```[\s\S]*?```/g, ' ')
    // 非流式场景可能只拿到开头的 ``` 而未闭合 → 其后内容一律不播报
    const fenceIdx = s.indexOf('```')
    if (fenceIdx >= 0) s = s.slice(0, fenceIdx)

    // 3) 行内代码：去反引号，保留内容
    s = s.replace(/`([^`]*)`/g, '$1')

    // 4) 图片 / 链接：只保留可读文字，丢弃 URL
    s = s.replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    s = s.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')

    // 5) 裸 URL / www 地址：念出来极不自然，统一读成「链接」。
    //    两侧用中文逗号而非空格——后续会删除中文之间的多余空格，
    //    用空格会导致「是链接请注意」粘连，而逗号能保留停顿。
    s = s.replace(/https?:\/\/[^\s，。；！？)）"'》]+/g, '，链接，')
    s = s.replace(/\bwww\.[^\s，。；！？)）"'》]+/g, '，链接，')

    // 6) 表格分隔行、Markdown 分隔线（--- / *** / ___）
    s = s.replace(/^\s*\|?[\s:|-]{3,}\|?\s*$/gm, ' ')
    s = s.replace(/^\s*([-*_])\s*\1\s*\1[^\n]*$/gm, ' ')
    // 表格竖线 → 中文逗号：让各列之间保留停顿，避免「方案优点缺点」粘连
    s = s.replace(/\|/g, '，')

    // 7) 行首标题符号与列表符号
    s = s.replace(/^\s{0,3}#{1,6}\s*/gm, '')
    s = s.replace(/^\s*([-*+]|\d+[.)])\s+/gm, '')

    // 8) 剩余 Markdown 装饰符号 → 空格（用空格而非删除，避免中英文粘连）
    //    注意：~ 与 & 不在其中，留给下一步做口语化替换
    s = s.replace(/[#*_`>[\]()|{}<>!$^=+@\\]/g, ' ')

    // 9) emoji 与箭头符号（U+2190-21FF 箭头、U+2B00-2BFF 杂项符号等）
    s = s.replace(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{2190}-\u{21FF}\u{2B00}-\u{2BFF}]/gu, ' ')
    // 变体选择符（emoji 修饰符）：属组合字符，不能放进字符类（会被读成独立的怪音），单独清理
    s = s.replace(/\u{FE0F}/gu, '')

    // 10) 数字与单位口语化（须在装饰符号清理之后，且顺序不能颠倒）
    s = s.replace(/(\d+(?:\.\d+)?)\s*%/g, '百分之$1') // 50% → 百分之50
    s = s.replace(/%/g, '百分号') // 孤立百分号
    s = s.replace(/(\d+(?:\.\d+)?)\s*℃/g, '$1摄氏度')
    s = s.replace(/(\d+(?:\.\d+)?)\s*℉/g, '$1华氏度')
    s = s.replace(/[~～≈]/g, '到') // 1~5 → 1到5
    s = s.replace(/≥/g, '大于等于')
    s = s.replace(/≤/g, '小于等于')
    s = s.replace(/±/g, '正负')
    s = s.replace(/×/g, '乘以')
    s = s.replace(/&/g, '和')

    // 11) 空白压缩，并删除中文字符/中文标点之间因符号替换产生的多余空格
    //     （如「， 方案」→「，方案」、「是 ，链接， 请注意」→「是，链接，请注意」）
    s = s.replace(/\s+/g, ' ')
    // 中文标点两侧的空格一律删除（符号替换、表格转列、URL 替换都会产生这类空格），
    // 只处理中文标点，不动英文空格，避免把「Redis Stream」压成「RedisStream」
    s = s.replace(/\s*([，。！？；：、])\s*/g, '$1')
    // 中文字符之间的多余空格删除（Markdown 加粗等符号被替换成空格后的残留）
    s = s.replace(/([\u4e00-\u9fa5])\s+([\u4e00-\u9fa5])/g, '$1$2')
    // 12) 重复标点压缩（删除空格后可能新产生「，，」，需要再压一次）
    s = s.replace(/([。！？!?；;，,、])\1+/g, '$1')
    // 13) 去掉首尾孤立标点，避免开场/收尾念出无意义的停顿
    s = s.replace(/^[，、；：\s]+/, '').replace(/[，、；：\s]+$/, '')
    s = s.trim()

    // 13) 纯符号 / 无实质内容的片段不播报（省一次 TTS 请求，也避免念读标点）
    if (!s) return ''
    if (!/[0-9A-Za-z\u4e00-\u9fa5]/.test(s)) return ''
    return s
  }

  /**
   * 流式代码块过滤：跨 chunk 维护围栏状态，围栏内的内容不进播报缓冲。
   * LLM 输出代码是一段一段的，单个 chunk 里往往只有开头的 ``` 而没有闭合，
   * 因此必须用状态机跨 chunk 记住"当前是否在代码块里"。
   */
  function stripFenceStream(chunk) {
    if (!chunk) return ''
    let out = ''
    let rest = chunk
    while (rest) {
      if (!fenceSkipping) {
        const i = rest.indexOf('```')
        if (i < 0) {
          out += rest
          break
        }
        out += rest.slice(0, i) // 围栏之前的正文照常播报
        fenceSkipping = true
        rest = rest.slice(i + 3)
      } else {
        const i = rest.indexOf('```')
        if (i < 0) break // 仍在代码块内，整段丢弃
        fenceSkipping = false
        rest = rest.slice(i + 3)
        // 代码块结束：仅当已有正文且末尾没有收尾标点时才补句号，
        // 避免「代码如下：」+ 代码块 变成「代码如下： 。」这种孤立句号
        const tail = out.trimEnd()
        if (tail && !/[。！？!?；;：:，,、\n]$/.test(tail)) out += '。'
      }
    }
    return out
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

  // 统一分发：按当前模式选择后端或浏览器。
  // 清洗放在这里而不是各分支内部，保证后端 TTS 与浏览器 TTS 两条路径口径一致
  // （历史上只有浏览器兜底路径做了清洗，后端路径会把 Markdown 原文直接喂给 TTS）。
  function utterSentence(text) {
    const spoken = toSpokenText(text)
    if (!spoken) return // 纯符号片段直接丢弃，不浪费一次 TTS 请求
    if (useBackend()) {
      enqueueBackendSentence(spoken)
    } else {
      enqueueBroadcastUtter(spoken)
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
    fenceSkipping = false
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
    // 先过滤代码块再入 buffer，避免 ``` 及其内容被当成正文逐句念出来
    if (appended) broadcastBuffer += stripFenceStream(appended)
    flushBufferedSentences()
  }

  /** 流式结束：把句末残留（无句末标点的尾巴）补读出来。 */
  function flushStreamBroadcast() {
    if (!broadcastEnabled) return
    // 若流结束时仍停在代码块内，说明残留 buffer 是代码而非正文，直接丢弃
    if (fenceSkipping) {
      broadcastBuffer = ''
      fenceSkipping = false
      return
    }
    // 末尾补句号后走正常切分：既保证最后一段能被完整播报，
    // 也让超长残留按软边界拆成多句，而不是一口气念一大段
    broadcastBuffer = broadcastBuffer.replace(/\s+$/, '')
    if (!broadcastBuffer) return
    if (!SENTENCE_END.test(broadcastBuffer.slice(-1))) broadcastBuffer += '。'
    flushBufferedSentences()
  }

  /** 立即取消播报（用户点停止 / 静音 / 切换 agent）。 */
  function stopStreamBroadcast() {
    broadcastBuffer = ''
    broadcastEnabled = false
    broadcastPending = 0
    broadcastActive = false
    fenceSkipping = false
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
  /**
   * 兼容旧调用方（speakMessage / speakText 等）的文本清洗入口。
   * 实际清洗逻辑统一收敛到 {@link toSpokenText}，避免出现两套清洗标准。
   */
  function messagePlainText(content) {
    return toSpokenText(content)
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
