<template>
  <a-drawer :open="open" title="子智能体状态" placement="right" width="380" @update:open="$emit('update:open', $event)">
    <template #extra>
      <a-tooltip title="刷新">
        <a-button
          type="text"
          size="small"
          class="runtime-refresh"
          :disabled="loading || summaryRefreshing"
          @click="refreshSummaries"
        >
          <ReloadOutlined :class="{ 'is-spinning': summaryRefreshing }" />
        </a-button>
      </a-tooltip>
    </template>

    <div v-if="runtimeRuns.length" class="runtime-list">
      <button v-for="run in runtimeRuns" :key="run.task_id" type="button" class="runtime-item" @click="openTask(run)">
        <span class="runtime-status" :class="`is-${run.status}`"></span>
        <span class="runtime-main">
          <span class="runtime-title">{{ displayNameOf(run) }}</span>
          <span class="runtime-task">{{ run.task || '未提供任务描述' }}</span>
          <span class="runtime-progress">
            {{ run.progress_summary || run.status_label || statusLabel(run.status) }}
          </span>
        </span>
        <span v-if="canCancel(run)" class="runtime-cancel-slot">
          <a-tooltip :title="isCancelling(run) ? '取消中…' : '停止子智能体'">
            <button type="button" class="runtime-cancel" :disabled="isCancelling(run)" @click.stop="cancelRun(run)">
              <a-spin v-if="isCancelling(run)" size="small" />
              <CircleStop v-else :size="16" />
            </button>
          </a-tooltip>
        </span>
        <span v-else class="runtime-status-label" :class="`is-${run.status}`">
          {{ run.status_label || statusLabel(run.status) }}
        </span>
      </button>
    </div>
    <a-empty v-else-if="!loading" description="当前会话暂无子智能体任务" />

    <SubAgentTaskDetailModal
      v-if="detailOpen"
      v-model:open="detailOpen"
      :session-id="sessionId"
      :task="selectedTask"
      :live-events="liveEvents"
    />
  </a-drawer>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch, toRef } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { CircleStop } from 'lucide-vue-next'
import SubAgentTaskDetailModal from './SubAgentTaskDetailModal.vue'
import { pickFresher } from '@/utils/subagentRuntime'
import { useSubAgentLiveState } from '@/composables/chat/useSubAgentLiveState.js'
import { getSubAgentRuntimeSummaries, cancelSubAgentTask } from '@/api/subagent'

const props = defineProps({
  open: { type: Boolean, default: false },
  sessionId: { type: [String, Number], default: null },
  liveEvents: { type: Array, default: () => [] },
})

defineEmits(['update:open'])

const runs = ref([])
const loading = ref(false)
const summaryRefreshing = ref(false)
const detailOpen = ref(false)
const selectedTask = ref(null)
let refreshTimer = null

// SubAgent 实时状态：增量维护 + RAF 批处理（避免每事件 O(N) 重算）
const { stateMap: liveTaskStateMap, reset: resetLiveState } = useSubAgentLiveState(toRef(props, 'liveEvents'))

const runtimeRuns = computed(() => {
  const merged = new Map(runs.value.map((run) => [String(run.task_id), { ...run }]))
  for (const [taskId, live] of liveTaskStateMap.value) {
    merged.set(taskId, pickFresher(merged.get(taskId), live))
  }
  return [...merged.values()].sort((a, b) => Number(b.status === 'running') - Number(a.status === 'running'))
})

async function loadSummaries() {
  if (!props.sessionId) {
    runs.value = []
    return
  }
  loading.value = true
  try {
    // 子智能体状态抽屉是会话级视图，不能被当前一轮协作任务的 taskIds 限制。
    const res = await getSubAgentRuntimeSummaries(props.sessionId, 100)
    runs.value = res.data || []
  } catch {
    // 侧栏轮询失败时保留上一份摘要，避免干扰主对话。
  } finally {
    loading.value = false
  }
}

/** 手动刷新保证图标至少旋转 500ms，避免请求过快时用户没有操作反馈。 */
async function refreshSummaries() {
  if (summaryRefreshing.value) return
  summaryRefreshing.value = true
  const minDelay = new Promise((resolve) => setTimeout(resolve, 500))
  try {
    await Promise.all([loadSummaries(), minDelay])
  } finally {
    summaryRefreshing.value = false
  }
}

const cancellingTasks = ref(new Set())

/** 侧栏可停止的状态：仅进行中/待执行/取消中允许点击停止 */
function canCancel(run) {
  return ['pending', 'running', 'cancel_requested'].includes(run?.status)
}

function isCancelling(run) {
  return run?.status === 'cancel_requested' || cancellingTasks.value.has(run?.task_id)
}

/** 手动停止一个子任务：置取消中（乐观更新），依赖轮询收敛到已取消 */
async function cancelRun(run) {
  if (!run?.task_id || !props.sessionId || isCancelling(run)) return
  cancellingTasks.value = new Set(cancellingTasks.value).add(run.task_id)
  try {
    await cancelSubAgentTask(run.task_id, props.sessionId)
    const target = runs.value.find((r) => r.task_id === run.task_id)
    if (target) target.status = 'cancel_requested'
  } catch {
    message.error('停止子智能体失败，请稍后重试')
    const next = new Set(cancellingTasks.value)
    next.delete(run.task_id)
    cancellingTasks.value = next
  }
}

/** 打开详情弹窗：加载交给共享组件 SubAgentTaskDetailModal */
function openTask(run) {
  selectedTask.value = run
  detailOpen.value = true
}

function startPolling() {
  stopPolling()
  if (!props.open || !props.sessionId) return
  refreshTimer = setInterval(loadSummaries, 4000)
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

watch(
  () => [props.open, props.sessionId],
  ([open], prev = []) => {
    // immediate 首次触发 oldValue 为 undefined，用默认空数组兜底
    // 会话切换：清空增量状态表，避免上一会话任务污染
    const prevSessionId = prev[1]
    if (prevSessionId !== undefined && props.sessionId !== prevSessionId) {
      resetLiveState()
    }
    if (open) {
      loadSummaries()
      startPolling()
    } else {
      stopPolling()
    }
  },
  { immediate: true }
)

watch(detailOpen, (open) => {
  if (!open) selectedTask.value = null
})

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.runtime-refresh {
  display: inline-flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border: 0 !important;
  color: var(--color-body);
}
.runtime-refresh:hover {
  background: var(--color-canvas-soft) !important;
  color: var(--color-ink) !important;
}
.runtime-refresh:disabled {
  cursor: default;
  opacity: 0.6;
}
.is-spinning {
  animation: runtimeSpin 0.8s linear infinite;
}
.runtime-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.runtime-cancel-slot {
  display: inline-flex;
  align-items: flex-start;
  flex: 0 0 auto;
}
.runtime-cancel {
  display: inline-flex;
  width: 26px;
  height: 26px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-hairline);
  border-radius: 6px;
  background: var(--color-canvas);
  color: var(--color-error);
  cursor: pointer;
}
.runtime-cancel:hover {
  background: #fee2e2;
  border-color: var(--color-error);
}
.runtime-cancel:disabled {
  cursor: default;
  opacity: 0.6;
}
.runtime-item {
  display: flex;
  gap: 10px;
  width: 100%;
  padding: 10px;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  background: var(--color-canvas);
  text-align: left;
  cursor: pointer;
}
.runtime-item:hover {
  background: var(--color-canvas-soft);
  border-color: var(--color-hairline-strong);
}
.runtime-status {
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 50%;
  flex: 0 0 auto;
  background: var(--color-mute);
}
.runtime-status.is-running {
  background: var(--color-link);
  box-shadow: 0 0 0 0 color-mix(in srgb, var(--color-link) 55%, transparent);
  animation: runningPulse 1.5s infinite;
}
.runtime-status.is-pending {
  background: var(--color-warning);
}
.runtime-status.is-completed {
  background: var(--color-success-500);
}
.runtime-status.is-failed {
  background: var(--color-error);
}
.runtime-status.is-cancelled {
  background: var(--color-mute);
}
.runtime-main {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}
.runtime-title {
  color: var(--color-ink);
  font-size: 14px;
  font-weight: 600;
}
.runtime-task,
.runtime-progress {
  overflow: hidden;
  color: var(--color-body);
  font-size: 12px;
  line-height: 1.45;
  word-break: break-word;
  white-space: normal;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.runtime-progress {
  color: var(--color-mute);
}
.runtime-status-label {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 12px;
  white-space: nowrap;
}
.runtime-status-label.is-running {
  color: #1d4ed8;
  background: #dbeafe;
  animation: runningTagPulse 1.5s ease-in-out infinite;
}
.runtime-status-label.is-pending {
  color: #a16207;
  background: #fef3c7;
}
.runtime-status-label.is-completed {
  color: #15803d;
  background: #dcfce7;
}
.runtime-status-label.is-failed {
  color: #b91c1c;
  background: #fee2e2;
}
.runtime-status-label.is-cancelled {
  color: var(--color-body);
  background: var(--color-canvas-soft-2);
}
@keyframes runtimeSpin {
  to {
    transform: rotate(360deg);
  }
}
@keyframes runningPulse {
  70% {
    box-shadow: 0 0 0 7px transparent;
  }
  100% {
    box-shadow: 0 0 0 0 transparent;
  }
}
@keyframes runningTagPulse {
  50% {
    opacity: 0.68;
  }
}
</style>
