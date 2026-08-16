<template>
  <aside class="runtime-panel" :style="panelStyle" aria-label="会话协作状态">
    <div
      class="runtime-resize-handle"
      aria-label="拖拽调整协作状态栏宽度"
      @pointerdown="$emit('resize-start', $event)"
    />

    <header class="runtime-panel-header">
      <div>
        <span class="runtime-panel-title-row">
          <strong>状态</strong>
          <em>{{ statusItemCount }} 项</em>
        </span>
        <span>本次任务的待办、文件与执行进展</span>
      </div>
      <a-tooltip title="刷新">
        <a-button type="text" size="small" class="runtime-refresh" :disabled="refreshing" @click="refreshRuntimeState">
          <ReloadOutlined :class="{ 'is-spinning': refreshing }" />
        </a-button>
      </a-tooltip>
    </header>

    <div class="runtime-status-list" aria-label="会话状态分类">
      <section v-if="usage.available" class="runtime-status-section">
        <a-button
          type="default"
          class="runtime-status-button"
          :class="{ active: isExpanded('usage') }"
          block
          :aria-expanded="isExpanded('usage')"
          @click="toggleSection('usage')"
        >
          <span class="runtime-status-icon is-usage"><DashboardOutlined /></span>
          <span class="runtime-status-copy">
            <strong>本轮用量</strong>
            <small>来自已持久化的模型调用记录</small>
          </span>
          <span class="runtime-status-meta">{{ usage.totalTokens || 0 }} tokens</span>
          <RightOutlined class="runtime-status-arrow" :class="{ expanded: isExpanded('usage') }" />
        </a-button>
        <CollapseTransition :open="isExpanded('usage')">
          <a-card size="small" class="runtime-detail-card" :bordered="false">
            <div class="usage-grid">
              <span>
                <small>输入</small>
                <strong>{{ usage.inputTokens || 0 }}</strong>
              </span>
              <span>
                <small>输出</small>
                <strong>{{ usage.outputTokens || 0 }}</strong>
              </span>
              <span>
                <small>合计</small>
                <strong>{{ usage.totalTokens || 0 }}</strong>
              </span>
            </div>
          </a-card>
        </CollapseTransition>
      </section>

      <section class="runtime-status-section">
        <a-button
          type="default"
          class="runtime-status-button"
          :class="{ active: isExpanded('todos') }"
          block
          :aria-expanded="isExpanded('todos')"
          @click="toggleSection('todos')"
        >
          <span class="runtime-status-icon is-todo"><CheckSquareOutlined /></span>
          <span class="runtime-status-copy">
            <strong>待办</strong>
            <small>计划与完成进度</small>
          </span>
          <span class="runtime-status-meta">
            {{ todos.length ? `${completedTodoCount}/${todos.length} · ${todoProgress}%` : '0' }}
          </span>
          <RightOutlined class="runtime-status-arrow" :class="{ expanded: isExpanded('todos') }" />
        </a-button>
        <CollapseTransition :open="isExpanded('todos')">
          <a-card size="small" class="runtime-detail-card" :bordered="false">
            <div v-if="todos.length" class="todo-list">
              <div class="todo-progress-track"><span :style="{ width: `${todoProgress}%` }"></span></div>
              <div v-for="todo in todos" :key="todo.id" class="todo-item" :class="`is-${todo.status}`">
                <span class="todo-status-icon">
                  <LoadingOutlined v-if="todo.status === 'in_progress'" class="todo-spinning" />
                  <CheckCircleFilled v-else-if="todo.status === 'completed'" />
                  <CloseCircleFilled v-else-if="todo.status === 'cancelled'" />
                  <ClockCircleOutlined v-else />
                </span>
                <span class="todo-content">{{ todo.content }}</span>
              </div>
            </div>
            <a-empty v-else :image="emptyImage" description="暂无待办" class="runtime-empty" />
          </a-card>
        </CollapseTransition>
      </section>

      <section class="runtime-status-section">
        <a-button
          type="default"
          class="runtime-status-button"
          :class="{ active: isExpanded('files') }"
          block
          :aria-expanded="isExpanded('files')"
          @click="toggleSection('files')"
        >
          <span class="runtime-status-icon is-files"><PaperClipOutlined /></span>
          <span class="runtime-status-copy">
            <strong>附件/文件</strong>
            <small>会话中可预览的文件</small>
          </span>
          <span class="runtime-status-meta">{{ attachments.length }}</span>
          <RightOutlined class="runtime-status-arrow" :class="{ expanded: isExpanded('files') }" />
        </a-button>
        <CollapseTransition :open="isExpanded('files')">
          <a-card size="small" class="runtime-detail-card" :bordered="false">
            <div v-if="attachments.length" class="file-list">
              <a-button
                v-for="file in attachments.slice(0, 4)"
                :key="file.id || file.objectKey || file.fileName"
                type="text"
                class="file-item"
                block
                @click="$emit('preview-attachment', file)"
              >
                <FileOutlined />
                <span>{{ file.fileName || file.name || '未命名文件' }}</span>
              </a-button>
              <a-button v-if="attachments.length > 4" type="link" class="runtime-more" @click="$emit('open-files')">
                查看全部 {{ attachments.length }} 个文件
              </a-button>
            </div>
            <a-empty v-else :image="emptyImage" description="暂无附件或文件" class="runtime-empty" />
          </a-card>
        </CollapseTransition>
      </section>

      <section class="runtime-status-section">
        <a-button
          type="default"
          class="runtime-status-button"
          :class="{ active: isExpanded('artifacts') }"
          block
          :aria-expanded="isExpanded('artifacts')"
          @click="toggleSection('artifacts')"
        >
          <span class="runtime-status-icon is-artifacts"><InboxOutlined /></span>
          <span class="runtime-status-copy">
            <strong>产物</strong>
            <small>主 Agent 已交付的结果</small>
          </span>
          <span class="runtime-status-meta">{{ artifacts.length }}</span>
          <RightOutlined class="runtime-status-arrow" :class="{ expanded: isExpanded('artifacts') }" />
        </a-button>
        <CollapseTransition :open="isExpanded('artifacts')">
          <a-card size="small" class="runtime-detail-card" :bordered="false">
            <div v-if="artifacts.length" class="artifact-list">
              <a-button
                v-for="artifact in artifacts"
                :key="artifact.path || artifact.url || artifact.name"
                type="text"
                class="artifact-card"
                block
                @click="openArtifactPreview(artifact)"
              >
                <FileDoneOutlined />
                <span class="artifact-main">
                  <strong>{{ artifact.name || '未命名产物' }}</strong>
                  <small>{{ artifact.path || artifact.contentType || '已交付' }}</small>
                </span>
              </a-button>
            </div>
            <a-empty v-else :image="emptyImage" description="暂无交付产物" class="runtime-empty" />
          </a-card>
        </CollapseTransition>
      </section>

      <section class="runtime-status-section">
        <a-button
          type="default"
          class="runtime-status-button"
          :class="{ active: isExpanded('subagents') }"
          block
          :aria-expanded="isExpanded('subagents')"
          @click="toggleSection('subagents')"
        >
          <span class="runtime-status-icon is-subagents"><RobotOutlined /></span>
          <span class="runtime-status-copy">
            <strong>子智能体</strong>
            <small>仅显示本次任务的并行执行</small>
          </span>
          <span class="runtime-status-meta">{{ subagentMeta }}</span>
          <RightOutlined class="runtime-status-arrow" :class="{ expanded: isExpanded('subagents') }" />
        </a-button>
        <CollapseTransition :open="isExpanded('subagents')">
          <a-card size="small" class="runtime-detail-card" :bordered="false">
            <transition-group v-if="runtimeRuns.length" name="subagent-card" tag="div" class="subagent-list">
              <div v-for="run in runtimeRuns" :key="run.task_id" class="subagent-run">
                <a-button type="text" class="subagent-card" block :class="`is-${run.status}`" @click="openDetail(run)">
                  <span class="subagent-avatar"><RobotOutlined /></span>
                  <span class="subagent-main">
                    <span class="subagent-name-row">
                      <strong>{{ displayNameOf(run) }}</strong>
                      <a-tag class="subagent-status" :class="`is-${run.status}`">{{ statusLabel(run.status) }}</a-tag>
                    </span>
                    <span class="subagent-task">{{ run.task || '正在准备任务' }}</span>
                    <span class="subagent-output">
                      {{ run.liveOutput || run.progress_summary || run.status_label || '等待调度' }}
                    </span>
                  </span>
                  <LoadingOutlined v-if="isRunning(run.status)" spin class="subagent-spinner" />
                  <RightOutlined class="subagent-expand-arrow" />
                </a-button>
              </div>
            </transition-group>
            <a-empty v-else :image="emptyImage" description="本次任务暂无子智能体" class="runtime-empty" />
          </a-card>
        </CollapseTransition>
      </section>
    </div>

    <SubAgentTaskDetailModal
      v-if="detailOpen"
      v-model:open="detailOpen"
      :session-id="sessionId"
      :task="selectedTask"
      :live-events="liveEvents"
    />

    <FilePreviewModal
      v-model:open="artifactPreviewOpen"
      :file-name="artifactPreviewFile?.name || ''"
      :file-url="artifactPreviewFile?.url || ''"
      :download-url="artifactPreviewDownloadUrl"
      :file-type="artifactPreviewExt"
      :is-video="artifactPreviewIsVideo"
    />
  </aside>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Empty } from 'ant-design-vue'
import {
  BorderOutlined,
  CheckCircleFilled,
  CheckSquareOutlined,
  ClockCircleOutlined,
  CloseCircleFilled,
  DashboardOutlined,
  FileDoneOutlined,
  FileOutlined,
  InboxOutlined,
  LoadingOutlined,
  PaperClipOutlined,
  ReloadOutlined,
  RightOutlined,
  RobotOutlined,
} from '@ant-design/icons-vue'
import { getResearchTaskProjection, getSubAgentRuntimeSummaries } from '../../../api/subagent'
import { pickFresher } from '../../../utils/subagentRuntime'
import { canOpenSourcePreview, getFileExtension, resolveSourcePreviewKind } from '../../../utils/filePreview'
import SubAgentTaskDetailModal from './SubAgentTaskDetailModal.vue'
import FilePreviewModal from '../../FilePreviewModal.vue'
import CollapseTransition from '../../common/CollapseTransition.vue'

const props = defineProps({
  sessionId: { type: [String, Number], default: null },
  messages: { type: Array, default: () => [] },
  liveEvents: { type: Array, default: () => [] },
  taskIds: { type: Array, default: () => [] },
  parentRequestId: { type: String, default: null },
  width: { type: Number, default: 336 },
})

defineEmits(['open-files', 'preview-attachment', 'resize-start'])

const emptyImage = Empty.PRESENTED_IMAGE_SIMPLE
const attachments = ref([])
const dbRuns = ref([])
const projection = ref(null)
const expandedSections = ref(new Set())
const detailOpen = ref(false)
const selectedTask = ref(null)
const refreshing = ref(false)
const artifactPreviewOpen = ref(false)
const artifactPreviewFile = ref(null)
let refreshTimer = null

const panelStyle = computed(() => ({ width: `${props.width}px`, flexBasis: `${props.width}px` }))
const currentTaskIds = computed(() => new Set(props.taskIds.map((taskId) => String(taskId))))

function isExpanded(key) {
  return expandedSections.value.has(key)
}
function toggleSection(key) {
  const next = new Set(expandedSections.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedSections.value = next
}

/** 打开子智能体详情弹窗：完整详情（实时/最终输出、工具、事件、子线程）由共享组件加载 */
function openDetail(run) {
  selectedTask.value = run
  detailOpen.value = true
}

const artifactPreviewExt = computed(() => getFileExtension(artifactPreviewFile.value?.name || ''))
const artifactPreviewIsVideo = computed(() => {
  const file = artifactPreviewFile.value
  return file ? resolveSourcePreviewKind(file.name, file.contentType) === 'video' : false
})
const artifactPreviewDownloadUrl = computed(() => {
  const file = artifactPreviewFile.value
  return file ? file.downloadUrl || file.url || '' : ''
})

/** 产物点击：可预览类型直接开预览弹窗，否则新窗口打开下载链接（不再退回会话文件侧栏） */
function openArtifactPreview(artifact) {
  if (!artifact?.url) return
  if (canOpenSourcePreview(artifact.name)) {
    artifactPreviewFile.value = artifact
    artifactPreviewOpen.value = true
    return
  }
  window.open(artifact.downloadUrl || artifact.url, '_blank')
}

function parseResult(event) {
  if (!event?.result) return null
  if (typeof event.result === 'object') return event.result
  try {
    return JSON.parse(event.result)
  } catch {
    return null
  }
}

const allToolEvents = computed(() => props.messages.flatMap((message) => message?._toolEvents || []))
const todosFromMessages = computed(() => {
  let snapshot = []
  for (const event of allToolEvents.value) {
    if (event?.type !== 'tool_result' || event.toolName !== 'write_todos') continue
    const payload = parseResult(event)
    if (payload?.success && Array.isArray(payload.todos)) snapshot = payload.todos
  }
  return snapshot
})
// 实时层：取 SSE 流中最后一次 todos_updated 快照（write_todos 落库即推流，无需等 5s 轮询）
const liveTodos = computed(() => {
  let snapshot = null
  for (const event of props.liveEvents) {
    if (event?.type !== 'todos_updated') continue
    if (Array.isArray(event.todos)) snapshot = event.todos
  }
  return snapshot
})
// 三层降级：SSE 实时快照 > projection 持久化快照 > 当前消息 toolEvents
const todos = computed(() => {
  if (liveTodos.value?.length) return liveTodos.value
  if (projection.value?.todos?.length) return projection.value.todos
  return todosFromMessages.value
})
const completedTodoCount = computed(() => todos.value.filter((todo) => todo.status === 'completed').length)
const todoProgress = computed(() =>
  todos.value.length ? Math.round((completedTodoCount.value * 100) / todos.value.length) : 0
)

const artifactsFromMessages = computed(() => {
  // 产物按 path 去重：同一文件被多次写入/交付，只保留最后一次快照
  const byPath = new Map()
  for (const event of allToolEvents.value) {
    if (event?.type !== 'tool_result') continue
    const payload = parseResult(event)
    if (!payload) continue
    if (event.toolName === 'present_artifacts') {
      for (const artifact of payload?.artifacts || []) {
        const key = artifact.path || artifact.url || artifact.name
        if (key) byPath.set(key, artifact)
      }
      continue
    }
    // sandbox_write_file / sandbox_append_file 写 outputs/ 时也算产物
    // 后端在 outputs/ 写入时会注入 url/name/contentType/downloadUrl，前端直接复用
    if (
      (event.toolName === 'sandbox_write_file' || event.toolName === 'sandbox_append_file') &&
      payload.success &&
      payload.url
    ) {
      const key = payload.path || payload.url || payload.name
      if (key) byPath.set(key, payload)
    }
  }
  return [...byPath.values()]
})
const artifacts = computed(() =>
  projection.value?.artifacts?.length ? projection.value.artifacts : artifactsFromMessages.value
)
const usage = computed(() => (projection.value?.usage?.available ? projection.value.usage : { available: false }))

const liveTaskStates = computed(() => {
  const states = new Map()
  for (const event of props.liveEvents) {
    if (event?.type === 'subagent_batch_start') {
      for (const task of event.tasks || []) {
        if (!currentTaskIds.value.has(String(task.task_id))) continue
        states.set(String(task.task_id), {
          task_id: task.task_id,
          batch_id: event.batch_id,
          subagent_name: task.subagent_name,
          display_name: task.display_name || task.displayName || task.subagent_name,
          task: task.task,
          status: 'pending',
          progress_summary: '等待调度',
          liveOutput: '',
        })
      }
      continue
    }
    if (!event?.task_id || !currentTaskIds.value.has(String(event.task_id))) continue
    const taskKey = String(event.task_id)
    const state = states.get(taskKey) || {
      task_id: event.task_id,
      batch_id: event.batch_id,
      subagent_name: event.subagentName,
      display_name: event.display_name || event.displayName || event.subagentName,
      task: event.task || '',
      status: 'pending',
      progress_summary: '等待调度',
      liveOutput: '',
    }
    if (event.type === 'subagent_task_start') {
      state.status = 'running'
      state.progress_summary = '正在执行'
    } else if (event.type === 'subagent_token') {
      state.status = 'running'
      state.progress_summary = '正在生成输出'
      state.liveOutput = `${state.liveOutput || ''}${event.content || ''}`.slice(-260)
    } else if (event.type === 'subagent_tool_call') {
      state.status = 'running'
      state.progress_summary = `正在调用 ${event.toolDisplayName || event.toolName || '工具'}`
    } else if (event.type === 'subagent_tool_result') {
      state.status = 'running'
      state.progress_summary = '工具执行完成，继续处理'
    } else if (event.type === 'subagent_error') {
      state.status = 'failed'
      state.progress_summary = event.message || '任务执行失败'
    } else if (event.type === 'subagent_task_done') {
      state.status = event.status || 'completed'
      state.progress_summary = state.status === 'completed' ? '任务已完成' : '任务已结束'
      state.liveOutput = event.result?.reply || state.liveOutput
    }
    if (event.task) state.task = event.task
    if (event.display_name || event.displayName) state.display_name = event.display_name || event.displayName
    states.set(taskKey, state)
  }
  return states
})

const runtimeRuns = computed(() => {
  const merged = new Map(dbRuns.value.map((run) => [String(run.task_id), { ...run }]))
  for (const [taskId, live] of liveTaskStates.value) {
    merged.set(taskId, pickFresher(merged.get(taskId), live))
  }
  return [...merged.values()].sort((a, b) => Number(isRunning(b.status)) - Number(isRunning(a.status)))
})

const shouldPoll = computed(
  () => projection.value?.status === 'running' || runtimeRuns.value.some((run) => isRunning(run.status))
)

const runningSubagentCount = computed(() => runtimeRuns.value.filter((run) => isRunning(run.status)).length)
const subagentMeta = computed(() =>
  runtimeRuns.value.length
    ? runningSubagentCount.value
      ? `${runningSubagentCount.value}/${runtimeRuns.value.length} 运行中`
      : runtimeRuns.value.length
    : '0'
)
const statusItemCount = computed(
  () =>
    [
      usage.value.available,
      todos.value.length,
      attachments.value.length,
      artifacts.value.length,
      runtimeRuns.value.length,
    ].filter(Boolean).length
)

function isRunning(status) {
  return ['pending', 'running', 'cancel_requested'].includes(status)
}
function statusLabel(status) {
  return (
    { pending: '待调度', running: '运行中', completed: '已完成', failed: '失败', cancelled: '已取消', timeout: '超时' }[
      status
    ] || '处理中'
  )
}
function displayNameOf(run) {
  return run.display_name || run.displayName || run.subagent_name || run.subagentName || '子智能体'
}

async function refreshRuns() {
  if (props.parentRequestId) return
  if (!props.sessionId || (!currentTaskIds.value.size && !props.parentRequestId)) {
    dbRuns.value = []
    return
  }
  try {
    const response = await getSubAgentRuntimeSummaries(props.sessionId, 30, props.parentRequestId)
    const runs = response.data || []
    // 有 requestId 时服务端已严格限定本次用户请求；流式期尚未取得 requestId 时再用 SSE taskId 兜底。
    dbRuns.value = props.parentRequestId ? runs : runs.filter((run) => currentTaskIds.value.has(String(run.task_id)))
  } catch {
    // SSE 仍可提供当前请求运行态，轮询失败时不清空已展示的状态。
  }
}

/**
 * 以 parentRequestId 获取任务级事实快照。运行中的 SSE 仅补充增量，重进/刷新时不再从整个会话猜测待办和附件。
 */
async function refreshProjection() {
  if (!props.sessionId || !props.parentRequestId) {
    projection.value = null
    return
  }
  try {
    const response = await getResearchTaskProjection(props.sessionId, props.parentRequestId)
    const next = response.data
    if (!next) return
    const currentVersion = Number(projection.value?.version || 0)
    const nextVersion = Number(next.version || 0)
    if (nextVersion && currentVersion && nextVersion < currentVersion) return
    projection.value = next
    attachments.value = next.attachments || []
    dbRuns.value = next.subagents || []
  } catch {
    // 快照接口短暂失败时保留现有状态，并由当前 SSE 或旧轮询兜底继续展示。
  }
}

async function refreshRuntimeState() {
  if (refreshing.value) return
  refreshing.value = true
  const minDelay = new Promise((resolve) => setTimeout(resolve, 500))
  try {
    await Promise.all([refreshProjection(), refreshRuns(), minDelay])
  } finally {
    refreshing.value = false
  }
}

function startPolling() {
  clearInterval(refreshTimer)
  if (!shouldPoll.value) return
  refreshTimer = setInterval(() => {
    refreshProjection()
    refreshRuns()
  }, 5000)
}

watch(
  () => props.sessionId,
  () => {
    dbRuns.value = []
    projection.value = null
    attachments.value = []
    refreshProjection()
    refreshRuns()
    startPolling()
  },
  { immediate: true }
)

watch(
  () => [props.taskIds, props.parentRequestId],
  () => {
    refreshProjection()
    refreshRuns()
  },
  { deep: true }
)
watch(
  () => props.parentRequestId,
  () => {
    detailOpen.value = false
    selectedTask.value = null
    projection.value = null
    attachments.value = []
  }
)
watch(shouldPoll, startPolling, { immediate: true })

onBeforeUnmount(() => clearInterval(refreshTimer))
</script>

<style scoped>
.runtime-panel {
  position: relative;
  width: 336px;
  min-height: 100%;
  flex: 0 0 336px;
  overflow-y: auto;
  border-left: 1px solid var(--color-hairline);
  background: var(--color-canvas);
  box-shadow: -10px 0 28px rgba(15, 23, 42, 0.04);
  padding: 20px 16px 28px;
}
.runtime-resize-handle {
  position: absolute;
  z-index: 2;
  top: 0;
  bottom: 0;
  left: -5px;
  width: 10px;
  cursor: col-resize;
  touch-action: none;
}
.runtime-resize-handle::after {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 4px;
  width: 2px;
  border-radius: 2px;
  background: var(--color-link);
  content: '';
  opacity: 0;
  transition: opacity 0.16s ease;
}
.runtime-resize-handle:hover::after {
  opacity: 0.55;
}
.runtime-panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.runtime-panel-header > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.runtime-panel-title-row {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
}
.runtime-panel-header strong {
  color: var(--color-ink);
  font-size: 15px;
  line-height: 1.35;
}
.runtime-panel-header .runtime-panel-title-row em {
  color: var(--color-mute);
  font-size: 12px;
  font-style: normal;
}
.runtime-panel-header > div > span:last-child {
  color: var(--color-mute);
  font-size: 12px;
}
.runtime-refresh {
  display: inline-flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  color: var(--color-body);
}
.runtime-refresh:hover {
  background: var(--color-canvas-soft) !important;
  color: var(--color-ink) !important;
}
.is-spinning {
  animation: runtime-spin 0.8s linear infinite;
}
.runtime-status-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.runtime-status-section {
  display: flex;
  flex-direction: column;
}
.runtime-status-button {
  display: flex !important;
  width: 100%;
  height: auto !important;
  align-items: center;
  min-height: 66px;
  gap: 10px;
  border: 1px solid var(--color-hairline) !important;
  border-radius: var(--radius-md) !important;
  background: var(--color-canvas) !important;
  padding: 10px !important;
  color: var(--color-ink) !important;
  text-align: left;
  white-space: normal !important;
  transition:
    border-color 0.18s ease,
    background 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;
}
.runtime-status-button:hover {
  border-color: var(--color-hairline-strong) !important;
  background: var(--color-canvas-soft) !important;
  transform: translateY(-1px);
}
.runtime-status-button.active {
  border-color: var(--color-link) !important;
  background: var(--color-link-bg-soft) !important;
  box-shadow: 0 4px 14px color-mix(in srgb, var(--color-link) 14%, transparent);
}
.runtime-status-icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  flex: 0 0 34px;
  border-radius: 9px;
  background: var(--color-canvas-soft-2);
  color: var(--color-mute);
  font-size: 16px;
}
.runtime-status-icon.is-usage {
  color: #0f766e;
  background: #ccfbf1;
}
.runtime-status-icon.is-todo {
  color: #15803d;
  background: #dcfce7;
}
.runtime-status-icon.is-files {
  color: #1d4ed8;
  background: #dbeafe;
}
.runtime-status-icon.is-artifacts {
  color: #9333ea;
  background: #f3e8ff;
}
.runtime-status-icon.is-subagents {
  color: #c2410c;
  background: #ffedd5;
}
.runtime-status-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}
.runtime-status-copy strong {
  overflow: hidden;
  color: var(--color-ink);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.runtime-status-copy small {
  overflow: hidden;
  color: var(--color-mute);
  font-size: 11px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.runtime-status-meta {
  flex: 0 0 auto;
  color: var(--color-mute);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.runtime-status-arrow {
  flex: 0 0 auto;
  color: var(--color-mute);
  font-size: 11px;
  transition: transform 0.2s ease;
}
.runtime-status-arrow.expanded {
  color: var(--color-link);
  transform: rotate(90deg);
}
.runtime-status-button.active .runtime-status-copy strong,
.runtime-status-button.active .runtime-status-meta {
  color: var(--color-link-deep);
}
.runtime-detail-card {
  margin-top: 8px;
  border: 1px solid var(--color-hairline) !important;
  border-radius: var(--radius-lg) !important;
  background: var(--color-canvas-soft) !important;
}
.runtime-detail-card :deep(.ant-card-body) {
  padding: 14px;
}
.runtime-empty {
  margin: 0;
  padding: 8px 0 2px;
}
.todo-list,
.file-list,
.artifact-list,
.subagent-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.todo-list {
  gap: 6px;
}
.todo-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  color: var(--color-body);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.55;
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  border-left: 3px solid var(--color-mute);
  border-radius: 7px;
  transition:
    background 0.15s ease,
    border-color 0.15s ease;
}
.todo-item:hover {
  background: var(--color-canvas-soft);
}
.todo-status-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  font-size: 13px;
  line-height: 1;
  color: var(--color-mute);
}
.todo-status-icon :deep(.anticon) {
  display: inline-flex;
  font-size: 13px;
  line-height: 1;
}
.todo-status-icon :deep(svg) {
  display: block;
  width: 13px !important;
  height: 13px !important;
}
.todo-spinning {
  animation: runtime-spin 1s linear infinite;
  display: inline-block;
}
.todo-content {
  flex: 1;
  min-width: 0;
  word-break: break-word;
}
.todo-item.is-pending {
  border-left-color: #94a3b8;
  background: #f8fafc;
}
.todo-item.is-pending .todo-status-icon {
  color: #64748b;
}
.todo-item.is-pending:hover {
  background: #f1f5f9;
}
.todo-item.is-in_progress {
  border-left-color: var(--color-link);
  background: var(--color-link-bg-soft, #eff6ff);
}
.todo-item.is-in_progress .todo-status-icon {
  color: var(--color-link);
}
.todo-item.is-completed {
  color: var(--color-mute);
  text-decoration: line-through;
  background: var(--color-canvas-soft);
  border-left-color: var(--color-success);
}
.todo-item.is-completed .todo-status-icon {
  color: var(--color-success);
}
.todo-item.is-cancelled {
  color: var(--color-mute);
  background: var(--color-canvas-soft);
  border-left-color: var(--color-error);
}
.todo-item.is-cancelled .todo-status-icon {
  color: var(--color-error);
}
.todo-progress-track {
  height: 4px;
  margin-bottom: 4px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--color-hairline);
}
.todo-progress-track > span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--color-success);
  transition: width 0.25s ease;
}
.usage-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.usage-grid span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
  padding: 8px;
  border-radius: 7px;
  background: var(--color-canvas);
}
.usage-grid small {
  color: var(--color-mute);
  font-size: 11px;
}
.usage-grid strong {
  overflow: hidden;
  color: var(--color-ink);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
}
.file-item,
.artifact-card,
.subagent-card {
  display: flex !important;
  width: 100%;
  height: auto !important;
  align-items: flex-start;
  justify-content: flex-start;
  gap: 8px;
  padding: 7px 8px !important;
  border-radius: 8px !important;
  color: var(--color-body) !important;
  text-align: left;
  white-space: normal !important;
}
.file-item:hover,
.artifact-card:hover,
.subagent-card:hover {
  background: var(--color-canvas-soft-2) !important;
  color: var(--color-ink) !important;
}
.file-item span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.runtime-more {
  align-self: flex-start;
  padding: 0 !important;
}
.artifact-card > :first-child {
  margin-top: 3px;
  color: #9333ea;
}
.artifact-main {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}
.artifact-main strong,
.artifact-main small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.artifact-main strong {
  color: var(--color-ink);
  font-size: 12px;
}
.artifact-main small {
  color: var(--color-mute);
  font-size: 11px;
}
.subagent-run {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.subagent-card {
  position: relative;
  min-width: 0;
  border: 1px solid var(--color-hairline) !important;
  background: var(--color-canvas) !important;
}
.subagent-card.is-running {
  border-color: color-mix(in srgb, var(--color-link) 35%, var(--color-hairline)) !important;
}
.subagent-avatar {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  flex: 0 0 28px;
  border-radius: 8px;
  background: #ffedd5;
  color: #c2410c;
}
.subagent-main {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}
.subagent-name-row {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 5px;
}
.subagent-name-row strong {
  overflow: hidden;
  flex: 1;
  color: var(--color-ink);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.subagent-status {
  margin: 0;
  border: 0;
  border-radius: 4px;
  font-size: 10px;
  line-height: 18px;
}
.subagent-status.is-running,
.subagent-status.is-pending {
  color: #1d4ed8;
  background: #dbeafe;
}
.subagent-status.is-completed {
  color: #15803d;
  background: #dcfce7;
}
.subagent-status.is-failed,
.subagent-status.is-timeout,
.subagent-status.is-cancelled {
  color: #b91c1c;
  background: #fee2e2;
}
.subagent-task,
.subagent-output {
  min-width: 0;
}
.subagent-task {
  color: var(--color-body);
  font-size: 11px;
  line-height: 1.45;
  word-break: break-word;
  white-space: normal;
}
.subagent-output {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-mute);
  font-size: 11px;
}
.subagent-spinner {
  align-self: center;
  color: var(--color-link);
}
.subagent-expand-arrow {
  align-self: center;
  flex: 0 0 auto;
  color: var(--color-mute);
  font-size: 11px;
}
.subagent-card-enter-active,
.subagent-card-leave-active {
  transition:
    opacity 0.22s ease,
    transform 0.22s ease;
}
.subagent-card-enter-from,
.subagent-card-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
@keyframes runtime-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 1100px) {
  .runtime-panel {
    display: none;
  }
}
</style>
