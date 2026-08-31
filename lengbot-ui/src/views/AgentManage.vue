<template>
  <div class="page">
    <LbManageHeader
      title="Agent 列表"
      v-model="searchText"
      search-placeholder="搜索 Agent 名称..."
      :refresh-disabled="loading"
      create-text="新建 Agent"
      create-variant="neon"
      @refresh="refresh"
      @create="openDialog()"
    >
      <template #filters>
        <a-select
          v-model:value="filterAgentType"
          placeholder="全部类型"
          allow-clear
          style="width: 130px"
          @change="loadData"
        >
          <a-select-option value="chat">对话型</a-select-option>
          <a-select-option value="workflow">工作流型</a-select-option>
        </a-select>
      </template>
      <template #searchPrefix><SearchOutlined /></template>
      <template #actions>
        <div class="view-switch" role="group" aria-label="视图切换">
          <button
            type="button"
            class="view-switch__btn"
            :class="{ active: viewMode === 'card' }"
            title="卡片视图"
            @click="viewMode = 'card'"
          >
            <AppstoreOutlined />
          </button>
          <button
            type="button"
            class="view-switch__btn"
            :class="{ active: viewMode === 'list' }"
            title="列表视图"
            @click="viewMode = 'list'"
          >
            <UnorderedListOutlined />
          </button>
        </div>
        <a-tooltip title="示例工作流">
          <button class="lb-btn lb-btn--ghost" @click="openExampleModal">
            <ExperimentOutlined />
          </button>
        </a-tooltip>
        <a-tooltip title="消息反馈记录">
          <button class="lb-btn lb-btn--ghost" @click="feedbackOpen = true">
            <LikeOutlined />
          </button>
        </a-tooltip>
      </template>
    </LbManageHeader>

    <!-- 统计条：让页面有信息价值 -->
    <div class="stat-bar">
      <div class="stat-bar__item">
        <span class="stat-bar__num">{{ totalCount }}</span>
        <span class="stat-bar__label">共 Agent</span>
      </div>
      <div class="stat-bar__divider" />
      <div class="stat-bar__item">
        <span class="stat-bar__num stat-bar__num--green">{{ publishedCount }}</span>
        <span class="stat-bar__label">已发布</span>
      </div>
      <div class="stat-bar__divider" />
      <div class="stat-bar__item">
        <span class="stat-bar__num stat-bar__num--amber">{{ draftCount }}</span>
        <span class="stat-bar__label">草稿</span>
      </div>
    </div>

    <a-spin :spinning="loading" style="min-height: 300px; display: block">
      <!-- 卡片视图 -->
      <div v-show="viewMode === 'card'" class="agent-grid">
        <EntityCard
          v-for="a in list"
          :key="a.id"
          :type="resolveAgentBindingType(a.agentType)"
          :name="a.name"
          @click="router.push(`/app/agents/${a.id}`)"
        >
          <template #icon>
            <img v-if="a.avatar" :src="a.avatar" alt="" class="card-avatar-img" @error="a.avatar = ''" />
            <span v-else>{{ (a.name || 'A')[0] }}</span>
          </template>
          <template #info>
            <a-tooltip :title="a.name">
              <h3>
                {{ a.name }}
                <span v-if="a.isDefault" class="card-default-tag">默认</span>
              </h3>
            </a-tooltip>
            <span class="card-type" :class="'card-type--' + (a.agentType?.code || a.agentType || 'chat')">
              {{ agentTypeLabel(a.agentType) }}
            </span>
          </template>
          <template #actions>
            <a-tooltip title="编辑">
              <button class="btn-icon" @click.stop="openDialog(a)"><EditOutlined /></button>
            </a-tooltip>
            <a-dropdown :trigger="['click']">
              <button class="btn-icon" @click.stop.prevent><MoreOutlined /></button>
              <template #overlay>
                <a-menu>
                  <a-menu-item v-if="!a.isDefault" @click="handleSetDefault(a.id)">
                    <StarOutlined style="margin-right: 6px" />
                    设为默认
                  </a-menu-item>
                  <a-menu-item @click="handleClone(a.id)">
                    <CopyOutlined style="margin-right: 6px" />
                    复制
                  </a-menu-item>
                  <a-menu-item @click="handleDelete(a.id)" class="menu-danger">
                    <DeleteOutlined style="margin-right: 6px" />
                    删除
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </template>
          <a-tooltip v-if="a.description" :title="a.description" placement="top" :overlay-style="{ maxWidth: '400px' }">
            <p class="card-desc">{{ a.description }}</p>
          </a-tooltip>
          <p v-else class="card-desc">暂无描述</p>
          <template #meta>
            <span class="card-status" :class="(a.status?.code || a.status || 'draft').toLowerCase()">
              <span class="card-status__dot" />
              {{ statusText(a.status?.code || a.status, a.version) }}
            </span>
            <span class="card-time">{{ formatTime(a.createTime) }}</span>
          </template>
          <!-- 卡片底部操作区：消除下半部空白，hover 可见 -->
          <div class="card-footer">
            <button class="card-footer__btn" @click.stop="openDialog(a)">
              <EditOutlined /> 编辑
            </button>
            <button class="card-footer__btn" @click.stop="router.push(`/app/agents/${a.id}`)">
              <SettingOutlined /> 配置
            </button>
            <button class="card-footer__btn card-footer__btn--primary" @click.stop="runAgent(a)">
              <CaretRightOutlined /> 运行
            </button>
          </div>
        </EntityCard>

        <LbEmptyState
          v-if="list.length === 0 && !loading"
          :icon="RobotOutlined"
          :title="searchText ? '没有匹配的 Agent' : '还没有 Agent'"
          desc="点击右上角「新建 Agent」，或从示例工作流快速开始。"
        >
          <template #action>
            <button class="lb-btn lb-btn--accent lb-btn--accent--agent" @click="openDialog()">
              <PlusOutlined /> 创建第一个 Agent
            </button>
          </template>
        </LbEmptyState>
      </div>

      <!-- 列表视图 -->
      <div v-show="viewMode === 'list'" class="agent-list">
        <a-table
          :columns="tableColumns"
          :data-source="list"
          :loading="false"
          :pagination="false"
          row-key="id"
          size="middle"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'name'">
              <div class="list-name" @click="router.push(`/app/agents/${record.id}`)">
                <span class="list-name__icon" :style="{ background: agentAvatarGradient(record.agentType) }">
                  {{ (record.name || 'A')[0] }}
                </span>
                <div class="list-name__text">
                  <span class="list-name__title">
                    {{ record.name }}
                    <a-tag v-if="record.isDefault" color="gold" class="list-default-tag">默认</a-tag>
                  </span>
                  <span class="list-name__desc">{{ record.description || '暂无描述' }}</span>
                </div>
              </div>
            </template>
            <template v-else-if="column.key === 'type'">
              <span class="card-type" :class="'card-type--' + (record.agentType?.code || record.agentType || 'chat')">
                {{ agentTypeLabel(record.agentType) }}
              </span>
            </template>
            <template v-else-if="column.key === 'status'">
              <span class="card-status" :class="(record.status?.code || record.status || 'draft').toLowerCase()">
                <span class="card-status__dot" />
                {{ statusText(record.status?.code || record.status, record.version) }}
              </span>
            </template>
            <template v-else-if="column.key === 'createTime'">
              <span class="card-time">{{ formatTime(record.createTime) }}</span>
            </template>
            <template v-else-if="column.key === 'action'">
              <div class="list-actions">
                <a-tooltip title="编辑"><button class="btn-icon" @click="openDialog(record)"><EditOutlined /></button></a-tooltip>
                <a-tooltip title="配置"><button class="btn-icon" @click="router.push(`/app/agents/${record.id}`)"><SettingOutlined /></button></a-tooltip>
                <a-tooltip title="运行"><button class="btn-icon" @click="runAgent(record)"><CaretRightOutlined /></button></a-tooltip>
                <a-dropdown :trigger="['click']">
                  <button class="btn-icon" @click.stop.prevent><MoreOutlined /></button>
                  <template #overlay>
                    <a-menu>
                      <a-menu-item v-if="!record.isDefault" @click="handleSetDefault(record.id)">
                        <StarOutlined style="margin-right: 6px" /> 设为默认
                      </a-menu-item>
                      <a-menu-item @click="handleClone(record.id)">
                        <CopyOutlined style="margin-right: 6px" /> 复制
                      </a-menu-item>
                      <a-menu-item @click="handleDelete(record.id)" class="menu-danger">
                        <DeleteOutlined style="margin-right: 6px" /> 删除
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </div>
            </template>
          </template>
        </a-table>
        <LbEmptyState
          v-if="list.length === 0 && !loading"
          :icon="RobotOutlined"
          :title="searchText ? '没有匹配的 Agent' : '还没有 Agent'"
          desc="点击右上角「新建 Agent」，或从示例工作流快速开始。"
        />
      </div>
    </a-spin>

    <!-- 创建/编辑弹窗 -->
    <a-modal
      v-model:open="dialogVisible"
      :title="form.id ? '编辑 Agent' : '新建 Agent'"
      :width="560"
      @ok="handleSubmit"
      :confirm-loading="submitting"
      :mask-closable="false"
    >
      <a-form :model="form" :label-col="{ flex: '0 0 80px' }">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如：客服助手（不超过30字）" :maxlength="30" show-count />
        </a-form-item>
        <a-form-item label="描述">
          <a-textarea
            v-model:value="form.description"
            :rows="2"
            placeholder="Agent 描述（不超过500字）"
            :maxlength="500"
            show-count
          />
        </a-form-item>
        <a-form-item label="类型">
          <a-select v-model:value="form.agentType" style="width: 100%">
            <a-select-option value="chat">对话型</a-select-option>
            <a-select-option value="workflow">工作流型</a-select-option>
            <a-select-option value="digital_human">数字人型</a-select-option>
          </a-select>
        </a-form-item>
        <!-- 模型：仅新建且非工作流类型显示 -->
        <a-form-item v-if="!form.id && form.agentType !== 'workflow'" label="模型" required>
          <ModelSelect v-model:provider-id="createProviderId" v-model:model-id="createModelId" placeholder="选择模型" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 示例工作流弹窗 -->
    <a-modal
      v-model:open="exampleModalVisible"
      title="示例工作流 Agent"
      :width="640"
      :footer="null"
      :mask-closable="false"
      class="example-workflow-modal"
      :body-style="{ padding: 0, overflow: 'hidden' }"
    >
      <div class="dialog-scroll-body">
        <div class="example-modal-body">
          <div class="example-desc">选择一个内置示例，快速创建工作流 Agent 并学习各节点的使用方式</div>
          <div class="example-list-scroll">
            <div class="example-list">
              <div v-for="ex in workflowExamples" :key="ex.key" class="example-card">
                <div class="example-card-header">
                  <span class="example-name">{{ ex.name }}</span>
                  <a-button
                    type="primary"
                    size="small"
                    :loading="exampleCreating === ex.key"
                    @click="handleCreateExample(ex.key)"
                  >
                    生成
                  </a-button>
                </div>
                <div class="example-desc-text">{{ ex.description }}</div>
                <div class="example-tags">
                  <a-tag v-for="tag in ex.nodeTypeTags" :key="tag" color="blue">{{ tag }}</a-tag>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </a-modal>

    <FeedbackHistory v-model:open="feedbackOpen" />
  </div>
</template>

<script setup>
import { ref, reactive, watch, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  CopyOutlined,
  RobotOutlined,
  SearchOutlined,
  ReloadOutlined,
  StarOutlined,
  ExperimentOutlined,
  MoreOutlined,
  LikeOutlined,
  SettingOutlined,
  CaretRightOutlined,
  AppstoreOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import {
  getAgents,
  createAgent,
  updateAgent,
  deleteAgent,
  cloneAgent,
  setDefaultAgent,
  listWorkflowExamples,
  createFromWorkflowExample,
} from '../api/agent'
import { formatDate as formatTime } from '../utils/format'
import FeedbackHistory from './FeedbackHistory.vue'
import { loadAgentStatusLabels, formatAgentStatus } from '../utils/agentStatus'
import ModelSelect from '../components/ModelSelect.vue'
import EntityCard from '../components/EntityCard.vue'
import LbManageHeader from '../components/common/LbManageHeader.vue'
import LbEmptyState from '../components/common/LbEmptyState.vue'
import { resolveAgentBindingType, agentAvatarGradient } from '../utils/bindingTheme'

const router = useRouter()
const list = ref([])
const loading = ref(false)
const agentStatusLabels = ref(null)
const searchText = ref('')
const filterAgentType = ref(undefined)
const dialogVisible = ref(false)
const submitting = ref(false)
const form = reactive({ id: null, name: '', description: '', agentType: 'chat' })
const createProviderId = ref(null)
const createModelId = ref(null)
const exampleModalVisible = ref(false)
const workflowExamples = ref([])
const exampleCreating = ref(null)
const feedbackOpen = ref(false)
const viewMode = ref('card') // 'card' | 'list'

// 统计条派生数据
const totalCount = computed(() => list.value.length)
const publishedCount = computed(
  () => list.value.filter((a) => (a.status?.code || a.status || '') === 'published').length,
)
const draftCount = computed(
  () => list.value.filter((a) => (a.status?.code || a.status || 'draft') !== 'published').length,
)

const tableColumns = [
  { title: '名称', key: 'name', ellipsis: true },
  { title: '类型', key: 'type', width: 120 },
  { title: '状态', key: 'status', width: 140 },
  { title: '创建时间', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 160, align: 'right' },
]

function runAgent(a) {
  // 运行 = 跳转到该 Agent 的对话/调试界面（沿用既有导航逻辑，不新增接口）
  router.push(`/app/agents/${a.id}`)
}

function openDialog(row) {
  createProviderId.value = null
  createModelId.value = null
  if (row) {
    Object.assign(form, {
      id: row.id,
      name: row.name || '',
      description: row.description || '',
      agentType: row.agentType?.code || row.agentType || 'chat',
    })
  } else {
    Object.assign(form, { id: null, name: '', description: '', agentType: 'chat' })
  }
  dialogVisible.value = true
}

async function loadData() {
  loading.value = true
  try {
    const params = { pageNum: 1, pageSize: 50, includeDefault: false }
    if (searchText.value) params.name = searchText.value
    if (filterAgentType.value) params.agentType = filterAgentType.value
    const res = await getAgents(params)
    list.value = res.data.records || []
  } finally {
    loading.value = false
  }
}

// 刷新按钮语义：清空搜索关键词 + 类型筛选，回到全量列表
function refresh() {
  searchText.value = ''
  filterAgentType.value = undefined
  loadData()
}

let searchDebounceTimer = null
watch(searchText, () => {
  clearTimeout(searchDebounceTimer)
  // 立刻置 loading，避免 debounce 的 300ms 窗口期里 list=[] + loading=false 触发空状态闪现
  loading.value = true
  searchDebounceTimer = setTimeout(() => loadData(), 300)
})

async function handleSubmit() {
  if (!form.name.trim()) return message.warning('请输入名称')
  // 工作流类型不需要模型，在LLM节点中配置
  if (!form.id && form.agentType !== 'workflow' && (!createProviderId.value || !createModelId.value)) {
    return message.warning('请选择模型')
  }
  submitting.value = true
  try {
    if (form.id) {
      await updateAgent(form)
      message.success('更新成功')
    } else {
      const config =
        form.agentType === 'workflow'
          ? '{}'
          : JSON.stringify({ providerId: createProviderId.value, modelId: createModelId.value })
      await createAgent({ ...form, config })
      message.success('创建成功')
    }
    dialogVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

function handleDelete(id) {
  Modal.confirm({
    title: '确认删除',
    content: '删除后该 Agent 将无法恢复，是否继续？',
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      await deleteAgent(id)
      message.success('删除成功')
      loadData()
    },
  })
}

async function handleClone(id) {
  try {
    const res = await cloneAgent(id)
    message.success(`已复制: ${res.data?.name || '新 Agent'}`)
    loadData()
  } catch {
    // interceptor handled
  }
}

function handleSetDefault(id) {
  Modal.confirm({
    title: '设为默认智能体',
    content: '设置为默认智能体后，该智能体将对所有用户公开访问。确认继续？',
    okText: '确认',
    cancelText: '取消',
    async onOk() {
      await setDefaultAgent(id)
      message.success('已设为默认')
      loadData()
    },
  })
}

function agentTypeLabel(t) {
  const code = t?.code || t || ''
  const map = { chat: '对话型', assistant: '对话型', workflow: '工作流型', digital_human: '数字人型' }
  return map[code] || code || '对话型'
}

function statusText(s, version) {
  return formatAgentStatus(s, version || 0, agentStatusLabels.value)
}

async function openExampleModal() {
  try {
    const res = await listWorkflowExamples()
    workflowExamples.value = res.data || []
    exampleModalVisible.value = true
  } catch {
    message.error('加载示例列表失败')
  }
}

function handleCreateExample(key) {
  const ex = workflowExamples.value.find((e) => e.key === key)
  Modal.confirm({
    title: '生成示例工作流 Agent',
    content: `即将生成「${ex?.name || key}」。\n\n注意：示例中的部分节点需要手动配置实际内容（如绑定知识库、选择工具、选择模型等），生成后请进入工作流编辑器逐一完善。`,
    okText: '确认生成',
    cancelText: '取消',
    async onOk() {
      exampleCreating.value = key
      try {
        const res = await createFromWorkflowExample(key)
        message.success('示例 Agent 创建成功')
        exampleModalVisible.value = false
        loadData()
        router.push(`/app/agents/${res.data.id}`)
      } catch {
        message.error('创建失败')
      } finally {
        exampleCreating.value = null
      }
    },
  })
}

onMounted(async () => {
  agentStatusLabels.value = await loadAgentStatusLabels()
  loadData()
})
</script>

<style scoped>
.page {
  overflow-x: hidden;
}

:deep(.menu-danger) {
  color: var(--color-error) !important;
}
:deep(.menu-danger:hover) {
  background: var(--color-error-soft) !important;
}

/* ===== 统计条 ===== */
.stat-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 4px 0 18px;
  padding: 14px 20px;
  background: rgba(17, 22, 46, 0.66);
  border: 1px solid var(--card-bd);
  border-radius: 14px;
  backdrop-filter: blur(10px);
}
.stat-bar__item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 0 18px;
}
.stat-bar__num {
  font-size: 24px;
  font-weight: 700;
  color: var(--color-ink);
  font-variant-numeric: tabular-nums;
}
.stat-bar__num--green {
  color: #34d399;
}
.stat-bar__num--amber {
  color: #fbbf24;
}
.stat-bar__label {
  font-size: 13px;
  color: var(--color-mute);
}
.stat-bar__divider {
  width: 1px;
  height: 28px;
  background: var(--card-bd);
}

/* ===== 视图切换 ===== */
.view-switch {
  display: inline-flex;
  border: 1px solid var(--card-bd);
  border-radius: 9px;
  overflow: hidden;
  margin-right: 4px;
}
.view-switch__btn {
  width: 34px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  color: var(--color-mute);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.view-switch__btn + .view-switch__btn {
  border-left: 1px solid var(--card-bd);
}
.view-switch__btn:hover {
  color: var(--color-ink);
  background: rgba(34, 211, 238, 0.08);
}
.view-switch__btn.active {
  color: #051022;
  background: linear-gradient(135deg, var(--cyan), var(--indigo));
}

/* ===== 卡片网格 ===== */
.agent-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}
/* 头像需要 overflow: hidden 裁剪图片 */
:deep(.card-icon) {
  overflow: hidden;
}
.card-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.card-type {
  display: inline-block;
  margin-top: 4px;
  font-size: 11px;
  font-weight: 500;
  padding: 1px 8px;
  border-radius: 4px;
  letter-spacing: 0.3px;
}
.card-type--chat,
.card-type--assistant {
  color: #67e8f9;
  background: rgba(34, 211, 238, 0.12);
}
.card-type--workflow {
  color: #c4b5fd;
  background: rgba(139, 92, 246, 0.14);
}
.card-default-tag {
  font-size: 11px;
  padding: 1px 6px;
  background: rgba(34, 211, 238, 0.16);
  color: #67e8f9;
  border-radius: 100px;
  font-weight: 500;
}
.card-desc {
  font-size: 13px;
  color: var(--color-mute);
  margin-bottom: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  width: fit-content;
  max-width: 100%;
}
/* 状态：彩色圆点标签 */
.card-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 2px 10px 2px 8px;
  border-radius: 100px;
}
.card-status__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.card-status.draft {
  background: rgba(251, 191, 36, 0.12);
  color: #fbbf24;
}
.card-status.draft .card-status__dot {
  background: #fbbf24;
  box-shadow: 0 0 6px rgba(251, 191, 36, 0.7);
}
.card-status.published {
  background: rgba(52, 211, 153, 0.12);
  color: #34d399;
}
.card-status.published .card-status__dot {
  background: #34d399;
  box-shadow: 0 0 6px rgba(52, 211, 153, 0.7);
}
.card-status.published_editing {
  background: rgba(251, 191, 36, 0.12);
  color: #fbbf24;
}
.card-status.published_editing .card-status__dot {
  background: #fbbf24;
}
.card-status.archived {
  background: rgba(148, 163, 184, 0.14);
  color: #94a3b8;
}
.card-status.archived .card-status__dot {
  background: #94a3b8;
}
/* 卡片底部操作区 */
.card-footer {
  display: flex;
  gap: 8px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--color-hairline);
}
.card-footer__btn {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 34px;
  font-size: 13px;
  border-radius: 8px;
  border: 1px solid var(--card-bd);
  background: rgba(34, 211, 238, 0.06);
  color: var(--color-body);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, color 0.15s, transform 0.15s;
}
.card-footer__btn:hover {
  border-color: var(--cyan);
  background: rgba(34, 211, 238, 0.14);
  color: #fff;
}
.card-footer__btn--primary {
  border: none;
  background: linear-gradient(135deg, var(--cyan), var(--indigo));
  color: #051022;
  font-weight: 600;
}
.card-footer__btn--primary:hover {
  background: linear-gradient(135deg, #4fe3f7, #7c83f5);
  color: #051022;
}
.card-time {
  font-size: 12px;
  color: var(--color-mute);
}

/* ===== 列表视图 ===== */
.agent-list {
  background: rgba(17, 22, 46, 0.66);
  border: 1px solid var(--card-bd);
  border-radius: 14px;
  overflow: hidden;
  backdrop-filter: blur(10px);
}
.list-name {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}
.list-name__icon {
  width: 36px;
  height: 36px;
  border-radius: 9px;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 15px;
  flex-shrink: 0;
}
.list-name__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.list-name__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-ink);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.list-default-tag {
  transform: scale(0.85);
  transform-origin: left center;
}
.list-name__desc {
  font-size: 12px;
  color: var(--color-mute);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.list-actions {
  display: inline-flex;
  gap: 4px;
  justify-content: flex-end;
}

/* 空状态内边距 */
.empty-state {
  grid-column: 1 / -1;
  text-align: center;
  padding: 60px 20px;
  color: var(--color-mute);
}
.empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
  display: block;
}

/* 示例弹窗（沿用既有样式，仅微调 hover 色） */
.example-desc {
  flex-shrink: 0;
  color: var(--color-mute);
  font-size: 13px;
  padding: 0 24px 16px;
  margin-bottom: 0;
}
.example-modal-body {
  display: flex;
  flex-direction: column;
  max-height: 65vh;
}
.example-list-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 12px 20px 24px;
  margin-right: 8px;
  scrollbar-gutter: stable;
}
.example-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-right: 8px;
}
.example-card {
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  padding: 14px 16px;
  transition: border-color 0.2s;
}
.example-card:hover {
  border-color: var(--cyan);
}
.example-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.example-name {
  font-weight: 500;
  font-size: 14px;
  color: var(--color-ink);
}
.example-desc-text {
  font-size: 12px;
  color: var(--color-mute);
  margin-bottom: 10px;
  line-height: 1.6;
}
.example-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
</style>
