import request from '../utils/request'

/** 查询受管音色列表（带筛选：provider/locale/gender/favorite/group/keyword） */
export function getTtsVoices(params) {
  return request.get('/tts/voices', { params })
}

/** 从云端 Provider 同步音色到本地缓存，返回 { synced } */
export function syncTtsVoices(provider) {
  return request.post('/tts/voices/sync', null, { params: { provider } })
}

/** 局部更新某音色元数据（favorite / voiceGroup / remark） */
export function updateTtsVoiceMeta(voiceName, body) {
  return request.patch(`/tts/voices/${encodeURIComponent(voiceName)}`, body)
}

/** 列出所有已使用的分组 */
export function getTtsVoiceGroups() {
  return request.get('/tts/voices/groups')
}
