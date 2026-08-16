<template>
  <a-modal
    v-model:open="visible"
    title="渲染注册表"
    width="920px"
    :footer="null"
    destroy-on-close
    :body-style="{ padding: 0, overflow: 'hidden' }"
  >
    <div class="debug-registry-modal-body">
      <ChatDebugRegistryPanel />
    </div>
  </a-modal>
</template>

<script setup>
import { computed } from 'vue'
import ChatDebugRegistryPanel from './ChatDebugRegistryPanel.vue'

const props = defineProps({
  open: { type: Boolean, default: false },
})

const emit = defineEmits(['update:open'])

const visible = computed({
  get: () => props.open,
  set: (v) => emit('update:open', v),
})
</script>

<style scoped>
.debug-registry-modal-body {
  max-height: min(70vh, 640px);
  overflow-y: auto;
  overflow-x: hidden;
  padding: 16px 20px 16px 16px;
  scrollbar-gutter: stable;
}
</style>
