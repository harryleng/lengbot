import {
  buildFullWorkflowPipelineEvents,
  buildWorkflowFailureEvents,
  buildWorkflowRetryEvents,
  buildWorkflowNodeSample,
  combineWorkflowNodeSamples,
  WORKFLOW_NODE_TYPE_OPTIONS,
} from './debugWorkflowNodeSamples'
import { getPresetById } from './debugPresets'
import { apiMessageToEditorJson, createDefaultApiMessage } from './debugMessageBuilder'
import {
  WORKFLOW_DEBUG_SCENARIOS,
  buildWorkflowDebugFixture,
  getWorkflowDebugScenarioOptions,
} from './workflowDebugFixtureBuilder'

export { WORKFLOW_NODE_TYPE_OPTIONS, buildWorkflowNodeSample, combineWorkflowNodeSamples }
export { WORKFLOW_DEBUG_SCENARIOS, buildWorkflowDebugFixture, getWorkflowDebugScenarioOptions }

/** 工作流专项样例 */
export const WORKFLOW_DEBUG_SAMPLES = [
  ...WORKFLOW_DEBUG_SCENARIOS.map((scenario) => ({
    id: scenario.id,
    label: scenario.invalid ? `${scenario.title}（非法）` : scenario.title,
    scenarioId: scenario.id,
    invalid: !!scenario.invalid,
  })),
  {
    id: 'legacy-full-pipeline',
    label: '旧版：完整链路（仅 workflowEvents）',
    builder: buildFullWorkflowPipelineEvents,
  },
  { id: 'workflow-steps', label: '基础步骤', presetId: 'workflow-steps' },
  { id: 'workflow-confirm', label: '待确认', presetId: 'workflow-confirm' },
  { id: 'workflow-failure', label: '节点失败', builder: buildWorkflowFailureEvents },
  { id: 'workflow-retry', label: '节点重试', builder: buildWorkflowRetryEvents },
  { id: 'llm-only', label: '单节点：LLM', builder: () => buildWorkflowNodeSample('llm') },
  { id: 'retrieval-only', label: '单节点：检索', builder: () => buildWorkflowNodeSample('retrieval') },
  { id: 'tool-only', label: '单节点：工具', builder: () => buildWorkflowNodeSample('tool') },
  { id: 'classifier-only', label: '单节点：分类', builder: () => buildWorkflowNodeSample('classifier') },
]

export function getWorkflowSampleOptions() {
  return WORKFLOW_DEBUG_SAMPLES.map((s) => ({ value: s.id, label: s.label }))
}

export function getWorkflowSampleMessage(sampleId) {
  const sample = WORKFLOW_DEBUG_SAMPLES.find((s) => s.id === sampleId)
  if (!sample) return null

  if (sample.presetId) {
    return getPresetById(sample.presetId)
  }

  if (sample.scenarioId) {
    return buildWorkflowDebugFixture(sample.scenarioId).payload.message
  }

  const events = sample.builder()
  const base = createDefaultApiMessage()
  return {
    ...base,
    content: '以下为工作流节点渲染预览，展开节点可查看 outputs / 返回信息。',
    metadata: {
      ...base.metadata,
      workflowEvents: events,
    },
  }
}

export function buildWorkflowMessageFromEvents(events, content) {
  const base = createDefaultApiMessage()
  return apiMessageToEditorJson({
    ...base,
    content: content || '工作流节点样式预览。',
    metadata: {
      ...base.metadata,
      workflowEvents: events,
    },
  })
}
