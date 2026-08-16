<template>
  <div class="knowledge-advisor-tab">
    <!-- 顶部工具栏 -->
    <div class="advisor-toolbar">
      <div class="advisor-toolbar-right">
        <span class="advisor-toolbar-label">统计窗口</span>
        <a-select
          v-model:value="windowDays"
          size="small"
          style="width: 220px"
          :popup-class-name="'advisor-window-dropdown'"
          @change="onWindowDaysChange"
        >
          <a-select-option v-for="opt in presetOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </a-select-option>
          <a-select-option v-if="isCustomWindow" :value="windowDays">最近 {{ windowDays }} 天</a-select-option>
          <template #dropdownRender="{ menuNode }">
            <component :is="menuNode" />
            <a-divider style="margin: 4px 0" />
            <div class="advisor-custom-days">
              <div class="advisor-custom-days-row">
                <span class="advisor-custom-days-label">自定义天数</span>
                <a-input-number
                  v-model:value="customDaysInput"
                  :min="1"
                  :max="365"
                  size="small"
                  style="width: 88px"
                  @press-enter="applyCustomDays"
                />
                <span class="advisor-custom-days-suffix">天</span>
                <a-button size="small" type="primary" @click="applyCustomDays">应用</a-button>
              </div>
              <div class="advisor-custom-days-hint">
                {{ customRangeText }}
              </div>
            </div>
          </template>
        </a-select>
        <a-tooltip title="刷新">
          <a-button size="small" @click="loadAll" :disabled="loading">
            <template #icon><ReloadOutlined :spin="loading" /></template>
          </a-button>
        </a-tooltip>
      </div>
    </div>

    <!-- 可滚动主体 -->
    <div class="advisor-scroll-body">
      <a-spin :spinning="loading">
        <!-- 概览卡片 -->
        <div class="advisor-cards">
          <div class="advisor-card card-stat">
            <div class="advisor-card-icon"><LikeOutlined /></div>
            <div class="advisor-card-body">
              <div class="advisor-card-value">{{ summary.totalLikes || 0 }}</div>
              <div class="advisor-card-label">总点赞</div>
            </div>
          </div>
          <div class="advisor-card card-stat">
            <div class="advisor-card-icon dislike"><DislikeOutlined /></div>
            <div class="advisor-card-body">
              <div class="advisor-card-value">{{ summary.totalDislikes || 0 }}</div>
              <div class="advisor-card-label">总点踩</div>
            </div>
          </div>
          <div class="advisor-card card-stat">
            <div class="advisor-card-icon rate">
              <span>{{ likeRatePercent }}%</span>
            </div>
            <div class="advisor-card-body">
              <div class="advisor-card-value">{{ likeRatePercent }}%</div>
              <div class="advisor-card-label">点赞率</div>
            </div>
          </div>
          <div class="advisor-card card-wide">
            <div class="advisor-card-icon ref"><EyeOutlined /></div>
            <div class="advisor-card-body">
              <div class="advisor-card-value">{{ summary.totalReferences || 0 }}</div>
              <div class="advisor-card-label">引用次数</div>
            </div>
          </div>
          <div class="advisor-card card-wide sleep-card">
            <div class="advisor-card-icon sleep"><RestOutlined /></div>
            <div class="advisor-card-body">
              <div class="advisor-card-value">{{ summary.sleepingChunkCount || 0 }}</div>
              <div class="advisor-card-label-row">
                <span class="advisor-card-label">休眠分块</span>
                <a-tag color="purple" class="advisor-card-tag">近 {{ summary.windowDays || 14 }} 天</a-tag>
              </div>
            </div>
          </div>
        </div>

        <!-- 低分分块 -->
        <div class="advisor-section">
          <div class="advisor-section-header">
            <h3>
              <WarningOutlined />
              低分分块
            </h3>
            <span class="advisor-section-desc">点踩较多的分块，建议复核内容质量或重新切分</span>
          </div>
          <a-empty
            v-if="lowRatedChunks.length === 0"
            description="暂无低分分块"
            :image="Empty.PRESENTED_IMAGE_SIMPLE"
          />
          <a-table
            v-else
            :data-source="lowRatedChunks"
            :columns="lowRatedColumns"
            :pagination="false"
            :scroll="{ x: 980 }"
            :table-layout="'fixed'"
            size="small"
            row-key="chunkId"
            class="advisor-table"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'documentName'">
                <span class="cell-text" :title="record.documentName">{{ record.documentName || '-' }}</span>
              </template>
              <template v-else-if="column.key === 'contentPreview'">
                <a-tooltip :title="record.contentPreview" placement="topLeft">
                  <span class="cell-text">{{ record.contentPreview || '-' }}</span>
                </a-tooltip>
              </template>
              <template v-else-if="column.key === 'dislikeRate'">
                <a-tag :color="record.dislikeRate >= 0.5 ? 'red' : record.dislikeRate >= 0.3 ? 'orange' : 'default'">
                  {{ (record.dislikeRate * 100).toFixed(1) }}%
                </a-tag>
              </template>
              <template v-else-if="column.key === 'lastReferencedAt'">
                <span class="cell-text">{{ formatTimeSafe(record.lastReferencedAt) }}</span>
              </template>
            </template>
          </a-table>
        </div>

        <!-- 休眠分块 -->
        <div class="advisor-section">
          <div class="advisor-section-header">
            <h3>
              <RestOutlined />
              休眠分块
            </h3>
            <span class="advisor-section-desc">
              最近 {{ summary.windowDays || 14 }} 天未被检索命中，建议优化分块策略或重新向量化
            </span>
          </div>
          <a-empty
            v-if="sleepingChunks.length === 0"
            description="暂无休眠分块"
            :image="Empty.PRESENTED_IMAGE_SIMPLE"
          />
          <a-table
            v-else
            :data-source="sleepingChunks"
            :columns="sleepingColumns"
            :pagination="false"
            :scroll="{ x: 820 }"
            :table-layout="'fixed'"
            size="small"
            row-key="chunkId"
            class="advisor-table"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'documentName'">
                <span class="cell-text" :title="record.documentName">{{ record.documentName || '-' }}</span>
              </template>
              <template v-else-if="column.key === 'contentPreview'">
                <a-tooltip :title="record.contentPreview" placement="topLeft">
                  <span class="cell-text">{{ record.contentPreview || '-' }}</span>
                </a-tooltip>
              </template>
              <template v-else-if="column.key === 'sleepingDays'">
                <a-tag :color="record.sleepingDays >= 60 ? 'red' : record.sleepingDays >= 30 ? 'orange' : 'default'">
                  {{ record.sleepingDays }} 天
                </a-tag>
              </template>
              <template v-else-if="column.key === 'lastReferencedAt'">
                <span class="cell-text">
                  {{ record.lastReferencedAt ? formatTimeSafe(record.lastReferencedAt) : '从未引用' }}
                </span>
              </template>
            </template>
          </a-table>
        </div>
      </a-spin>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Empty, message } from 'ant-design-vue'
import {
  LikeOutlined,
  DislikeOutlined,
  EyeOutlined,
  RestOutlined,
  WarningOutlined,
  ReloadOutlined,
} from '@ant-design/icons-vue'
import { formatTime } from '../utils/format'
import { getAdvisorSummary, getAdvisorLowRatedChunks, getAdvisorSleepingChunks } from '../api/knowledge'

const props = defineProps({
  knowledgeId: { type: String, required: true },
})

// 预设窗口选项
const PRESET_WINDOW_DAYS = [7, 14, 30, 90]
const presetOptions = [
  { value: 7, label: '最近 7 天' },
  { value: 14, label: '最近 14 天' },
  { value: 30, label: '最近 30 天' },
  { value: 90, label: '最近 90 天' },
]

const loading = ref(false)
const windowDays = ref(14)
const customDaysInput = ref(14)
const summary = reactive({
  totalReferences: 0,
  totalLikes: 0,
  totalDislikes: 0,
  likeRate: 0,
  referencedChunkCount: 0,
  sleepingChunkCount: 0,
  windowDays: 14,
})
const lowRatedChunks = ref([])
const sleepingChunks = ref([])

const likeRatePercent = computed(() => {
  const rate = summary.likeRate || 0
  return (rate * 100).toFixed(1)
})

// 是否自定义窗口（非预设值）
const isCustomWindow = computed(() => !PRESET_WINDOW_DAYS.includes(windowDays.value))

// 当前窗口对应的起止日期：今天往前推 N-1 天 ~ 今天
const customRangeText = computed(() => {
  const today = new Date()
  const start = new Date(today)
  start.setDate(start.getDate() - (customDaysInput.value - 1))
  const fmt = (d) => {
    const y = d.getFullYear()
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    return `${y}-${m}-${day}`
  }
  return `${fmt(start)} ~ ${fmt(today)}`
})

const lowRatedColumns = [
  { title: '文档', key: 'documentName', width: 180, ellipsis: true },
  { title: '内容预览', key: 'contentPreview', ellipsis: true },
  { title: '点赞', dataIndex: 'likeCount', key: 'likeCount', width: 70, align: 'center' },
  { title: '点踩', dataIndex: 'dislikeCount', key: 'dislikeCount', width: 70, align: 'center' },
  { title: '点踩率', key: 'dislikeRate', width: 90, align: 'center' },
  { title: '引用', dataIndex: 'referenceCount', key: 'referenceCount', width: 70, align: 'center' },
  { title: '最近引用', key: 'lastReferencedAt', width: 150, ellipsis: true },
]

const sleepingColumns = [
  { title: '文档', key: 'documentName', width: 180, ellipsis: true },
  { title: '内容预览', key: 'contentPreview', ellipsis: true },
  { title: '休眠天数', key: 'sleepingDays', width: 100, align: 'center' },
  { title: '引用', dataIndex: 'referenceCount', key: 'referenceCount', width: 80, align: 'center' },
  { title: '最近引用', key: 'lastReferencedAt', width: 150, ellipsis: true },
]

const formatTimeSafe = (t) => (t ? formatTime(t) : '-')

function onWindowDaysChange(val) {
  customDaysInput.value = val
  loadAll()
}

function applyCustomDays() {
  const v = Number(customDaysInput.value)
  if (!v || v < 1 || v > 365) {
    message.warning('请输入 1-365 之间的天数')
    return
  }
  windowDays.value = v
  loadAll()
}

async function loadAll() {
  if (!props.knowledgeId) return
  loading.value = true
  try {
    const [sumRes, lowRes, sleepRes] = await Promise.all([
      getAdvisorSummary(props.knowledgeId, windowDays.value),
      getAdvisorLowRatedChunks(props.knowledgeId, 10),
      getAdvisorSleepingChunks(props.knowledgeId, windowDays.value, 10),
    ])
    Object.assign(summary, sumRes?.data || {})
    lowRatedChunks.value = Array.isArray(lowRes?.data) ? lowRes.data : []
    sleepingChunks.value = Array.isArray(sleepRes?.data) ? sleepRes.data : []
  } catch (e) {
    message.error('加载反馈数据失败')

    console.error('[KnowledgeAdvisor] load failed:', e)
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.knowledge-advisor-tab {
  display: flex;
  flex-direction: column;
  /* 兼容父容器高度：rag-section 是 height: calc(100vh - 220px) 的 flex column */
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.advisor-toolbar {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 8px;
  flex-shrink: 0;
}

.advisor-toolbar-right {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.advisor-toolbar-label {
  font-size: 12px;
  color: var(--text-secondary, #999);
}

/* 主体滚动区域：超出 viewport 出垂直滚动条 */
.advisor-scroll-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 8px;
}

/* 下拉自定义区域：通过 :popup-class-name 全局类放宽宽度 */
:global(.advisor-window-dropdown) {
  min-width: 260px !important;
}

.advisor-custom-days {
  padding: 8px 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.advisor-custom-days-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: nowrap;
}

.advisor-custom-days-label {
  font-size: 12px;
  color: var(--text-secondary, #666);
  white-space: nowrap;
}

.advisor-custom-days-suffix {
  font-size: 12px;
  color: var(--text-secondary, #666);
  margin-right: auto;
}

.advisor-custom-days-hint {
  font-size: 11px;
  color: var(--text-tertiary, #999);
  font-family: 'Monaco', 'Consolas', monospace;
}

.advisor-cards {
  display: grid;
  /* 5 列网格：5 个卡片一行平铺 */
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  margin-bottom: 24px;
}

.advisor-card.card-stat,
.advisor-card.card-wide {
  grid-column: span 1;
}

/* 窄屏（< 900px）回退为单列自适应，避免卡片被过度压缩 */
@media (max-width: 900px) {
  .advisor-cards {
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  }
  .advisor-card.card-stat,
  .advisor-card.card-wide {
    grid-column: auto;
  }
}

.advisor-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: var(--color-canvas-soft);
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  min-width: 0;
}

.advisor-card.sleep-card {
  align-items: center;
}

.advisor-card-icon {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--color-link-bg-soft);
  color: #1677ff;
  font-size: 18px;
  font-weight: 600;
}

[data-theme='dark'] .advisor-card-icon {
  color: #60a5fa;
}

.advisor-card-icon.dislike {
  background: var(--color-error-bg);
  color: var(--color-error);
}

.advisor-card-icon.rate {
  background: var(--color-success-bg);
  color: var(--color-success);
  font-size: 13px;
}

.advisor-card-icon.ref {
  background: var(--color-warn-bg);
  color: var(--color-warning);
}

.advisor-card-icon.sleep {
  background: var(--color-purple-bg);
  color: #722ed1;
}

[data-theme='dark'] .advisor-card-icon.sleep {
  color: #a78bfa;
}

.advisor-card-body {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.advisor-card-value {
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
}

.advisor-card-label-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  flex-wrap: wrap;
}

.advisor-card-label {
  font-size: 12px;
  color: var(--color-body);
}

.advisor-card-tag {
  margin: 0;
  flex-shrink: 0;
  font-size: 11px;
  line-height: 16px;
  padding: 0 6px;
}

.advisor-section {
  margin-bottom: 24px;
}

.advisor-section-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.advisor-section-header h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.advisor-section-desc {
  font-size: 12px;
  color: var(--text-secondary, #999);
}

.advisor-table {
  width: 100%;
}

.advisor-table :deep(.cell-text) {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

/* 表格锁定列宽 */
.advisor-table :deep(.ant-table) {
  table-layout: fixed;
  word-break: break-all;
}

.advisor-table :deep(.ant-table-thead > tr > th) {
  white-space: nowrap;
}

/* 隐藏 antd 默认为滚动条预留的占位间距 */
.advisor-table :deep(.ant-table-content) {
  overflow-x: auto !important;
}
</style>
