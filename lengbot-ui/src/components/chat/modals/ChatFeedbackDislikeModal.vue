<template>
  <!-- Dislike 原因弹窗 -->
  <a-modal
    :open="open"
    title="反馈原因（可选）"
    :mask-closable="false"
    :closable="true"
    :keyboard="false"
    width="420px"
    @cancel="$emit('close')"
    @update:open="$emit('update:open', $event)"
  >
    <div style="padding: 4px 0">
      <a-textarea
        :value="reason"
        placeholder="请描述问题所在，帮助我们改进回答质量..."
        :auto-size="{ minRows: 3, maxRows: 6 }"
        @update:value="$emit('update:reason', $event)"
      />
    </div>
    <template #footer>
      <a-button @click="$emit('close')">关闭</a-button>
      <a-button @click="$emit('skip')">跳过</a-button>
      <a-button type="primary" @click="$emit('submit')">提交</a-button>
    </template>
  </a-modal>
</template>

<script setup>
defineProps({
  open: { type: Boolean, default: false },
  reason: { type: String, default: '' },
})

defineEmits(['update:open', 'update:reason', 'submit', 'skip', 'close'])
</script>
