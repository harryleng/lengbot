<template>
  <a-modal
    :open="open"
    width="760px"
    :footer="null"
    :mask-closable="false"
    :closable="false"
    destroy-on-close
    @update:open="$emit('update:open', $event)"
  >
    <template #title>
      <div class="detail-modal-header">
        <span class="detail-modal-title">{{ detailTitle }}</span>
        <div class="detail-modal-actions">
          <a-tooltip title="刷新详情">
            <a-button
              type="text"
              size="small"
              class="detail-icon-btn"
              :disabled="detailLoading || detailRefreshing"
              @click="refreshDetail"
            >
              <ReloadOutlined :class="{ 'is-spinning': detailRefreshing }" />
            </a-button>
          </a-tooltip>
          <a-tooltip title="关闭">
            <a-button type="text" size="small" class="detail-icon-btn" @click="emitClose">
              <CloseOutlined />
            </a-button>
          </a-tooltip>
        </div>
      </div>
    </template>
    <div class="dialog-scroll-body">
      <a-spin :spinning="detailLoading">
        <div ref="scrollRef" class="task-detail-scroll" @scroll="onScroll">
          <div v-if="selectedDisplayTask" class="task-detail-summary">
            <span :class="['task-detail-status', `is-${selectedDisplayTask.status}`]">
              {{ selectedDisplayTask.status_label || statusLabel(selectedDisplayTask.status) }}
            </span>
            <span :class="['task-detail-progress', `is-${selectedDisplayTask.status}`]">
              {{
                selectedDisplayTask.progress_summary ||
                selectedDisplayTask.status_label ||
                statusLabel(selectedDisplayTask.status)
              }}
            </span>
          </div>
          <div v-if="selectedDisplayTask" class="detail-section task-context-section">
            <button
              type="button"
              class="detail-section-title detail-section-toggle"
              :aria-expanded="taskInfoExpanded"
              @click="taskInfoExpanded = !taskInfoExpanded"
            >
              <span>任务信息</span>
              <RightOutlined class="detail-section-arrow" :class="{ expanded: taskInfoExpanded }" />
            </button>
            <CollapseTransition :open="taskInfoExpanded">
              <div class="detail-section-body">
                <div class="task-context-label">任务提示</div>
                <div class="task-context-content">{{ selectedDisplayTask.task || '未提供任务描述' }}</div>
                <div class="task-time-row">
                  <span>开始：{{ formatTime(selectedDisplayTask.start_time) || '-' }}</span>
                  <span>结束：{{ formatTime(selectedDisplayTask.end_time) || '-' }}</span>
                </div>
                <div v-if="selectedDisplayTask.error" class="task-error">
                  <span class="task-context-label">异常信息</span>
                  <span>{{ selectedDisplayTask.error }}</span>
                </div>
              </div>
            </CollapseTransition>
          </div>
          <div v-if="displayOutput" class="detail-section live-output-section">
            <button
              type="button"
              class="detail-section-title detail-section-toggle"
              :aria-expanded="finalOutputExpanded"
              @click="finalOutputExpanded = !finalOutputExpanded"
            >
              <span>{{ isSelectedTaskDone ? '最终输出' : '实时输出' }}</span>
              <RightOutlined class="detail-section-arrow" :class="{ expanded: finalOutputExpanded }" />
            </button>
            <CollapseTransition :open="finalOutputExpanded">
              <div class="detail-section-body">
                <MarkdownPreview :content="displayOutput" :finalized="isSelectedTaskDone" :image-preview="false" />
              </div>
            </CollapseTransition>
          </div>
          <div v-if="detailToolEvents.length" class="detail-section tool-calls-section">
            <button
              type="button"
              class="detail-section-title detail-section-toggle"
              :aria-expanded="toolCallsExpanded"
              @click="toolCallsExpanded = !toolCallsExpanded"
            >
              <span>工具调用明细</span>
              <RightOutlined class="detail-section-arrow" :class="{ expanded: toolCallsExpanded }" />
            </button>
            <CollapseTransition :open="toolCallsExpanded">
              <div class="detail-section-body">
                <ToolCallsGroupComponent
                  :tool-events="detailToolEvents"
                  :is-done="isSelectedTaskDone"
                  :default-expanded="true"
                  :message-index="-1"
                />
              </div>
            </CollapseTransition>
          </div>
          <div class="detail-section">
            <div class="detail-section-title">运行事件</div>
            <a-timeline v-if="events.length" class="event-timeline">
              <a-timeline-item v-for="event in events" :key="event.cursor" :color="eventColor(event)">
                <div class="event-title" :class="{ 'is-error': isErrorEvent(event) }">{{ eventLabel(event) }}</div>
                <div class="event-time">{{ formatTime(event.create_time) }}</div>
                <div v-if="eventText(event)" class="event-text" :class="{ 'is-error': isErrorEvent(event) }">
                  {{ eventText(event) }}
                </div>
              </a-timeline-item>
            </a-timeline>
            <a-empty v-else description="暂无运行事件" :image="aEmptyImage" />
          </div>
          <div class="detail-section">
            <div class="detail-section-title">独立子线程</div>
            <div v-if="threadMessages.length" class="thread-messages">
              <div
                v-for="(item, index) in threadMessages"
                :key="item.id || item.messageId || index"
                class="thread-message"
                :class="`role-${item.type}`"
              >
                <span class="thread-role">{{ roleLabel(item.type) }}</span>
                <pre class="thread-content">{{ threadContent(item) }}</pre>
              </div>
            </div>
            <a-empty
              v-else
              :description="threadAvailable ? '子线程暂未产生可展示消息' : '子线程历史暂不可用'"
              :image="aEmptyImage"
            />
          </div>
        </div>
      </a-spin>
    </div>
  </a-modal>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch, nextTick, toRef } from 'vue'
import { Empty } from 'ant-design-vue'
import { ReloadOutlined, RightOutlined, CloseOutlined } from '@ant-design/icons-vue'
import MarkdownPreview from '@/components/MarkdownPreview.vue'
import ToolCallsGroupComponent from '@/components/ToolCallsGroupComponent.vue'
import CollapseTransition from '@/components/common/CollapseTransition.vue'
import { formatTime } from '@/utils/format'
import { pickFresher } from '@/utils/subagentRuntime'
import { getSubAgentRun, getSubAgentRunEvents, getSubAgentRunThread } from '@/api/subagent'
import { useAutoScroll } from '@/composables/chat/useAutoScroll'
import { useSubAgentLiveState } from '@/composables/chat/useSubAgentLiveState'

const props = defineProps({
  open: { type: Boolean, default: false },
  sessionId: { type: [String, Number], default: null },
  task: { type: Object, default: null },
  liveEvents: { type: Array, default: () => [] },
})

const emit = defineEmits(['update:open'])

const aEmptyImage = Empty.PRESENTED_IMAGE_SIMPLE
const detailTask = ref(null)
const detailLoading = ref(false)
const detailRefreshing = ref(false)
const events = ref([])
const eventCursor = ref('')
// 滚动容器 ref（useAutoScroll 在所有依赖 computed 定义之后再调用，避免 TDZ）
const scrollRef = ref(null)
const threadMessages = ref([])
const threadAvailable = ref(false)
// 三大块默认展开
const taskInfoExpanded = ref(true)
const finalOutputExpanded = ref(true)
const toolCallsExpanded = ref(true)
let refreshTimer = null

function emitClose() {
  emit('update:open', false)
}

const detailTitle = computed(() => (props.task ? `${displayNameOf(props.task)} · 子线程详情` : '子智能体子线程详情'))

/** 从父级透传的实时 SSE 中还原当前任务的运行态（增量维护 + RAF 批处理） */
const { stateMap: liveTaskStates } = useSubAgentLiveState(toRef(props, 'liveEvents'))

const selectedLiveState = computed(() => liveTaskStates.value.get(String(props.task?.task_id)) || null)
const baseTask = computed(() => detailTask.value || props.task)
const selectedDisplayTask = computed(() =>
  baseTask.value ? pickFresher(baseTask.value, selectedLiveState.value) : null
)
const liveOutput = computed(() => selectedLiveState.value?.liveOutput || '')
const isSelectedTaskDone = computed(() =>
  ['completed', 'failed', 'cancelled', 'timeout'].includes(selectedDisplayTask.value?.status)
)
const displayOutput = computed(() => {
  const task = selectedDisplayTask.value
  if (!task) return ''
  if (isSelectedTaskDone.value && task.reply) return task.reply
  return liveOutput.value || task.reply || ''
})

/** 将子智能体持久化事件适配为会话工具组件的数据结构，沿用参数展开与详情弹窗。 */
const detailToolEvents = computed(() =>
  events.value.flatMap((event) => {
    const payload = eventPayload(event)
    const toolName = payload.toolName || payload.tool_name
    const displayName = payload.toolDisplayName || payload.tool_display_name || toolName
    if (event.type === 'subagent_tool_call' && toolName) {
      return [
        {
          type: 'tool_call',
          toolName,
          displayName,
          args: stringifyToolPayload(payload.args ?? payload.arguments ?? payload.params),
        },
      ]
    }
    if (event.type === 'subagent_tool_result' && toolName) {
      return [
        {
          type: 'tool_result',
          toolName,
          displayName,
          result: stringifyToolPayload(payload.result ?? payload.content ?? payload.message),
        },
      ]
    }
    return []
  })
)

function stringifyToolPayload(value) {
  if (value == null) return ''
  return typeof value === 'string' ? value : JSON.stringify(value)
}

// useAutoScroll 仅提供 scrollIntoView / scrollToBottom 主动调用方法，
// 不再传 depsGetter 自动跟随（避免初始填充时滚动条被拉到底部）
const { onScroll, scrollIntoView } = useAutoScroll(scrollRef)

/** 打开/切换任务时定位到「最终输出」开头：跳过任务信息块，让用户直接看到 AI 输出 */
function scrollToLiveOutputStart() {
  scrollIntoView('.live-output-section')
}

/** 真正运行中的任务才定位到流式输出；pending/cancel_requested/终态都从顶部正常显示 */
function isRunningStatus(status) {
  return status === 'running'
}

async function loadDetail() {
  const taskId = props.task?.task_id
  if (!taskId || !props.sessionId) return
  detailTask.value = props.task
  events.value = []
  eventCursor.value = ''
  threadMessages.value = []
  threadAvailable.value = false
  detailLoading.value = true
  try {
    const [taskRes, eventRes, threadRes] = await Promise.all([
      getSubAgentRun(taskId, props.sessionId),
      getSubAgentRunEvents(taskId, props.sessionId),
      getSubAgentRunThread(taskId, props.sessionId),
    ])
    detailTask.value = taskRes.data || props.task
    applyEvents(eventRes.data)
    threadMessages.value = threadRes.data?.messages || []
    threadAvailable.value = !!threadRes.data?.available
  } finally {
    detailLoading.value = false
    // 仅运行中的任务在 DOM 渲染完成后补一次定位（已完成的不滚动）
    if (isRunningStatus(detailTask.value?.status)) {
      nextTick(() => scrollToLiveOutputStart())
    }
  }
}

function applyEvents(payload) {
  const incoming = payload?.events || []
  const known = new Set(events.value.map((item) => item.cursor))
  events.value.push(...incoming.filter((item) => !known.has(item.cursor)))
  if (payload?.next_cursor) eventCursor.value = payload.next_cursor
}

async function refreshActiveTask() {
  const taskId = props.task?.task_id
  if (!props.open || !taskId || !props.sessionId) return
  try {
    const [eventRes, taskRes, threadRes] = await Promise.all([
      getSubAgentRunEvents(taskId, props.sessionId, eventCursor.value),
      getSubAgentRun(taskId, props.sessionId),
      getSubAgentRunThread(taskId, props.sessionId),
    ])
    applyEvents(eventRes.data)
    detailTask.value = taskRes.data || detailTask.value
    threadMessages.value = threadRes.data?.messages || threadMessages.value
    threadAvailable.value = !!threadRes.data?.available
  } catch {
    // 轮询失败不关闭用户正在查看的详情。
  }
}

/** 手动刷新当前任务详情：保证刷新动画至少展示 600ms，避免闪烁 */
async function refreshDetail() {
  if (detailRefreshing.value || !props.task?.task_id) return
  detailRefreshing.value = true
  const minDelay = new Promise((resolve) => setTimeout(resolve, 600))
  try {
    await Promise.all([refreshActiveTask(), minDelay])
  } finally {
    detailRefreshing.value = false
  }
}

function startPolling() {
  stopPolling()
  if (!props.open || !props.sessionId || !props.task?.task_id) return
  refreshTimer = setInterval(refreshActiveTask, 4000)
}

function stopPolling() {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = null
}

function statusLabel(status) {
  return (
    {
      pending: '待执行',
      running: '运行中',
      completed: '已完成',
      failed: '失败',
      cancelled: '已取消',
      timeout: '超时',
      cancel_requested: '取消中',
    }[status] || '未知'
  )
}

function displayNameOf(task) {
  return task?.display_name || task?.displayName || task?.subagent_name || task?.subagentName || '子智能体'
}

function eventPayload(event) {
  try {
    return typeof event.payload === 'string' ? JSON.parse(event.payload) : event.payload || {}
  } catch {
    return {}
  }
}

function isErrorEvent(event) {
  const payload = eventPayload(event)
  return event.type === 'subagent_error' || payload.status === 'failed' || payload.status === 'cancelled'
}

function eventLabel(event) {
  const payload = eventPayload(event)
  if (event.type === 'subagent_task_done') {
    return payload.status === 'completed' ? '任务执行完成' : `任务执行结束（${statusLabel(payload.status)}）`
  }
  return (
    {
      subagent_task_start: '任务开始',
      subagent_tool_call: '调用工具',
      subagent_tool_result: '工具完成',
      subagent_token: '生成输出',
      subagent_error: '执行异常',
      subagent_error_retry: '准备重试',
    }[event.type] || event.type
  )
}

function eventColor(event) {
  if (isErrorEvent(event)) return 'red'
  if (event.type === 'subagent_task_done') return 'green'
  if (event.type === 'subagent_tool_call' || event.type === 'subagent_token') return 'blue'
  return 'gray'
}

function eventText(event) {
  const payload = eventPayload(event)
  const result = payload?.result || {}
  return (
    payload?.toolDisplayName ||
    payload?.toolName ||
    payload?.message ||
    result.error ||
    payload?.error ||
    (payload?.status ? statusLabel(payload.status) : '')
  )
}

function roleLabel(type) {
  return { system: '系统', user: '用户', assistant: 'SubAgent', tool_response: '工具' }[type] || type
}

function threadContent(item) {
  if (item.content) return item.content
  if (item.responses) return JSON.stringify(item.responses, null, 2)
  if (item.toolCalls) return JSON.stringify(item.toolCalls, null, 2)
  return ''
}

watch(
  () => [props.open, props.task?.task_id],
  ([open]) => {
    if (open && props.task?.task_id) {
      loadDetail()
      startPolling()
      // 仅运行中的任务定位到「最终输出」块（流式输出场景）；已完成的不滚动，保持顶部
      if (isRunningStatus(props.task?.status)) {
        nextTick(() => scrollToLiveOutputStart())
      }
    } else {
      stopPolling()
      detailTask.value = null
      events.value = []
      eventCursor.value = ''
      threadMessages.value = []
      threadAvailable.value = false
    }
  },
  { immediate: true }
)

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.detail-modal-header {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
}
.detail-modal-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--color-ink);
  font-size: 15px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.detail-modal-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex: 0 0 auto;
}
.detail-icon-btn {
  display: inline-flex;
  width: 26px;
  height: 26px;
  align-items: center;
  justify-content: center;
  border: 0 !important;
  color: var(--color-body);
}
.detail-icon-btn:hover {
  background: var(--color-canvas-soft) !important;
  color: var(--color-ink) !important;
}
.detail-icon-btn:disabled {
  cursor: default;
  opacity: 0.6;
}
.is-spinning {
  animation: detailSpin 0.8s linear infinite;
}
.task-detail-status {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 12px;
  white-space: nowrap;
}
.task-detail-status.is-running {
  color: #1d4ed8;
  background: #dbeafe;
  animation: runningTagPulse 1.5s ease-in-out infinite;
}
.task-detail-status.is-pending {
  color: #a16207;
  background: #fef3c7;
}
.task-detail-status.is-completed {
  color: #15803d;
  background: #dcfce7;
}
.task-detail-status.is-failed {
  color: #b91c1c;
  background: #fee2e2;
}
.task-detail-status.is-cancelled {
  color: var(--color-body);
  background: var(--color-canvas-soft-2);
}
.task-detail-scroll {
  display: flex;
  max-height: 64vh;
  flex-direction: column;
  gap: 18px;
  overflow-y: auto;
  padding: 2px 12px 8px 2px;
}
.task-detail-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--color-body);
}
.task-detail-progress {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 12px;
}
.task-detail-progress.is-pending {
  color: #a16207;
  background: #fef3c7;
}
.task-detail-progress.is-completed {
  color: #15803d;
  background: #dcfce7;
}
.task-detail-progress.is-running {
  color: #1d4ed8;
  background: #dbeafe;
  animation: runningTagPulse 1.5s ease-in-out infinite;
}
.task-detail-progress.is-failed {
  color: #b91c1c;
  background: #fee2e2;
}
.task-detail-progress.is-cancelled {
  color: var(--color-body);
  background: var(--color-canvas-soft-2);
}
.detail-section {
  min-width: 0;
}
.detail-section-title {
  margin-bottom: 8px;
  color: var(--color-ink);
  font-weight: 600;
}
.detail-section-toggle {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  font: inherit;
}
.detail-section-toggle:hover {
  color: var(--color-link);
}
.detail-section-arrow {
  color: var(--color-mute);
  font-size: 12px;
  transition: transform 0.2s ease;
}
.detail-section-arrow.expanded {
  transform: rotate(90deg);
}
.detail-section-body {
  padding-top: 4px;
}
.tool-calls-section {
  padding: 12px;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  background: var(--color-canvas-soft);
}
.task-context-section {
  padding: 12px;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  background: var(--color-canvas-soft);
}
.task-context-label {
  color: var(--color-mute);
  font-size: 12px;
  font-weight: 600;
}
.task-context-content {
  margin-top: 4px;
  color: var(--color-body);
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}
.task-time-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin-top: 10px;
  color: var(--color-mute);
  font-size: 12px;
}
.task-error {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.task-error .task-context-label {
  color: #b91c1c;
}
.live-output-section {
  padding: 12px;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  background: var(--color-canvas-soft);
}
.event-timeline {
  padding-top: 6px;
}
.event-title {
  color: var(--color-ink);
  font-size: 13px;
  font-weight: 600;
}
.event-time,
.event-text {
  margin-top: 2px;
  color: var(--color-mute);
  font-size: 12px;
}
.event-title.is-error,
.event-text.is-error {
  color: #b91c1c;
}
.thread-messages {
  display: flex;
  max-height: 360px;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
  padding-right: 8px;
}
.thread-message {
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--color-canvas-soft);
}
.thread-role {
  color: var(--color-mute);
  font-size: 12px;
  font-weight: 600;
}
.thread-content {
  margin: 5px 0 0;
  color: var(--color-body);
  font-family: inherit;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
@keyframes detailSpin {
  to {
    transform: rotate(360deg);
  }
}
@keyframes runningTagPulse {
  50% {
    opacity: 0.68;
  }
}
</style>
