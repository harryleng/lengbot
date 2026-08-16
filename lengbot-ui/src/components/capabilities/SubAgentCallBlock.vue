<template>
  <div
    class="subagent-call-block"
    :class="{
      'is-retrying': isActivelyRetrying,
      'is-failed': !!activeError && !isActivelyRetrying,
      'retry-pulse': retryPulseActive,
    }"
  >
    <button type="button" class="subagent-header" @click="toggle($event)">
      <component :is="headerIcon" class="subagent-icon" />
      <span class="subagent-title">
        委派 SubAgent：
        <strong>{{ subagentTitle }}</strong>
        <span v-if="attemptViews.length > 1" class="subagent-attempt-count">（{{ attemptViews.length }} 次委派）</span>
        <span v-if="taskStatusSummary" class="subagent-task-summary">{{ taskStatusSummary }}</span>
      </span>
      <span v-if="activeRetry" class="subagent-header-badge retry">
        <LoadingOutlined spin class="subagent-step-icon" />
        重试 {{ activeRetry.attempt }}/{{ activeRetry.maxRetries }}
      </span>
      <span v-else-if="activeError" class="subagent-header-badge error">
        <CloseCircleOutlined class="subagent-step-icon" />
        {{ activeErrorLabel || '执行失败' }}
      </span>
      <LoadingOutlined v-else-if="!blockDone && !hasAnyModelOutput" class="subagent-spinner" spin />
      <RightOutlined :class="{ expanded: expanded }" class="subagent-toggle" />
    </button>

    <div v-if="activeRetry" class="subagent-status-banner retry">
      <LoadingOutlined spin class="subagent-step-icon" />
      <span class="subagent-status-text">{{ activeRetry.message || 'SubAgent 连接异常，正在重试' }}</span>
      <span class="subagent-retry-count">{{ activeRetry.attempt }}/{{ activeRetry.maxRetries }}</span>
    </div>
    <div v-else-if="activeError" class="subagent-status-banner error">
      <CloseCircleOutlined class="subagent-step-icon error" />
      <span class="subagent-status-text">{{ activeError.message }}</span>
      <span v-if="activeErrorLabel" class="subagent-error-code">{{ activeErrorLabel }}</span>
      <span v-else-if="activeError.code" class="subagent-error-code">{{ activeError.code }}</span>
    </div>

    <CollapseTransition :open="expanded">
      <div ref="bodyRef" class="subagent-body" @scroll.passive="onBodyScroll">
        <div
          v-for="(attempt, ai) in attemptViews"
          :key="attempt.key"
          class="subagent-attempt"
          :class="{
            'is-active': attempt.isActive,
            'is-done': attempt.isDone,
            'is-failed': attempt.error && attempt.isDone,
          }"
        >
          <div v-if="attemptViews.length > 1" class="subagent-attempt-header" @click="toggleAttempt(attempt, $event)">
            <component :is="attemptIcon(attempt)" class="subagent-attempt-icon" />
            <span class="subagent-attempt-label">{{ attemptLabel(attempt, ai) }}</span>
            <span v-if="attempt.isDone && attempt.error" class="subagent-attempt-status error">失败</span>
            <span v-else-if="attempt.isDone && attempt.success" class="subagent-attempt-status success">完成</span>
            <span v-else-if="attempt.isDone" class="subagent-attempt-status error">失败</span>
            <span v-else-if="attempt.activeRetry" class="subagent-attempt-status retry">
              <LoadingOutlined spin />
              重试中
            </span>
            <span v-else class="subagent-attempt-status running">
              <LoadingOutlined v-if="!attempt.isDone" spin />
              进行中
            </span>
            <RightOutlined :class="{ expanded: isAttemptExpanded(attempt) }" class="subagent-attempt-toggle" />
          </div>

          <CollapseTransition :open="isAttemptExpanded(attempt)">
            <div class="subagent-attempt-body">
              <div v-if="visibleAttemptTimeline(attempt).length" class="subagent-timeline">
                <div
                  v-for="item in visibleAttemptTimeline(attempt)"
                  :key="item.key"
                  class="subagent-timeline-item"
                  :class="`kind-${item.kind}`"
                >
                  <LoadingOutlined
                    v-if="item.kind === 'retry' || item.kind === 'running'"
                    spin
                    class="subagent-step-icon"
                  />
                  <CheckCircleOutlined v-else-if="item.kind === 'success'" class="subagent-step-icon success" />
                  <CloseCircleOutlined v-else-if="item.kind === 'error'" class="subagent-step-icon error" />
                  <span class="subagent-timeline-text">
                    <template v-if="item.kind === 'retry'">
                      重试 {{ item.attempt }}/{{ item.maxRetries }}：{{ item.message || '连接异常，正在重试' }}
                    </template>
                    <template v-else>{{ item.message }}</template>
                  </span>
                </div>
              </div>

              <div v-if="attempt.task" class="subagent-section">
                <div class="subagent-section-header">
                  <span class="subagent-label">任务</span>
                </div>
                <div class="subagent-task-box">{{ attempt.task }}</div>
              </div>

              <ToolCallsGroupComponent
                v-if="attempt.toolEvents.length"
                class="subagent-tools"
                :tool-events="attempt.toolEvents"
                :is-done="attempt.toolsDone"
                :default-expanded="attempt.isActive"
                :message-index="-1"
                @height-change="onToolHeightChange"
              />

              <div v-if="attempt.modelOutput" class="subagent-section">
                <div class="subagent-section-header">
                  <span class="subagent-label">模型输出</span>
                  <div v-if="attempt.canViewResultJson" class="subagent-section-actions">
                    <a-tooltip title="查看返回 JSON">
                      <button type="button" class="subagent-action-btn icon-only" @click.stop="openJsonModal(attempt)">
                        <CodeOutlined />
                      </button>
                    </a-tooltip>
                  </div>
                </div>
                <div class="subagent-markdown">
                  <MarkdownPreview :content="attempt.modelOutput" :finalized="attempt.isDone" :image-preview="false" />
                </div>
              </div>
            </div>
          </CollapseTransition>
        </div>
      </div>
    </CollapseTransition>

    <a-modal
      v-model:open="jsonModalOpen"
      :footer="null"
      width="680px"
      :body-style="{ maxHeight: '70vh', overflow: 'auto' }"
      destroy-on-close
    >
      <template #title>
        <span>SubAgent 返回 JSON</span>
        <a-tooltip title="复制">
          <button class="subagent-modal-btn" @click="copyText(jsonModalContent)">
            <CheckOutlined v-if="jsonCopied" style="color: #16a34a" />
            <CopyOutlined v-else />
          </button>
        </a-tooltip>
      </template>
      <div class="dialog-scroll-body">
        <pre class="subagent-modal-pre">{{ jsonModalContent }}</pre>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { message } from 'ant-design-vue'
import { copyToClipboard } from '../../utils/clipboard'
import {
  RobotOutlined,
  LoadingOutlined,
  RightOutlined,
  CloseCircleOutlined,
  CodeOutlined,
  CopyOutlined,
  CheckOutlined,
  CheckCircleOutlined,
} from '@ant-design/icons-vue'
import { resolveIcon } from '@/utils/iconRegistry'
import ToolCallsGroupComponent from '../ToolCallsGroupComponent.vue'
import MarkdownPreview from '../MarkdownPreview.vue'
import CollapseTransition from '../common/CollapseTransition.vue'
import {
  normalizeSubagentCalls,
  buildSubagentAttemptTimeline,
  formatSubagentErrorLabel,
  resolveSubagentModelOutput,
  findSubagentResultRawJson,
  hasSubagentResultJson,
  mapSubagentToolsToStandardEvents,
  findSubagentError,
  findSubagentErrorRetry,
  isSubagentAttemptDone,
  isSubagentAttemptSuccessful,
  isSubagentBlockDone,
} from './subagentEventUtils.js'

const props = defineProps({
  event: { type: Object, required: true },
  calls: { type: Array, default: null },
  events: { type: Array, default: () => [] },
  allEvents: { type: Array, default: null },
  eventIndex: { type: Number, default: 0 },
  isDone: { type: Boolean, default: true },
  streamFinished: { type: Boolean, default: true },
  defaultExpanded: { type: Boolean, default: true },
})

const emit = defineEmits(['heightChange'])

const expanded = ref(props.defaultExpanded)
const bodyRef = ref(null)
const userReadingInnerHistory = ref(false)
let userToggled = false

// 多任务时每个任务独立展开/收起，默认收起（key -> boolean）
const attemptExpandedMap = ref({})

function isAttemptExpanded(attempt) {
  // 单任务：内容随外层块展开；多任务：按 key 记录，默认收起
  if (attemptViews.value.length <= 1) return true
  return attemptExpandedMap.value[attempt.key] === true
}

function toggleAttempt(attempt, event) {
  attemptExpandedMap.value = {
    ...attemptExpandedMap.value,
    [attempt.key]: !isAttemptExpanded(attempt),
  }
  emitLayoutChange(event, true)
}

const jsonModalOpen = ref(false)
const jsonModalContent = ref('')
const jsonCopied = ref(false)
const retryPulseActive = ref(false)
let retryPulseTimer = null

const isBatchEvent = computed(() => props.event?.type === 'subagent_batch_start')
const scopedEvents = computed(() =>
  (props.allEvents || props.events || []).map((event) => {
    // 批次协议复用原有 subagent_result 语义，避免出现第二套渲染和状态机。
    if (event.type === 'subagent_task_done') {
      return {
        ...event,
        type: 'subagent_result',
        result: typeof event.result === 'string' ? event.result : JSON.stringify(event.result || event),
      }
    }
    return event
  })
)
const callList = computed(() => {
  if (props.calls?.length) return props.calls
  if (isBatchEvent.value) {
    return (props.event.tasks || []).map((task, taskIndex) => {
      const streamEvent = scopedEvents.value.find(
        (event) => event.task_id === task.task_id && event.subagentName === task.subagent_name
      )
      return {
        type: 'subagent_call',
        batch_id: props.event.batch_id,
        task_id: task.task_id,
        taskIndex: task.task_index ?? taskIndex,
        subagentName: task.subagent_name,
        displayName: streamEvent?.displayName || task.subagent_name,
        icon: streamEvent?.icon || task.icon,
        task: task.task,
        contentOffset: props.event.contentOffset,
        delegationIndex: props.event.delegationIndex,
      }
    })
  }
  return props.event ? [props.event] : []
})

const blockDone = computed(() => isSubagentBlockDone(scopedEvents.value, callList.value, !props.streamFinished))

const attemptViews = computed(() => {
  const streaming = !props.streamFinished
  const normalized = normalizeSubagentCalls(callList.value)
  const lastActiveIndex = normalized.findIndex(
    ({ call, delegationIndex }) =>
      !isSubagentAttemptDone(scopedEvents.value, call, delegationIndex, streaming, callList.value)
  )
  // 批次多任务全部完成后无「当前任务」概念，不再兜底高亮最后一个；重试模型仍兜底到最后一次尝试。
  const activeIndex = lastActiveIndex >= 0 ? lastActiveIndex : isBatchEvent.value ? -1 : normalized.length - 1

  return normalized.map(({ call, delegationIndex }, index) => {
    const blockCalls = callList.value
    const error = findSubagentError(scopedEvents.value, call, delegationIndex, blockCalls)
    const activeRetry =
      !isSubagentAttemptDone(scopedEvents.value, call, delegationIndex, streaming, blockCalls) &&
      !resolveSubagentModelOutput(scopedEvents.value, call, delegationIndex, blockCalls)?.trim() &&
      !error
        ? findSubagentErrorRetry(scopedEvents.value, call, delegationIndex, blockCalls)
        : null
    const modelOutput = resolveSubagentModelOutput(scopedEvents.value, call, delegationIndex, blockCalls)
    const isDone = isSubagentAttemptDone(scopedEvents.value, call, delegationIndex, streaming, blockCalls)
    const success = isSubagentAttemptSuccessful(scopedEvents.value, call, delegationIndex, blockCalls)
    return {
      key: call.task_id || `${call.subagentName}-${call.contentOffset}-${delegationIndex ?? index}`,
      call,
      delegationIndex,
      task: call.task,
      error,
      activeRetry,
      modelOutput,
      isDone,
      success,
      isActive: index === activeIndex,
      timeline: buildSubagentAttemptTimeline(scopedEvents.value, call, delegationIndex, streaming, blockCalls),
      toolEvents: mapSubagentToolsToStandardEvents(scopedEvents.value, call, delegationIndex, blockCalls),
      toolsDone: isDone || !!modelOutput?.trim() || !!error,
      canViewResultJson:
        props.streamFinished && hasSubagentResultJson(scopedEvents.value, call, delegationIndex, blockCalls),
      resultRawJson: findSubagentResultRawJson(scopedEvents.value, call, delegationIndex, blockCalls),
    }
  })
})

const subagentTitle = computed(() =>
  isBatchEvent.value
    ? `${modeLabel(props.event.mode)} · ${callList.value.length} 个任务`
    : props.event.displayName || props.event.subagentName
)

/** 头部图标：单任务委派用该 SubAgent 配置图标；多任务批次用默认机器人图标 */
const headerIcon = computed(() => {
  if (isBatchEvent.value) {
    // 单任务批次直接展示该 SubAgent 图标；多任务保留机器人图标
    const calls = callList.value
    if (calls.length === 1) {
      const iconName = calls[0]?.icon
      return resolveIcon(iconName) || RobotOutlined
    }
    return RobotOutlined
  }
  const iconName = props.event.icon
  return resolveIcon(iconName) || RobotOutlined
})

const activeAttempt = computed(
  () => attemptViews.value.find((a) => a.isActive) || attemptViews.value[attemptViews.value.length - 1]
)

const activeRetry = computed(() => {
  if (blockDone.value) return null
  const attempt = activeAttempt.value
  if (!attempt || attempt.error || attempt.modelOutput?.trim()) return null
  return attempt.activeRetry
})

const activeError = computed(() => {
  if (isActivelyRetrying.value) return null
  const attempt = activeAttempt.value
  if (attempt?.error) return attempt.error
  if (!blockDone.value) return null
  for (let i = attemptViews.value.length - 1; i >= 0; i--) {
    const err = attemptViews.value[i].error
    if (err) return err
  }
  return null
})

const activeErrorLabel = computed(() => formatSubagentErrorLabel(activeError.value?.code))
const isActivelyRetrying = computed(() => !!activeRetry.value)
const hasAnyModelOutput = computed(() => attemptViews.value.some((a) => a.modelOutput?.trim()))

const taskStatusSummary = computed(() => {
  if (!isBatchEvent.value || attemptViews.value.length === 0) return ''
  const running = attemptViews.value.filter((attempt) => !attempt.isDone).length
  const failed = attemptViews.value.filter((attempt) => attempt.isDone && !attempt.success).length
  const completed = attemptViews.value.filter((attempt) => attempt.isDone && attempt.success).length
  const parts = []
  if (running) parts.push(`进行中 ${running}`)
  if (completed) parts.push(`完成 ${completed}`)
  if (failed) parts.push(`失败 ${failed}`)
  return parts.join(' · ')
})

/** 顶部 banner 已展示终态错误时，时间线不再重复 error；失败后也不再展示重试记录 */
function visibleAttemptTimeline(attempt) {
  const timeline = attempt?.timeline || []
  if (!timeline.length) return []
  let visible = timeline
  if (attempt.isDone && attempt.error) {
    visible = visible.filter((item) => item.kind !== 'retry')
  }
  const shownInBanner = !!activeError.value && attempt.error && (attemptViews.value.length === 1 || attempt.isActive)
  if (!shownInBanner) return visible
  return visible.filter((item) => item.kind !== 'error')
}

function modeLabel(mode) {
  return { sync: '同步委派', parallel: '并行委派', background: '后台委派' }[mode] || '批次委派'
}

function attemptLabel(attempt, index) {
  if (!isBatchEvent.value) return `第 ${index + 1} 次委派`
  return `任务 ${index + 1} · ${attempt.call.displayName || attempt.call.subagentName || 'SubAgent'}`
}

/** 单个委派任务的图标：优先用 SubAgent 配置图标，回退机器人图标 */
function attemptIcon(attempt) {
  const iconName = attempt?.call?.icon
  return resolveIcon(iconName) || RobotOutlined
}

watch(
  () => props.defaultExpanded,
  (val) => {
    if (!userToggled) expanded.value = val
    if (val && !userToggled && !props.streamFinished) scrollInnerBodyToBottom()
  },
  { immediate: true }
)

watch(activeRetry, (val, oldVal) => {
  if (!val) {
    retryPulseActive.value = false
    if (retryPulseTimer) {
      clearTimeout(retryPulseTimer)
      retryPulseTimer = null
    }
    return
  }
  if (!oldVal || val.attempt !== oldVal.attempt) {
    triggerRetryPulse()
    expanded.value = true
    emitLayoutChange()
  }
})

watch(activeError, (err) => {
  if (!err) return
  retryPulseActive.value = false
  if (retryPulseTimer) {
    clearTimeout(retryPulseTimer)
    retryPulseTimer = null
  }
})

watch(
  [activeRetry, activeError, attemptViews, blockDone],
  () => {
    if (activeRetry.value || activeError.value || attemptViews.value.length || blockDone.value) {
      if (!userToggled) expanded.value = true
      if (!props.streamFinished) {
        scrollInnerBodyToBottom()
        emitLayoutChange()
      }
    }
  },
  { deep: true }
)

watch(
  () => attemptViews.value.map((a) => a.modelOutput).join('\u0000'),
  () => {
    if (!props.streamFinished && expanded.value) scrollInnerBodyToBottom()
  }
)

watch(
  scopedEvents,
  () => {
    if (!props.streamFinished && expanded.value) scrollInnerBodyToBottom()
  },
  { deep: true }
)

function triggerRetryPulse() {
  retryPulseActive.value = false
  nextTick(() => {
    retryPulseActive.value = true
    if (retryPulseTimer) clearTimeout(retryPulseTimer)
    retryPulseTimer = setTimeout(() => {
      retryPulseActive.value = false
    }, 1800)
  })
}

function emitLayoutChange(nativeEvent, preserveViewport = false) {
  nextTick(() => {
    const rowEl = nativeEvent?.target?.closest?.('[data-index]') || bodyRef.value?.closest?.('[data-index]')
    emit('heightChange', rowEl ? { target: rowEl, preserveViewport } : nativeEvent)
  })
}

function scrollInnerBodyToBottom() {
  nextTick(() => {
    const el = bodyRef.value
    if (el && expanded.value && !userReadingInnerHistory.value) el.scrollTop = el.scrollHeight
  })
}

function onBodyScroll(event) {
  const el = event.currentTarget
  userReadingInnerHistory.value = el.scrollHeight - el.scrollTop - el.clientHeight > 24
}

function onToolHeightChange(event) {
  // 工具栏由用户主动展开/收起时，仅同步高度；不能把内部滚动位置强制推到底部。
  emitLayoutChange(event, true)
}

watch(expanded, (val, oldVal) => {
  if (val && !oldVal && !props.streamFinished) scrollInnerBodyToBottom()
})

function toggle(event) {
  userToggled = true
  expanded.value = !expanded.value
  emitLayoutChange(event, true)
}

function openJsonModal(attempt) {
  jsonModalContent.value = attempt.resultRawJson || ''
  jsonModalOpen.value = true
}

async function copyText(text) {
  if (!text) return
  const ok = await copyToClipboard(text)
  if (ok) {
    jsonCopied.value = true
    setTimeout(() => {
      jsonCopied.value = false
    }, 2000)
    message.success('已复制')
  } else {
    message.error('复制失败')
  }
}
</script>

<style scoped>
.subagent-call-block {
  border-radius: 10px;
  border: 1px solid var(--color-warning-soft);
  background: var(--color-warn-bg);
  overflow: hidden;
  margin-top: 4px;
  transition:
    box-shadow 0.25s ease,
    border-color 0.25s ease;
}
.subagent-call-block.is-retrying,
.subagent-call-block.retry-pulse {
  border-color: color-mix(in srgb, var(--color-warning) 55%, transparent);
  animation:
    errorFadeIn 0.3s ease,
    retryPulse 1.6s ease-in-out infinite;
}
.subagent-call-block.is-failed {
  border-color: color-mix(in srgb, var(--color-error) 40%, transparent);
  background: var(--color-error-bg);
}
.subagent-header {
  appearance: none;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-size: 14px;
  color: var(--color-text-dark);
}
.subagent-icon {
  font-size: 16px;
  color: var(--color-warning);
  flex-shrink: 0;
}
.subagent-title {
  flex: 1;
  min-width: 0;
  line-height: 1.5;
}
.subagent-title strong {
  font-weight: 600;
  color: var(--color-warning-deep);
}
.subagent-attempt-count {
  font-size: 12px;
  color: var(--color-mute);
  font-weight: normal;
  margin-left: 4px;
}
.subagent-task-summary {
  font-size: 12px;
  color: var(--color-mute);
  font-weight: normal;
  margin-left: 6px;
}
.subagent-spinner {
  color: var(--color-mute);
  font-size: 14px;
}
.subagent-header-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
  flex-shrink: 0;
}
.subagent-header-badge.retry {
  color: var(--color-warning-deep);
  background: color-mix(in srgb, var(--color-warning) 18%, transparent);
}
.subagent-header-badge.error {
  color: var(--color-error-deep);
  background: color-mix(in srgb, var(--color-error) 14%, transparent);
}
.subagent-toggle {
  font-size: 11px;
  color: var(--color-mute);
  transition: transform 0.2s;
  flex-shrink: 0;
}
.subagent-toggle.expanded {
  transform: rotate(90deg);
}
.subagent-status-banner {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0 16px 12px;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
}
.subagent-status-banner.retry {
  background: color-mix(in srgb, var(--color-warning) 16%, transparent);
  color: var(--color-warning-deep);
  animation:
    errorFadeIn 0.3s ease,
    retryPulse 1.6s ease-in-out infinite;
}
.subagent-status-banner.error {
  background: color-mix(in srgb, var(--color-error) 12%, transparent);
  color: var(--color-error-deep);
}
.subagent-status-text {
  flex: 1;
  word-break: break-word;
}
.subagent-body {
  padding: 4px 16px 16px 16px;
  padding-right: calc(16px + var(--scroll-content-gap, 8px));
  font-size: 14px;
  color: var(--color-body);
  max-height: 520px;
  overflow-y: auto;
  scrollbar-gutter: stable;
}
.subagent-attempt {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed color-mix(in srgb, var(--color-warning) 28%, transparent);
}
.subagent-attempt:first-child {
  margin-top: 4px;
  padding-top: 0;
  border-top: none;
}
.subagent-attempt.is-active {
  border-left: 3px solid color-mix(in srgb, var(--color-warning) 45%, transparent);
  padding-left: 10px;
  margin-left: -2px;
}
.subagent-attempt-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  user-select: none;
  border-radius: 6px;
  padding: 4px 6px;
  margin-left: -6px;
  margin-right: -6px;
  transition: background 0.15s ease;
}
.subagent-attempt-header:hover {
  background: color-mix(in srgb, var(--color-warning) 8%, transparent);
}
.subagent-attempt-toggle {
  font-size: 11px;
  color: var(--color-mute);
  transition: transform 0.2s;
  flex-shrink: 0;
  margin-left: auto;
}
.subagent-attempt-toggle.expanded {
  transform: rotate(90deg);
}
.subagent-attempt-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-warning-deep);
}
.subagent-attempt-icon {
  font-size: 13px;
  color: var(--color-warning);
  flex-shrink: 0;
}
.subagent-attempt-status {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
}
.subagent-attempt-status.success {
  color: #15803d;
  background: rgba(34, 197, 94, 0.18);
}
.subagent-attempt-status.error {
  color: var(--color-error-deep);
  background: color-mix(in srgb, var(--color-error) 14%, transparent);
}
.subagent-attempt-status.retry,
.subagent-attempt-status.running {
  color: var(--color-warning-deep);
  background: color-mix(in srgb, var(--color-warning) 16%, transparent);
}
.subagent-timeline {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 10px;
}
.subagent-timeline-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
  line-height: 1.5;
  padding: 6px 10px;
  border-radius: 6px;
  background: var(--color-canvas-soft);
  animation: errorFadeIn 0.25s ease;
}
.subagent-timeline-item.kind-retry,
.subagent-timeline-item.kind-running {
  color: var(--color-warning-deep);
  background: color-mix(in srgb, var(--color-warning) 14%, transparent);
}
.subagent-timeline-item.kind-error {
  color: var(--color-error-deep);
  background: color-mix(in srgb, var(--color-error) 10%, transparent);
}
.subagent-timeline-item.kind-success {
  color: #15803d;
  background: rgba(34, 197, 94, 0.15);
}
.subagent-timeline-text {
  flex: 1;
  word-break: break-word;
}
.subagent-section {
  margin-top: 14px;
}
.subagent-section:first-child {
  margin-top: 4px;
}
.subagent-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.subagent-label {
  font-weight: 600;
  font-size: 13px;
  color: var(--color-mute);
}
.subagent-section-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.subagent-action-btn {
  appearance: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid color-mix(in srgb, var(--color-warning) 28%, transparent);
  border-radius: 6px;
  background: var(--color-canvas-soft);
  color: var(--color-warning-deep);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.subagent-action-btn:hover {
  background: var(--color-canvas-soft-2);
  border-color: color-mix(in srgb, var(--color-warning) 45%, transparent);
}
.subagent-action-btn.icon-only {
  padding: 4px 8px;
  font-size: 14px;
}
.subagent-task-box {
  padding: 10px 12px;
  background: var(--color-canvas-soft);
  border-radius: 8px;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.65;
  color: var(--color-body);
}
.subagent-markdown {
  padding: 10px 12px;
  background: var(--color-canvas-soft);
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.7;
}
.subagent-markdown :deep(.markdown-preview) {
  font-size: 14px;
  line-height: 1.7;
  color: var(--color-ink);
}
.subagent-tools {
  margin-top: 10px;
}
.subagent-tools :deep(.tool-calls-group) {
  margin-top: 0;
  background: var(--color-canvas-soft);
  font-size: 14px;
}
.subagent-step-icon {
  font-size: 12px;
}
.subagent-step-icon.error {
  color: var(--color-error);
}
.subagent-step-icon.success {
  color: #16a34a;
}
.subagent-error-code {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--color-canvas-soft-2);
  color: var(--color-mute);
  flex-shrink: 0;
}
.subagent-retry-count {
  font-size: 12px;
  color: var(--color-mute);
  flex-shrink: 0;
  font-family: var(--font-mono);
}
.subagent-modal-pre {
  margin: 0;
  padding: 0;
  background: none;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'Menlo', 'Monaco', 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.7;
  color: var(--color-text-code);
}
.subagent-modal-btn {
  appearance: none;
  border: none;
  background: none;
  color: var(--gray-400);
  font-size: 14px;
  cursor: pointer;
  padding: 2px 6px;
  margin-left: 8px;
  border-radius: 4px;
  vertical-align: middle;
  transition: all 0.15s;
}
.subagent-modal-btn:hover {
  color: var(--main-600);
  background: var(--gray-100);
}
@keyframes errorFadeIn {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
@keyframes retryPulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 color-mix(in srgb, var(--color-warning) 0%, transparent);
  }
  50% {
    box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-warning) 14%, transparent);
  }
}
</style>

<style>
[data-theme='dark'] .subagent-markdown :deep(.markdown-preview) :not(pre) > code {
  background-color: var(--color-canvas-soft-2);
}
[data-theme='dark'] .subagent-markdown :deep(.markdown-preview pre) {
  background: var(--color-canvas-soft-2);
  border-color: var(--color-hairline);
}
[data-theme='dark'] .subagent-tools :deep(.tool-calls-summary) {
  background: var(--color-canvas-soft-2);
  border-color: var(--color-hairline);
  color: var(--color-body);
}
[data-theme='dark'] .subagent-tools :deep(.tool-calls-summary:hover),
[data-theme='dark'] .subagent-tools :deep(.tool-calls-summary.is-expanded) {
  background: var(--color-canvas-soft-3);
  border-color: var(--color-hairline-strong);
  color: var(--color-ink);
}
[data-theme='dark'] .subagent-attempt-status.success,
[data-theme='dark'] .subagent-timeline-item.kind-success {
  color: var(--green-400);
  background: rgba(34, 197, 94, 0.16);
}
[data-theme='dark'] .subagent-step-icon.success {
  color: var(--green-400);
}
</style>
