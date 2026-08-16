<template>
  <div
    v-if="!hidden"
    class="workflow-step-row"
    :class="{ nested, weakened, [`status-${step.status}`]: true, [`wf-type-${step.nodeType}`]: !!step.nodeType }"
  >
    <div class="event-row" :class="statusClass" :style="rowStyle">
      <div class="event-icon-col">
        <LoadingOutlined v-if="iconType === 'running'" class="event-icon icon-spinning" />
        <PauseCircleOutlined v-else-if="iconType === 'suspended'" class="event-icon icon-suspended" />
        <CheckCircleOutlined v-else-if="iconType === 'done'" class="event-icon icon-success" />
        <CloseCircleOutlined v-else-if="iconType === 'failed'" class="event-icon icon-fail" />
        <PlayCircleOutlined v-else class="event-icon start" />
      </div>
      <div class="event-main">
        <div class="event-head" @click="toggle">
          <span class="event-label">
            <strong>{{ step.nodeLabel || typeLabel }}</strong>
            <span class="event-type-tag">{{ typeLabel }}</span>
            <span v-if="step.isContainer && step.children?.length" class="event-child-count">
              {{ step.children.length }} 个子节点
            </span>
            <span v-if="step.iterationIndex != null" class="event-iteration-tag">#{{ step.iterationIndex + 1 }}</span>
          </span>
          <span class="event-summary">{{ summary }}</span>
          <span v-if="step.durationMs != null" class="event-duration">{{ step.durationMs }}ms</span>
          <RightOutlined v-if="expandable" :class="{ expanded: expanded }" class="step-toggle-icon" />
        </div>
        <WorkflowStepResilienceAlerts
          v-if="step.resilienceEvents?.length"
          :step="step"
          :is-streaming="isStreaming && step.status === 'running'"
        />
        <div v-show="expanded && expandable" class="step-detail-body">
          <WorkflowStepDetail :step="step" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import {
  CheckCircleOutlined,
  LoadingOutlined,
  RightOutlined,
  PlayCircleOutlined,
  CloseCircleOutlined,
  PauseCircleOutlined,
} from '@ant-design/icons-vue'
import WorkflowStepDetail from './WorkflowStepDetail.vue'
import WorkflowStepResilienceAlerts from './WorkflowStepResilienceAlerts.vue'
import {
  stepStatusClass,
  stepStatusIcon,
  getStepSummary,
  hasExpandableStepContent,
  isHiddenInChat,
  isWeakenedInChat,
  getNodeTypeLabel,
  getNodeChatStyle,
} from './workflowNodeRegistry.js'
import { hasWorkflowToolRenderer } from './workflowStepUtils.js'

const props = defineProps({
  step: { type: Object, required: true },
  stepKey: { type: [String, Number], default: '' },
  nested: { type: Boolean, default: false },
  defaultExpanded: { type: Boolean, default: false },
  isStreaming: { type: Boolean, default: false },
})

const expanded = ref(props.defaultExpanded || shouldAutoExpandToolStep(props.step))

watch(
  () => props.defaultExpanded,
  (v) => {
    if (v) expanded.value = true
  }
)

watch(
  () => props.step,
  (step) => {
    if (shouldAutoExpandToolStep(step)) expanded.value = true
  },
  { deep: true }
)

function shouldAutoExpandToolStep(step) {
  if (!step) return false
  if (step.status === 'running') return props.defaultExpanded
  return (step.nodeType === 'tool' || step.nodeType === 'mcp') && hasWorkflowToolRenderer(step)
}

const hidden = computed(() => isHiddenInChat(props.step?.nodeType))
const weakened = computed(() => isWeakenedInChat(props.step?.nodeType))
const statusClass = computed(() => stepStatusClass(props.step))
const iconType = computed(() => stepStatusIcon(props.step))
const typeLabel = computed(() => getNodeTypeLabel(props.step?.nodeType))
const summary = computed(() => getStepSummary(props.step))
const expandable = computed(() => hasExpandableStepContent(props.step))
const rowStyle = computed(() => {
  const style = getNodeChatStyle(props.step?.nodeType)
  return {
    borderLeft: `3px solid ${style.color}`,
    '--wf-accent': style.color,
    '--wf-bg': style.bg,
    '--wf-border': style.border,
  }
})

function toggle() {
  if (!expandable.value) return
  expanded.value = !expanded.value
}
</script>

<style scoped>
.workflow-step-row {
  font-size: 13px;
}
.workflow-step-row.weakened .event-row {
  opacity: 0.88;
  padding: 6px 10px;
}
.workflow-step-row.weakened .event-summary {
  font-size: 10px;
}
.workflow-step-row.nested .event-row {
  padding: 6px 8px;
  background: var(--wf-bg, var(--color-purple-bg));
  border-color: var(--wf-border, var(--color-purple-border));
}
.event-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--color-canvas);
  transition: border-color 0.2s;
  border: 1px solid var(--color-border-slate);
}
.event-running {
  border-color: var(--color-purple-border);
  background: var(--color-purple-bg);
}
.event-start {
  border-color: var(--wf-border, var(--color-purple-border));
}
.event-done {
  border-color: var(--green-200);
}
.event-fail {
  border: 1px solid var(--color-error-soft);
  background: var(--color-error-bg);
}
.event-suspended {
  border-color: var(--color-warning-soft);
  background: var(--color-warn-bg);
}
.event-icon-col {
  flex-shrink: 0;
  margin-top: 2px;
}
.event-icon {
  font-size: 14px;
}
.event-icon.start {
  color: var(--wf-accent, var(--blue-500));
}
.event-icon.icon-success {
  color: var(--green-500);
}
.event-icon.icon-fail {
  color: var(--color-error);
}
.event-icon.icon-suspended {
  color: var(--color-warning);
}
.icon-spinning {
  color: var(--wf-accent, var(--blue-500));
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.event-main {
  flex: 1;
  min-width: 0;
}
.event-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  cursor: pointer;
  user-select: none;
  border-radius: 4px;
  padding: 2px 4px;
  margin: -2px -4px;
}
.event-head:hover {
  background: var(--gray-50);
}
.event-label {
  flex: 1;
  min-width: 0;
  color: var(--color-text-dark);
  line-height: 1.5;
}
.event-type-tag {
  margin-left: 6px;
  font-size: 11px;
  font-weight: normal;
  color: var(--color-mute);
}
.event-summary {
  font-size: 11px;
  color: var(--wf-accent, var(--blue-500));
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.event-duration {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--color-mute);
  font-variant-numeric: tabular-nums;
}
.step-toggle-icon {
  font-size: 10px;
  color: var(--color-mute);
  transition: transform 0.2s;
  flex-shrink: 0;
}
.step-toggle-icon.expanded {
  transform: rotate(90deg);
}
.step-detail-body {
  margin-top: 6px;
}
.event-child-count {
  margin-left: 6px;
  font-size: 11px;
  font-weight: normal;
  color: var(--wf-accent, var(--blue-500));
  background: var(--color-purple-bg);
  padding: 1px 6px;
  border-radius: 8px;
}
.event-iteration-tag {
  margin-left: 4px;
  font-size: 10px;
  font-weight: normal;
  color: var(--color-link);
  background: var(--color-info-bg);
  padding: 1px 5px;
  border-radius: 6px;
}
</style>
