<template>
  <div class="debug-registry-panel">
    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="tools" tab="工具 TOOL_RENDERERS">
        <a-table
          :columns="toolColumns"
          :data-source="toolRows"
          :pagination="{ pageSize: 12 }"
          size="small"
          row-key="toolName"
        />
      </a-tab-pane>
      <a-tab-pane key="capability" tab="能力块/流事件 CAPABILITY">
        <a-table
          :columns="capabilityColumns"
          :data-source="capabilityRows"
          :pagination="false"
          size="small"
          row-key="eventType"
        />
      </a-tab-pane>
      <a-tab-pane key="skills" tab="Skill SKILL_ITEM">
        <a-table
          :columns="skillColumns"
          :data-source="skillRows"
          :pagination="{ pageSize: 10 }"
          size="small"
          row-key="slug"
        />
      </a-tab-pane>
    </a-tabs>
    <div class="debug-registry-hint">
      只读浏览前端注册表，数据来自 toolRegistry / capabilityRegistry / skillRegistry，不请求后端。
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import {
  getToolRegistryRows,
  getCapabilityRegistryRows,
  getSkillRegistryRows,
} from '@/utils/chat/debug/debugRegistryData'

const activeTab = ref('tools')
const toolRows = getToolRegistryRows().map((r) => ({
  ...r,
  hasRenderer: r.hasRenderer ? '是' : '否',
  hidden: r.hidden ? '是' : '否',
}))
const capabilityRows = getCapabilityRegistryRows()
const skillRows = getSkillRegistryRows().map((r) => ({
  ...r,
  hasCustomRenderer: r.hasCustomRenderer ? '是' : '否',
  builtin: r.builtin ? '是' : '否',
}))

const toolColumns = [
  { title: 'toolName', dataIndex: 'toolName', key: 'toolName' },
  { title: '显示名', dataIndex: 'displayName', key: 'displayName' },
  { title: '专用渲染', dataIndex: 'hasRenderer', key: 'hasRenderer' },
  { title: 'Hidden', dataIndex: 'hidden', key: 'hidden' },
  { title: '组件', dataIndex: 'component', key: 'component' },
]

const capabilityColumns = [
  { title: 'eventType', dataIndex: 'eventType', key: 'eventType' },
  { title: '显示名', dataIndex: 'displayName', key: 'displayName' },
  { title: '组件', dataIndex: 'component', key: 'component' },
  { title: '顶层块', dataIndex: 'topLevel', key: 'topLevel' },
]

const skillColumns = [
  { title: 'slug', dataIndex: 'slug', key: 'slug' },
  { title: '显示名', dataIndex: 'displayName', key: 'displayName' },
  { title: '定制渲染', dataIndex: 'hasCustomRenderer', key: 'hasCustomRenderer' },
  { title: '内置', dataIndex: 'builtin', key: 'builtin' },
  { title: '组件', dataIndex: 'component', key: 'component' },
]
</script>

<style scoped>
.debug-registry-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.debug-registry-hint {
  margin-top: 12px;
  font-size: 12px;
  color: var(--gray-500);
}
</style>
