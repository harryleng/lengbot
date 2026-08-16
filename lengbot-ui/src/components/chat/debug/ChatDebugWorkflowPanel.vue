<template>
  <div class="debug-workflow-panel">
    <div class="debug-panel-toolbar">
      <a-select
        v-model:value="selectedScenario"
        :options="scenarioOptions"
        style="width: 220px"
        @change="loadScenario"
      />
      <a-button type="primary" @click="$emit('parse')">解析预览</a-button>
      <a-button @click="handleFormat">格式化 JSON</a-button>
    </div>

    <div class="debug-scenario-grid">
      <button
        v-for="scenario in scenarios"
        :key="scenario.id"
        type="button"
        class="debug-scenario-card"
        :class="{ active: scenario.id === selectedScenario, invalid: scenario.invalid }"
        @click="loadScenario(scenario.id)"
      >
        <span class="scenario-title">{{ scenario.title }}</span>
        <span class="scenario-desc">{{ scenario.description }}</span>
        <span class="scenario-tags">
          <span v-for="tag in scenario.tags" :key="tag" class="scenario-tag">{{ tag }}</span>
        </span>
      </button>
    </div>

    <a-alert
      v-if="validationSummary"
      :type="validationSummary.type"
      :message="validationSummary.message"
      show-icon
      class="debug-validation-alert"
    />

    <a-alert
      v-if="parseError"
      type="error"
      :message="parseError"
      show-icon
      closable
      class="debug-parse-error"
      @close="parseError = ''"
    />

    <a-tabs v-model:active-key="activeTab" size="small" class="debug-workflow-tabs">
      <a-tab-pane key="json" tab="消息 JSON">
        <div class="debug-editor-label">工作流消息 JSON（content + metadata.workflowEvents）</div>
        <a-textarea v-model:value="localJson" :rows="16" class="debug-json-textarea" @change="onEdit" />
      </a-tab-pane>

      <a-tab-pane key="graph" tab="图预览">
        <div class="debug-replay-toolbar">
          <a-button size="small" type="primary" :disabled="replayPlaying" @click="playReplay">播放</a-button>
          <a-button size="small" :disabled="!replayPlaying" @click="pauseReplay">暂停</a-button>
          <a-button size="small" @click="stepReplay(-1)">上一步</a-button>
          <a-button size="small" @click="stepReplay(1)">下一步</a-button>
          <a-button size="small" @click="showFullReplay">显示全部</a-button>
          <a-select v-model:value="replaySpeed" :options="replaySpeedOptions" size="small" style="width: 86px" />
          <span class="debug-replay-progress">{{ replayProgressText }}</span>
        </div>
        <div v-if="groupNodes.length" class="debug-group-toolbar">
          <span class="debug-group-label">父子节点视图</span>
          <a-segmented v-model:value="groupViewMode" :options="groupViewOptions" size="small" />
          <span class="debug-group-hint">仅改变 Debug 画布投影，校验仍使用完整图。</span>
        </div>
        <div class="debug-graph-preview">
          <WorkflowViewerCanvas
            v-if="currentFixture"
            flow-id="debug-workflow-preview"
            :nodes="graphNodes"
            :edges="graphEdges"
            :node-states="nodeStates"
            :highlighted-edge-ids="highlightedEdgeIds"
            :selected-node-id="selectedNodeId"
            :show-minimap="false"
            @node-click="selectedNodeId = $event"
            @pane-click="selectedNodeId = null"
          />
          <div v-else class="debug-preview-empty">旧版组合样例没有图数据，请选择上方场景卡片。</div>
        </div>
      </a-tab-pane>

      <a-tab-pane key="trace" tab="轨迹">
        <div class="debug-replay-toolbar">
          <a-button size="small" type="primary" :disabled="replayPlaying" @click="playReplay">播放</a-button>
          <a-button size="small" :disabled="!replayPlaying" @click="pauseReplay">暂停</a-button>
          <a-button size="small" @click="stepReplay(-1)">上一步</a-button>
          <a-button size="small" @click="stepReplay(1)">下一步</a-button>
          <a-button size="small" @click="showFullReplay">显示全部</a-button>
          <span class="debug-replay-progress">{{ replayProgressText }}</span>
        </div>
        <div class="debug-trace-preview">
          <WorkflowTestTimeline
            :node-events="displayEvents"
            :active-node-id="selectedNodeId"
            @select-node="selectedNodeId = $event"
          />
        </div>
      </a-tab-pane>

      <a-tab-pane key="validation" tab="校验">
        <div class="debug-validation-list">
          <div v-if="!currentFixture" class="debug-preview-empty">旧版组合样例不包含工作流图，无法执行图校验。</div>
          <template v-else-if="currentValidation.issues.length">
            <div
              v-for="issue in currentValidation.issues"
              :key="`${issue.code}-${issue.nodeId || ''}-${issue.message}`"
              class="debug-validation-item"
              :class="issue.severity"
            >
              <span class="issue-severity">{{ issue.severity }}</span>
              <span>{{ issue.message }}</span>
            </div>
          </template>
          <div v-else class="debug-validation-pass">当前场景通过本地工作流图校验。</div>
        </div>
      </a-tab-pane>

      <a-tab-pane key="advanced" tab="高级拼装">
        <div class="debug-workflow-builder">
          <div class="debug-editor-label">新版高级拼装（生成合法图、父子节点与 workflowEvents）</div>
          <a-select
            v-model:value="selectedNodeTypes"
            mode="multiple"
            :options="nodeTypeOptions"
            placeholder="选择节点类型组合样式"
            style="width: 100%"
            :max-tag-count="4"
          />
          <div class="debug-builder-actions">
            <a-button type="primary" size="small" @click="buildAdvancedWorkflow">生成合法工作流</a-button>
            <a-button size="small" @click="selectedNodeTypes = ['retrieval', 'batch', 'loop', 'llm']">
              恢复推荐组合
            </a-button>
          </div>
        </div>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { apiMessageToEditorJson, editorJsonToApiMessage } from '@/utils/chat/debug/debugMessageBuilder'
import {
  WORKFLOW_DEBUG_SCENARIOS,
  WORKFLOW_ADVANCED_NODE_TYPE_OPTIONS,
  buildWorkflowDebugFixture,
  buildWorkflowDebugFixtureFromNodeTypes,
  getWorkflowDebugScenarioOptions,
} from '@/utils/chat/debug/workflowDebugFixtureBuilder'
import { validateWorkflowDebugGraph } from '@/utils/chat/debug/workflowDebugGraphValidator'
import WorkflowViewerCanvas from '@/views/workflow/components/WorkflowViewerCanvas.vue'
import WorkflowTestTimeline from '@/views/workflow/components/WorkflowTestTimeline.vue'
import { buildExecutedEdgeIds, eventsToNodeStates } from '@/views/workflow/workflowViewerAdapter'

const emit = defineEmits(['parse', 'update:modelValue'])

const scenarios = WORKFLOW_DEBUG_SCENARIOS
const scenarioOptions = getWorkflowDebugScenarioOptions()
const nodeTypeOptions = WORKFLOW_ADVANCED_NODE_TYPE_OPTIONS
const selectedScenario = ref(scenarioOptions[0]?.value || 'linear-basic')
const selectedNodeTypes = ref(['retrieval', 'batch', 'loop', 'llm'])
const localJson = ref('')
const parseError = ref('')
const activeTab = ref('json')
const currentFixture = ref(null)
const selectedNodeId = ref(null)
const replayIndex = ref(-1)
const replayPlaying = ref(false)
const replaySpeed = ref(1)
const groupViewMode = ref('expanded')
let replayTimer = null

const replaySpeedOptions = [
  { value: 0.5, label: '0.5x' },
  { value: 1, label: '1x' },
  { value: 2, label: '2x' },
  { value: 4, label: '4x' },
]

const groupViewOptions = [
  { value: 'expanded', label: '全部展开' },
  { value: 'collapsed', label: '全部收起' },
  { value: 'first-collapsed', label: '仅首个收起' },
]

const currentEvents = computed(() => {
  try {
    const msg = editorJsonToApiMessage(localJson.value)
    return msg.metadata?.workflowEvents || []
  } catch {
    return currentFixture.value?.events || []
  }
})

const displayEvents = computed(() => {
  if (replayIndex.value < 0) return currentEvents.value
  return currentEvents.value.slice(0, replayIndex.value + 1)
})

const groupNodes = computed(() => {
  const nodes = currentFixture.value?.graph?.nodes || []
  return nodes.filter((node) => node.type === 'batch' || node.type === 'loop')
})

const collapsedGroupIds = computed(() => {
  if (!groupNodes.value.length || groupViewMode.value === 'expanded') return new Set()
  if (groupViewMode.value === 'first-collapsed') return new Set([groupNodes.value[0].id])
  return new Set(groupNodes.value.map((node) => node.id))
})

const graphNodes = computed(() => {
  if (!currentFixture.value) return []
  const collapsedIds = collapsedGroupIds.value
  return currentFixture.value.graph.nodes
    .filter((node) => !node.parentNode || !collapsedIds.has(node.parentNode))
    .map((node) => {
      if (!collapsedIds.has(node.id)) return node
      return {
        ...node,
        data: {
          ...node.data,
          label: `${node.data?.label || node.id}（收起）`,
        },
      }
    })
})

const graphEdges = computed(() => {
  if (!currentFixture.value) return []
  const collapsedIds = collapsedGroupIds.value
  if (!collapsedIds.size) return currentFixture.value.graph.edges
  const parentByNodeId = new Map(
    currentFixture.value.graph.nodes.filter((node) => node.parentNode).map((node) => [node.id, node.parentNode])
  )
  const seen = new Set()
  return currentFixture.value.graph.edges.reduce((acc, item) => {
    const source = collapsedIds.has(parentByNodeId.get(item.source)) ? parentByNodeId.get(item.source) : item.source
    const target = collapsedIds.has(parentByNodeId.get(item.target)) ? parentByNodeId.get(item.target) : item.target
    if (source === target) return acc
    const key = `${source}->${target}`
    if (seen.has(key)) return acc
    seen.add(key)
    acc.push({ ...item, id: `${item.id}__${source}__${target}`, source, target })
    return acc
  }, [])
})

const currentValidation = computed(() => {
  if (!currentFixture.value) return { valid: true, issues: [], errors: [], warnings: [], infos: [] }
  return validateWorkflowDebugGraph(currentFixture.value.graph, currentEvents.value)
})

const validationSummary = computed(() => {
  if (!currentFixture.value) {
    return { type: 'warning', message: '当前为旧版 workflowEvents 组合样例，不包含工作流图数据。' }
  }
  if (currentValidation.value.errors.length) {
    return {
      type: currentFixture.value.invalid ? 'warning' : 'error',
      message: currentFixture.value.invalid
        ? `非法样例命中 ${currentValidation.value.errors.length} 个预期错误，仅用于校验提示。`
        : `当前场景存在 ${currentValidation.value.errors.length} 个工作流图错误。`,
    }
  }
  if (currentValidation.value.warnings.length) {
    return { type: 'warning', message: `当前场景通过校验，但有 ${currentValidation.value.warnings.length} 个警告。` }
  }
  return { type: 'success', message: '当前场景通过本地工作流图校验，可用于渲染回归测试。' }
})

const nodeStates = computed(() => eventsToNodeStates(displayEvents.value))

const highlightedEdgeIds = computed(() => {
  if (!currentFixture.value) return new Set()
  const completedNodeIds = displayEvents.value
    .filter((event) => event.type === 'workflow_node_complete' && event.nodeId && event.success !== false)
    .map((event) => event.nodeId)
  return buildExecutedEdgeIds(graphEdges.value, completedNodeIds)
})

const replayProgressText = computed(() => {
  if (!currentEvents.value.length) return '0 / 0'
  const index = replayIndex.value < 0 ? currentEvents.value.length : replayIndex.value + 1
  return `${Math.min(index, currentEvents.value.length)} / ${currentEvents.value.length}`
})

function loadScenario(id = selectedScenario.value) {
  selectedScenario.value = id
  selectedNodeId.value = null
  currentFixture.value = buildWorkflowDebugFixture(id)
  localJson.value = apiMessageToEditorJson(currentFixture.value.payload.message)
  resetReplay()
  emit('update:modelValue', localJson.value)
}

function onEdit() {
  emit('update:modelValue', localJson.value)
}

function handleFormat() {
  parseError.value = ''
  try {
    const msg = editorJsonToApiMessage(localJson.value)
    localJson.value = apiMessageToEditorJson(msg)
    emit('update:modelValue', localJson.value)
  } catch (e) {
    parseError.value = e.message || 'JSON 格式错误'
  }
}

function applyFixture(fixture) {
  if (scenarioOptions.some((option) => option.value === fixture.id)) {
    selectedScenario.value = fixture.id
  }
  selectedNodeId.value = null
  currentFixture.value = fixture
  localJson.value = apiMessageToEditorJson(fixture.payload.message)
  resetReplay()
  emit('update:modelValue', localJson.value)
}

function buildAdvancedWorkflow() {
  if (!selectedNodeTypes.value?.length) {
    message.warning('请至少选择一个节点类型')
    return
  }
  try {
    applyFixture(buildWorkflowDebugFixtureFromNodeTypes(selectedNodeTypes.value))
    activeTab.value = 'graph'
    message.success('已生成合法工作流图')
  } catch (e) {
    parseError.value = e.message || '高级拼装失败'
  }
}

function clearReplayTimer() {
  if (replayTimer) {
    clearTimeout(replayTimer)
    replayTimer = null
  }
}

function resetReplay() {
  clearReplayTimer()
  replayPlaying.value = false
  replayIndex.value = -1
}

function pauseReplay() {
  replayPlaying.value = false
  clearReplayTimer()
}

function stepReplay(delta = 1) {
  if (!currentEvents.value.length) return
  const nextIndex = Math.min(Math.max(replayIndex.value + delta, 0), currentEvents.value.length - 1)
  replayIndex.value = nextIndex
  const event = currentEvents.value[nextIndex]
  if (event?.nodeId) selectedNodeId.value = event.nodeId
  if (nextIndex >= currentEvents.value.length - 1) pauseReplay()
}

function scheduleReplay() {
  clearReplayTimer()
  if (!replayPlaying.value) return
  replayTimer = setTimeout(
    () => {
      stepReplay(1)
      scheduleReplay()
    },
    Math.max(120, 700 / replaySpeed.value)
  )
}

function playReplay() {
  if (!currentEvents.value.length) {
    message.warning('当前没有 workflowEvents 可回放')
    return
  }
  if (replayIndex.value >= currentEvents.value.length - 1) replayIndex.value = -1
  replayPlaying.value = true
  scheduleReplay()
}

function showFullReplay() {
  pauseReplay()
  replayIndex.value = -1
}

watch(replaySpeed, () => {
  if (replayPlaying.value) scheduleReplay()
})

onBeforeUnmount(clearReplayTimer)

function validateAndGetMessage() {
  parseError.value = ''
  try {
    if (currentFixture.value && currentValidation.value.errors.length) {
      parseError.value = currentFixture.value.invalid
        ? '当前是非法样例，只用于验证校验提示，不进入对话预览。'
        : '当前工作流图未通过本地校验，请先修复场景。'
      return null
    }
    return editorJsonToApiMessage(localJson.value)
  } catch (e) {
    parseError.value = e.message || 'JSON 格式错误'
    return null
  }
}

loadScenario(selectedScenario.value)

defineExpose({ validateAndGetMessage, loadScenario, handleFormat })
</script>

<style scoped>
.debug-workflow-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.debug-panel-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.debug-scenario-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.debug-scenario-card {
  text-align: left;
  border: 1px solid var(--gray-200);
  border-radius: 6px;
  background: var(--color-canvas);
  padding: 9px 10px;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.debug-scenario-card:hover,
.debug-scenario-card.active {
  border-color: var(--color-link);
  box-shadow: 0 0 0 2px var(--color-link-bg-soft);
}

.debug-scenario-card.invalid {
  border-style: dashed;
}

.scenario-title {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-800);
}

.scenario-desc {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--gray-500);
  line-height: 1.45;
}

.scenario-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 8px;
}

.scenario-tag {
  font-size: 11px;
  line-height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--gray-100);
  color: var(--gray-600);
}

.debug-workflow-builder {
  margin-bottom: 12px;
  padding: 10px;
  border: 1px dashed var(--gray-200);
  border-radius: 6px;
  background: var(--color-canvas);
}

.debug-builder-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.debug-replay-toolbar,
.debug-group-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.debug-replay-progress,
.debug-group-label,
.debug-group-hint {
  font-size: 12px;
  color: var(--gray-500);
}

.debug-group-label {
  font-weight: 600;
  color: var(--gray-700);
}

.debug-group-hint {
  margin-left: 2px;
}

.debug-editor-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
  margin-bottom: 8px;
}

.debug-json-textarea {
  flex: 1;
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
}

.debug-workflow-tabs {
  flex: 1;
  min-height: 0;
}

.debug-workflow-tabs :deep(.ant-tabs-content),
.debug-workflow-tabs :deep(.ant-tabs-tabpane) {
  height: 100%;
  min-height: 0;
}

.debug-graph-preview {
  height: 420px;
  min-height: 320px;
  border: 1px solid var(--gray-200);
  border-radius: 6px;
  overflow: hidden;
}

.debug-trace-preview {
  max-height: 420px;
  overflow: auto;
  padding-right: 4px;
}

.debug-validation-alert {
  margin-bottom: 10px;
}

.debug-validation-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 420px;
  overflow: auto;
}

.debug-validation-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--gray-50);
  border: 1px solid var(--gray-200);
  font-size: 12px;
  color: var(--gray-700);
}

.debug-validation-item.error {
  border-color: #fecaca;
  background: #fef2f2;
  color: #991b1b;
}

.debug-validation-item.warning {
  border-color: #fde68a;
  background: #fffbeb;
  color: #92400e;
}

.debug-validation-item.info {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1e40af;
}

/* 深色模式：浅色状态背景在 #111 画布上刺眼且文字对比度不足，
   统一替换为深色底 + 浅色文字，复用 variables.css 的语义背景变量。 */
[data-theme='dark'] .debug-validation-item.error {
  border-color: var(--color-error-deep);
  background: var(--color-error-bg);
  color: var(--color-error-deep);
}

[data-theme='dark'] .debug-validation-item.warning {
  border-color: var(--color-warning-deep);
  background: var(--color-warn-bg);
  color: var(--color-warning-deep);
}

[data-theme='dark'] .debug-validation-item.info {
  border-color: var(--color-link);
  background: var(--color-info-bg);
  color: var(--color-link-deep);
}

.issue-severity {
  flex: 0 0 auto;
  min-width: 48px;
  font-weight: 600;
  text-transform: uppercase;
}

.debug-validation-pass,
.debug-preview-empty {
  padding: 24px;
  text-align: center;
  color: var(--gray-500);
  font-size: 13px;
}

.debug-parse-error {
  margin-top: 8px;
}
</style>
