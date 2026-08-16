<template>
  <div class="chat-input-toolbar">
    <!-- Agent 列表为空时显示气泡引导 -->
    <a-popover v-if="agents.length === 0" trigger="click" placement="topLeft">
      <template #content>
        <div class="empty-agent-tip">
          系统里还没有智能体，
          <router-link to="/app/agents">点击创建智能体</router-link>
        </div>
      </template>
      <button type="button" class="btn-agent">
        <RobotOutlined />
      </button>
    </a-popover>
    <!-- Agent 列表不为空时正常下拉 -->
    <a-dropdown v-else :trigger="['click']" placement="topLeft">
      <a-tooltip :title="currentAgent?.name || '选择 Agent'">
        <button type="button" class="btn-agent">
          <RobotOutlined v-if="!currentAgent" />
          <img v-else-if="currentAgent.avatar" :src="currentAgent.avatar" alt="" class="btn-agent-avatar" />
          <span v-else class="btn-agent-initial" :style="agentIconStyle(currentAgent?.agentType)">
            {{ currentAgent.name[0] }}
          </span>
        </button>
      </a-tooltip>
      <template #overlay>
        <a-menu @click="onAgentSelect" :selected-keys="selectedAgentId ? [String(selectedAgentId)] : []">
          <a-menu-item v-for="a in agents" :key="String(a.id)">
            <div class="agent-menu-item">
              <img v-if="a.avatar" :src="a.avatar" alt="" class="agent-menu-icon" />
              <span v-else class="agent-menu-icon" :style="agentIconStyle(a.agentType)">{{ a.name[0] }}</span>
              <span class="agent-menu-name">{{ a.name }}</span>
              <span v-if="agentVersionLabel(a)" class="agent-version-tag">{{ agentVersionLabel(a) }}</span>
              <span v-if="a.isDefault" class="agent-default-tag">默认</span>
            </div>
          </a-menu-item>
        </a-menu>
      </template>
    </a-dropdown>
    <span v-if="currentAgent?.name" class="chat-toolbar-agent-name">{{ currentAgent.name }}</span>
    <a-select
      v-if="selectedAgentId && configVersionOptions.length > 0"
      :value="selectedConfigVersion"
      class="config-version-select"
      :disabled="loading"
      popup-class-name="config-version-select-dropdown"
      @change="onConfigVersionChange"
    >
      <a-select-option
        v-for="opt in configVersionOptions"
        :key="String(opt.value)"
        :value="opt.value"
        :label="opt.selectLabel"
      >
        <span class="version-option-row">
          <span class="version-option-num">{{ opt.versionLabel }}</span>
          <a-tag v-if="opt.badge === 'draft'" class="version-status-tag draft" :bordered="false">草稿</a-tag>
          <a-tag v-else-if="opt.badge === 'online'" class="version-status-tag online" color="success" :bordered="false">
            线上
          </a-tag>
        </span>
      </a-select-option>
    </a-select>
    <a-tooltip v-if="sessionTokenCount > 0" :title="`本次会话累计消耗 ${sessionTokenCount.toLocaleString()} tokens`">
      <div class="token-pill">
        <ThunderboltOutlined class="token-pill-icon" />
        <span class="token-pill-value">{{ formatTokenCount(sessionTokenCount) }}</span>
        <span class="token-pill-label">tokens</span>
      </div>
    </a-tooltip>
  </div>
</template>

<script setup>
import { RobotOutlined, ThunderboltOutlined } from '@ant-design/icons-vue'
import { agentAvatarGradient } from '../../../utils/bindingTheme'

defineProps({
  agents: { type: Array, default: () => [] },
  selectedAgentId: { type: [String, Number], default: null },
  currentAgent: { type: Object, default: null },
  configVersionOptions: { type: Array, default: () => [] },
  selectedConfigVersion: { type: [String, Number], default: 0 },
  sessionTokenCount: { type: Number, default: 0 },
  loading: { type: Boolean, default: false },
})

const emit = defineEmits(['agent-select', 'config-version-change'])

function agentIconStyle(agentType) {
  return { background: agentAvatarGradient(agentType) }
}

function agentVersionLabel(a) {
  const status = a.status
  const ver = a.version
  if (status === 'published' && ver > 0) return `v${ver}`
  if (status === 'published_editing' && ver > 0) return `v${ver}·编辑中`
  if (status === 'draft') return '草稿'
  return ''
}

function formatTokenCount(tokens) {
  if (!tokens) return '0'
  if (tokens >= 10000) return (tokens / 10000).toFixed(1) + '万'
  return tokens.toLocaleString()
}

function onAgentSelect(e) {
  emit('agent-select', e)
}

function onConfigVersionChange(value) {
  emit('config-version-change', value)
}
</script>

<style scoped>
.chat-input-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: var(--color-canvas-soft);
  border-bottom: 1px solid var(--color-hairline);
  position: relative;
}
.chat-toolbar-agent-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Agent 选择按钮 */
.btn-agent {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  border: none;
  background: var(--color-canvas-soft-2);
  color: var(--color-mute);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 16px;
  transition: background 0.15s;
  overflow: hidden;
}
.btn-agent:hover {
  background: var(--color-hairline);
}
.btn-agent-avatar {
  width: 36px;
  height: 36px;
  object-fit: cover;
}
.btn-agent-initial {
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  line-height: 1;
}

/* Agent 下拉菜单项 */
.agent-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 160px;
}
.agent-menu-icon {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}
span.agent-menu-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  color: #fff;
}
.agent-menu-icon.default-icon {
  background: #0070f3;
  color: #fff;
}
.agent-menu-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.agent-version-tag {
  font-size: 10px;
  padding: 1px 6px;
  background: var(--color-canvas-soft-2);
  color: var(--color-mute);
  border-radius: 100px;
  flex-shrink: 0;
}
.agent-default-tag {
  font-size: 10px;
  padding: 1px 6px;
  background: var(--color-info-bg);
  color: #2563eb;
  border-radius: 100px;
  flex-shrink: 0;
}
.empty-agent-tip {
  font-size: 13px;
  color: var(--color-body);
  white-space: nowrap;
}
.empty-agent-tip a {
  color: var(--color-link);
  font-weight: 500;
}

.config-version-select {
  margin-left: auto;
  flex-shrink: 0;
  min-width: 128px;
  max-width: 200px;
}
.version-option-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.version-option-num {
  font-size: 13px;
  color: var(--color-ink);
}
.version-status-tag {
  margin: 0;
  font-size: 11px;
  line-height: 18px;
  padding: 0 6px;
}
.version-status-tag.draft {
  background: var(--color-canvas-soft-2);
  color: var(--color-body);
}
.token-pill {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  padding: 2px 10px;
  background: var(--color-canvas-soft-2);
  border: 1px solid var(--color-hairline);
  border-radius: 100px;
  font-size: 12px;
  white-space: nowrap;
}
.token-pill-icon {
  color: #f59e0b;
  font-size: 12px;
}
.token-pill-value {
  color: var(--color-ink);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.token-pill-label {
  color: var(--color-mute);
}
</style>
