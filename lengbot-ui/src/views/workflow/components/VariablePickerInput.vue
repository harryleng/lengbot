<template>
  <div class="variable-picker-input">
    <a-input :value="modelValue" :placeholder="placeholder" :disabled="disabled" @update:value="onInput" />
    <a-dropdown :disabled="disabled" trigger="click">
      <a-button type="link" size="small" class="var-pick-btn">变量</a-button>
      <template #overlay>
        <a-menu class="builtin-var-menu" @click="onPick">
          <a-menu-item-group title="内置变量">
            <a-menu-item v-for="v in BUILTIN_VARIABLES" :key="'builtin-' + v.key">
              <div class="var-menu-item">
                <code>{{ v.example }}</code>
                <span class="var-menu-label">{{ v.label }}</span>
              </div>
              <div class="var-menu-desc">{{ v.desc }}</div>
            </a-menu-item>
          </a-menu-item-group>
          <a-menu-item-group v-if="upstreamItems.length" title="上游节点输出">
            <a-menu-item v-for="v in upstreamItems" :key="'upstream-' + v.key">
              <div class="var-menu-item">
                <code>{{ v.example }}</code>
                <span class="var-menu-label">{{ v.label }}</span>
              </div>
              <div v-if="v.desc" class="var-menu-desc">{{ v.desc }}</div>
            </a-menu-item>
          </a-menu-item-group>
        </a-menu>
      </template>
    </a-dropdown>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { BUILTIN_VARIABLES } from '../nodeConfigMeta'
import { buildUpstreamVariableItems } from '../workflowUpstreamVariables'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '{{query}}' },
  disabled: { type: Boolean, default: false },
  nodeId: { type: String, default: '' },
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] },
})

const emit = defineEmits(['update:modelValue', 'change'])

const upstreamItems = computed(() => {
  if (!props.nodeId || !props.nodes?.length) return []
  return buildUpstreamVariableItems(props.nodeId, props.nodes, props.edges)
})

function onInput(v) {
  emit('update:modelValue', v)
  emit('change', v)
}

function onPick({ key: menuKey }) {
  const builtin = BUILTIN_VARIABLES.find((v) => menuKey === 'builtin-' + v.key)
  if (builtin) {
    emit('update:modelValue', builtin.example)
    emit('change', builtin.example)
    return
  }
  const upstream = upstreamItems.value.find((v) => menuKey === 'upstream-' + v.key)
  if (upstream) {
    emit('update:modelValue', upstream.example)
    emit('change', upstream.example)
  }
}
</script>

<style scoped>
.variable-picker-input {
  display: flex;
  gap: 4px;
  align-items: center;
  flex-wrap: nowrap;
  min-width: 0;
  width: 100%;
}
.variable-picker-input :deep(.ant-input) {
  flex: 1 1 0;
  min-width: 0;
}
.var-pick-btn {
  flex-shrink: 0;
  padding: 0 4px;
  white-space: nowrap;
}
</style>

<style>
.builtin-var-menu {
  max-height: 360px;
  overflow-y: auto;
  min-width: 280px;
}
.builtin-var-menu .var-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
}
.builtin-var-menu code {
  font-size: 12px;
  color: var(--color-link);
}
.builtin-var-menu .var-menu-label {
  font-size: 13px;
  color: var(--color-text-dark);
}
.builtin-var-menu .var-menu-desc {
  font-size: 11px;
  color: var(--color-mute);
  margin-top: 2px;
  padding-left: 2px;
}
</style>
