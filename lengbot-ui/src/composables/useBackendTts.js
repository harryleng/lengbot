import { ref } from 'vue'

/**
 * 后端 TTS 调用封装。
 * 数字人播报 / 消息朗读统一通过后端 /api/tts/synthesize 合成音频，
 * 不再依赖浏览器自带的 Web Speech API（speechSynthesis），
 * 因此在无语音引擎的浏览器 / 无头环境下也能正常发声。
 */

const BASE = '/api/tts'

export function useBackendTts() {
  const lastError = ref(null)

  /** 列出服务端支持的音色（用于音色下拉） */
  async function listVoices() {
    const r = await fetch(`${BASE}/voices`)
    if (!r.ok) throw new Error(`加载音色失败: ${r.status}`)
    const data = await r.json()
    // Result.ok(list) 结构
    return data?.data || data || []
  }

  /** 返回当前生效的 Provider 及全部可选 Provider：{ active, available } */
  async function provider() {
    const r = await fetch(`${BASE}/provider`)
    if (!r.ok) throw new Error(`查询引擎失败: ${r.status}`)
    const data = await r.json()
    const d = data?.data || {}
    return { active: d.active || 'unknown', available: d.available || [] }
  }

  /**
   * 运行时切换当前生效的 Provider（无需刷新/重启）。
   * @param {string} name 目标 Provider 名称（如 mock / edge-tts）
   * @returns {Promise<{active:string, available:string[]}>}
   */
  async function setProvider(name) {
    const r = await fetch(`${BASE}/provider`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: name }),
    })
    if (!r.ok) {
      let msg = `切换引擎失败: ${r.status}`
      try {
        const data = await r.json()
        if (data?.message) msg = data.message
      } catch (e) {
        // ignore
      }
      throw new Error(msg)
    }
    const data = await r.json()
    const d = data?.data || {}
    return { active: d.active || name, available: d.available || [] }
  }

  /**
   * 合成并播放一段文本。
   * @param {string} text 文本
   * @param {object} [opts]
   * @param {string} [opts.voice] 音色名（如 zh-CN-XiaoxiaoNeural）
   * @param {number|string} [opts.rate] 语速
   * @param {number|string} [opts.pitch] 音调
   * @returns {Promise<HTMLAudioElement>} 已开始播放的 audio 元素
   */
  async function synthesize(text, opts = {}) {
    const body = {
      text,
      voice: opts.voice || undefined,
      rate: opts.rate,
      pitch: opts.pitch,
      format: opts.format,
      provider: opts.provider || undefined,
    }
    const r = await fetch(`${BASE}/synthesize`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!r.ok) {
      throw new Error(`TTS 合成失败: ${r.status}`)
    }
    const blob = await r.blob()
    const url = URL.createObjectURL(blob)
    const audio = new Audio(url)
    // 播放结束后释放对象 URL
    const cleanup = () => URL.revokeObjectURL(url)
    audio.addEventListener('ended', cleanup, { once: true })
    audio.addEventListener('error', cleanup, { once: true })
    return audio
  }

  /** 全引擎连通性自检：{ active, providers: { name: { available, detail } } } */
  async function health() {
    const r = await fetch(`${BASE}/health`)
    if (!r.ok) throw new Error(`自检失败: ${r.status}`)
    const data = await r.json()
    return data?.data || {}
  }

  return { listVoices, provider, setProvider, health, synthesize, lastError }
}
