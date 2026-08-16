<template>
  <div v-if="workflowEvents && workflowEvents.length > 0" class="workflow-nodes-group">
    <button type="button" class="workflow-summary" :class="{ 'is-expanded': isExpanded }" @click="toggleExpand">
      <span class="summary-icon">
        <CheckCircleOutlined v-if="summaryDone" class="icon-success" />
        <LoadingOutlined v-else-if="awaitingConfirm" class="icon-spinning icon-waiting" />
        <LoadingOutlined v-else class="icon-spinning" />
      </span>
      <span class="summary-content">
        <span class="summary-title">
          <template v-if="awaitingConfirm">工作流等待您的确认</template>
          <template v-else>
            {{ summaryDone ? `工作流已执行 ${visibleSteps.length} 个节点` : `工作流执行中 (${runningCount} 个进行中)` }}
          </template>
        </span>
        <span v-if="nodeLabels.length" class="summary-meta">{{ nodeLabels.join(' → ') }}</span>
      </span>
      <span class="summary-trailing">
        <RightOutlined :class="{ expanded: isExpanded }" class="expand-icon" />
      </span>
    </button>

    <div v-show="isExpanded" class="workflow-panel">
      <template v-for="(step, i) in visibleSteps" :key="step.stepKey || i">
        <div v-show="i < visibleCount" class="workflow-step" :style="{ animationDelay: `${i * 80}ms` }">
          <WorkflowStepRow
            :step="step"
            :step-key="i"
            :default-expanded="!!isStreaming && step.status === 'running'"
            :is-streaming="!!isStreaming"
          />
          <div v-if="step.isContainer && step.children?.length" class="container-children">
            <WorkflowStepRow
              v-for="(child, ci) in limitedChildren(step, i)"
              :key="child.stepKey || ci"
              :step="child"
              :step-key="`c-${i}-${ci}`"
              nested
            />
            <a-button
              v-if="step.children.length > childShowLimit && !showAllChildren.has(i)"
              type="link"
              size="small"
              @click="markShowAllChildren(i)"
            >
              显示全部 {{ step.children.length }} 个子步骤
            </a-button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { CheckCircleOutlined, LoadingOutlined, RightOutlined } from '@ant-design/icons-vue'
import WorkflowStepRow from './workflow/WorkflowStepRow.vue'
import { useWorkflowNodeSteps } from '../views/workflow/composables/useWorkflowNodeSteps.js'
import { isHiddenInChat } from './workflow/workflowNodeRegistry.js'

const props = defineProps({
  workflowEvents: { type: Array, default: () => [] },
  isDone: { type: Boolean, default: true },
  defaultExpanded: { type: Boolean, default: false },
  isStreaming: { type: Boolean, default: false },
})

const isExpanded = ref(props.defaultExpanded)
const visibleCount = ref(0)
const showAllChildren = ref(new Set())
const childShowLimit = 8
let revealTimer = null

const { nodeSteps } = useWorkflowNodeSteps(() => props.workflowEvents)

const visibleSteps = computed(() => nodeSteps.value.filter((s) => !isHiddenInChat(s.nodeType) || s.isContainer))

const runningCount = computed(() => {
  let count = visibleSteps.value.filter((s) => s.status === 'running' || s.status === 'suspended').length
  for (const s of visibleSteps.value) {
    if (s.children) {
      count += s.children.filter((c) => c.status === 'running').length
    }
  }
  return count
})

const nodeLabels = computed(() => visibleSteps.value.map((s) => s.nodeLabel || s.nodeType).filter(Boolean))

const awaitingConfirm = computed(() => visibleSteps.value.some((s) => s.status === 'suspended'))

const summaryDone = computed(() => props.isDone && !awaitingConfirm.value)

watch(
  () => props.defaultExpanded,
  (val) => {
    isExpanded.value = val
  },
  { immediate: true }
)

watch(
  () => [props.isDone, visibleSteps.value.length],
  ([done, len]) => {
    if (done) {
      visibleCount.value = len
      clearRevealTimer()
    } else if (len > 0) {
      visibleCount.value = len
    }
  },
  { immediate: true }
)

function markShowAllChildren(i) {
  showAllChildren.value = new Set([...showAllChildren.value, i])
}

function limitedChildren(step, idx) {
  if (showAllChildren.value.has(idx)) return step.children
  return step.children.slice(0, childShowLimit)
}

function clearRevealTimer() {
  if (revealTimer) {
    clearInterval(revealTimer)
    revealTimer = null
  }
}

function toggleExpand() {
  isExpanded.value = !isExpanded.value
  if (isExpanded.value) {
    startRevealAnimation()
  } else {
    clearRevealTimer()
    visibleCount.value = 0
  }
}

function startRevealAnimation() {
  clearRevealTimer()
  const total = visibleSteps.value.length
  if (props.isStreaming || total <= 3) {
    visibleCount.value = total
    return
  }
  visibleCount.value = 0
  revealTimer = setInterval(() => {
    if (visibleCount.value < total) visibleCount.value++
    else clearRevealTimer()
  }, 120)
}

watch(isExpanded, (val) => {
  if (val) startRevealAnimation()
  else {
    clearRevealTimer()
    visibleCount.value = 0
  }
})

onUnmounted(clearRevealTimer)
</script>

<style scoped>
.workflow-nodes-group {
  margin-bottom: 8px;
  padding: 10px 12px;
  background: var(--color-canvas-soft);
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
}

.workflow-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.summary-icon {
  flex-shrink: 0;
  font-size: 16px;
}
.icon-success {
  color: var(--green-500);
}
.icon-spinning {
  color: var(--blue-500);
  animation: spin 1s linear infinite;
}
.icon-waiting {
  color: var(--color-warning);
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.summary-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.summary-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
}
.summary-meta {
  font-size: 12px;
  color: var(--gray-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-trailing {
  flex-shrink: 0;
}
.expand-icon {
  font-size: 10px;
  color: var(--color-mute);
  transition: transform 0.2s;
}
.expand-icon.expanded {
  transform: rotate(90deg);
}

.workflow-panel {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--color-hairline);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.workflow-step {
  animation: stepFadeIn 0.3s ease-out both;
}
@keyframes stepFadeIn {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.container-children {
  margin-left: 20px;
  padding-left: 12px;
  border-left: 2px solid var(--color-hairline);
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
