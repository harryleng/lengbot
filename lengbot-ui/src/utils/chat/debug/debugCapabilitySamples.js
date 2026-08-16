import { getPresetById } from './debugPresets'

/** 能力块专项样例（Skill / SubAgent） */
export const CAPABILITY_DEBUG_SAMPLES = [
  { id: 'skill-active', label: 'Skill 启用', presetId: 'skill-active' },
  { id: 'subagent-delegation', label: 'SubAgent 委派', presetId: 'subagent-delegation' },
  { id: 'subagent-batch-stream', label: 'SubAgent 批次流式委派', presetId: 'subagent-batch-stream' },
  { id: 'subagent-error', label: 'SubAgent 失败', presetId: 'subagent-error' },
  { id: 'reasoning', label: '深度思考', presetId: 'reasoning' },
  { id: 'rag-references', label: '参考文献', presetId: 'rag-references' },
]

export function getCapabilitySampleMessage(sampleId) {
  const sample = CAPABILITY_DEBUG_SAMPLES.find((s) => s.id === sampleId)
  if (!sample) return null
  return getPresetById(sample.presetId)
}

export function getCapabilitySampleOptions() {
  return CAPABILITY_DEBUG_SAMPLES.map((s) => ({ value: s.id, label: s.label }))
}
