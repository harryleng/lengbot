<template>
  <a-modal v-model:open="open" title="全局设置" :width="640" @ok="$emit('ok')">
    <div class="dialog-scroll-body">
      <a-form layout="vertical">
        <a-form-item>
          <template #label>
            <ConfigFieldLabel
              label="上下文轮次（history_max_round）"
              tip="控制注入工作流变量 history_list 时保留的最近对话轮数。1 轮 = 1 条用户消息 + 1 条助手回复；设为 5 约等于最近 10 条消息。LLM 节点可通过 {{history_list}} 引用。"
            />
          </template>
          <a-input-number
            v-model:value="config.history_config.history_max_round"
            :min="0"
            :max="50"
            style="width: 100%"
          />
        </a-form-item>
        <a-form-item>
          <template #label>
            <ConfigFieldLabel
              label="启用对话历史"
              tip="开启后，Chat 多轮对话会将会话历史写入 history_list / query 等流程变量，供 LLM、脚本等节点使用；关闭时每次仅携带当前用户输入，无历史上下文。"
            />
          </template>
          <a-switch v-model:checked="config.history_config.history_switch" />
        </a-form-item>
        <a-divider>会话变量（conversation_params）</a-divider>
        <div
          v-for="(param, idx) in config.variable_config.conversation_params"
          :key="(param.key || 'param') + '-' + idx"
          class="conv-param-row"
        >
          <a-input v-model:value="param.key" placeholder="变量名" style="flex: 1" />
          <a-input v-model:value="param.default_value" placeholder="默认值" style="flex: 1" />
          <a-button type="text" danger @click="$emit('remove-param', idx)"><DeleteOutlined /></a-button>
        </div>
        <a-button type="dashed" block @click="$emit('add-param')">
          <PlusOutlined />
          添加会话变量
        </a-button>
      </a-form>
    </div>
  </a-modal>
</template>

<script setup>
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons-vue'
import ConfigFieldLabel from '../ConfigFieldLabel.vue'

defineProps({
  config: { type: Object, required: true },
})

defineEmits(['ok', 'add-param', 'remove-param'])

const open = defineModel('open', { type: Boolean, default: false })
</script>

<style scoped>
.conv-param-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
</style>
