<template>
  <div class="trace-steps">
    <div v-if="!visibleSteps.length" class="trace-empty">暂无节点轨迹</div>
    <template v-for="(step, i) in visibleSteps" :key="step.stepKey || i">
      <div class="trace-step" :class="{ 'trace-active': step.nodeId === activeNodeId }" @click="onStepClick(step, i)">
        <WorkflowStepRow :step="step" :step-key="i" :default-expanded="autoExpand || expandedSteps.has(i)" />
        <div
          v-if="step.isContainer && step.children?.length && expandedSteps.has(i)"
          class="trace-container-children"
          @click.stop
        >
          <WorkflowStepRow
            v-for="(child, ci) in step.children"
            :key="child.stepKey || ci"
            :step="child"
            :step-key="`c-${i}-${ci}`"
            nested
          />
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import WorkflowStepRow from '../../../components/workflow/WorkflowStepRow.vue'
import { useWorkflowNodeSteps } from '../composables/useWorkflowNodeSteps.js'
import { isHiddenInChat } from '../../../components/workflow/workflowNodeRegistry.js'

const props = defineProps({
  nodeEvents: { type: Array, default: () => [] },
  activeNodeId: { type: [String, Number], default: null },
  autoExpand: { type: Boolean, default: true },
})

const emit = defineEmits(['select-node'])

const expandedSteps = ref(new Set())
const { nodeSteps } = useWorkflowNodeSteps(() => props.nodeEvents)

const visibleSteps = computed(() => nodeSteps.value.filter((s) => !isHiddenInChat(s.nodeType) || s.isContainer))

watch(
  () => props.nodeEvents,
  (val) => {
    if (props.autoExpand && val?.length) {
      expandedSteps.value = new Set(Array.from({ length: visibleSteps.value.length }, (_, i) => i))
    }
  },
  { immediate: true }
)

function emitSelect(step) {
  if (step?.nodeId) emit('select-node', step.nodeId)
}

function onStepClick(step, i) {
  emitSelect(step)
  const next = new Set(expandedSteps.value)
  if (next.has(i)) next.delete(i)
  else next.add(i)
  expandedSteps.value = next
}
</script>

<style scoped>
.trace-steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.trace-empty {
  font-size: 12px;
  color: var(--color-mute);
  text-align: center;
  padding: 20px 0;
}
.trace-step {
  border: 1px solid var(--color-hairline);
  border-radius: 6px;
  background: var(--color-canvas);
  cursor: pointer;
  padding: 4px;
}
.trace-step.trace-active {
  border-color: #818cf8;
  background: var(--color-info-bg);
}
.trace-container-children {
  margin-left: 18px;
  padding-left: 10px;
  border-left: 2px solid #e9d5ff;
  margin-top: 4px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-bottom: 6px;
}
</style>
