<template>
  <div class="knowledge-graph-tab">
    <!-- 顶部工具栏 -->
    <div class="kg-toolbar">
      <div class="kg-toolbar-row">
        <div class="kg-toolbar-left">
          <a-input
            v-model:value="searchText"
            placeholder="搜索节点..."
            allow-clear
            size="small"
            class="kg-search"
            @press-enter="handleSearch"
          >
            <template #prefix><SearchOutlined /></template>
          </a-input>
          <a-button size="small" @click="handleSearch" :disabled="!searchText">搜索</a-button>
          <span class="kg-toolbar-divider"></span>
          <a-input
            v-model:value="semanticQuery"
            placeholder="语义搜索..."
            allow-clear
            size="small"
            class="kg-search-semantic"
            @press-enter="handleSemanticSearch"
          >
            <template #prefix><ThunderboltOutlined /></template>
          </a-input>
          <a-button size="small" @click="handleSemanticSearch" :loading="semanticSearching" :disabled="!semanticQuery">
            语义搜索
          </a-button>
          <a-popover trigger="click" placement="bottomLeft">
            <template #content>
              <div class="kg-semantic-config">
                <div class="kg-semantic-config-item">
                  <span class="kg-semantic-config-label">TopN</span>
                  <a-input-number
                    v-model:value="semanticTopN"
                    :min="1"
                    :max="100"
                    :precision="0"
                    size="small"
                    style="width: 100%"
                  />
                  <span class="kg-semantic-config-hint">返回条数上限</span>
                </div>
                <div class="kg-semantic-config-item">
                  <span class="kg-semantic-config-label">相似度阈值</span>
                  <a-input-number
                    v-model:value="semanticMinScore"
                    :min="0"
                    :max="1"
                    :step="0.05"
                    :precision="2"
                    size="small"
                    style="width: 100%"
                  />
                  <span class="kg-semantic-config-hint">低于该值的结果不高亮（0~1）</span>
                </div>
              </div>
            </template>
            <a-button size="small">
              <template #icon><SettingOutlined /></template>
              参数
            </a-button>
          </a-popover>
          <a-tooltip>
            <template #title>
              <div style="max-width: 260px">
                <div style="font-weight: 600; margin-bottom: 4px">什么是语义搜索？</div>
                <div>基于向量相似度匹配节点含义，而非精确匹配文字。命中节点按相似度高亮，低于阈值的结果不展示。</div>
                <div style="margin-top: 6px; color: var(--color-mute)">
                  示例：搜索"数据库技术"可以找到"MySQL"、"PostgreSQL"等节点
                </div>
              </div>
            </template>
            <QuestionCircleOutlined class="kg-semantic-help" />
          </a-tooltip>
          <!-- 共用清除高亮按钮：放在两种搜索之后，对节点搜索 / 语义搜索同时生效 -->
          <a-tooltip v-if="searchKeywords.length > 0" title="清除高亮">
            <a-button size="small" class="kg-clear-btn" @click="handleClearSearch">
              <template #icon><ClearOutlined /></template>
            </a-button>
          </a-tooltip>
        </div>
        <div class="kg-toolbar-right">
          <!-- 单文档模式：重新抽取按钮 -->
          <a-popconfirm
            v-if="documentId && stats.nodeCount > 0"
            title="确定重新抽取该文档的知识图谱？已有数据将被覆盖。"
            ok-text="重新抽取"
            cancel-text="取消"
            @confirm="handleExtractSingleDoc"
          >
            <a-button size="small" :loading="extracting">
              <template #icon><RedoOutlined /></template>
              重新抽取
            </a-button>
          </a-popconfirm>
          <!-- 单文档模式：清空图谱按钮 -->
          <a-popconfirm
            v-if="documentId && stats.nodeCount > 0"
            title="确定清空该文档的知识图谱？此操作不可恢复。"
            ok-text="清空"
            cancel-text="取消"
            @confirm="handleDeleteDocGraph"
          >
            <a-button size="small" danger :loading="deleting">
              <template #icon><DeleteOutlined /></template>
              清空图谱
            </a-button>
          </a-popconfirm>
          <!-- 知识库模式：下拉选择抽取方式 -->
          <template v-if="!documentId">
            <a-dropdown :trigger="['click']">
              <a-button size="small" :loading="extracting">
                <template #icon><RobotOutlined /></template>
                AI 抽取图谱
              </a-button>
              <template #overlay>
                <a-menu @click="({ key }) => handleExtractMenu(key)">
                  <a-menu-item key="single">单文档抽取</a-menu-item>
                  <a-menu-item key="multi">多文档合并抽取</a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
            <a-popconfirm
              v-if="stats.nodeCount > 0"
              title="确定清空知识图谱？"
              ok-text="清空"
              cancel-text="取消"
              @confirm="handleDeleteGraph"
            >
              <a-button size="small" danger :loading="deleting">
                <template #icon><DeleteOutlined /></template>
                清空图谱
              </a-button>
            </a-popconfirm>
          </template>
        </div>
      </div>
    </div>

    <!-- 统计信息 -->
    <div v-if="stats.nodeCount > 0" class="kg-stats">
      <span class="kg-stat-item">
        <span class="kg-stat-label">节点</span>
        <span class="kg-stat-value">{{ displayNodeCount }}</span>
        <span v-if="displayNodeCount !== stats.nodeCount" class="kg-stat-total">/ {{ stats.nodeCount }}</span>
      </span>
      <span class="kg-stat-item">
        <span class="kg-stat-label">边</span>
        <span class="kg-stat-value">{{ displayEdgeCount }}</span>
        <span v-if="displayEdgeCount !== stats.edgeCount" class="kg-stat-total">/ {{ stats.edgeCount }}</span>
      </span>
    </div>

    <!-- 图谱画布 -->
    <div class="kg-canvas-wrapper" ref="canvasWrapperRef">
      <div v-if="graphReady && !hasRunningTask" class="kg-canvas" ref="canvasRef"></div>
      <div v-if="graphReady && !hasRunningTask" class="kg-fit-btn">
        <a-tooltip title="适应画布" placement="left">
          <a-button shape="circle" size="small" @click="handleFitView">
            <template #icon><CompressOutlined /></template>
          </a-button>
        </a-tooltip>
      </div>
      <div v-else class="kg-empty">
        <a-spin v-if="loading" tip="加载图谱中..." />
        <template v-else>
          <template v-if="hasRunningTask">
            <LoadingOutlined style="font-size: 28px; color: var(--blue-500); margin-bottom: 12px" />
            <p>知识图谱抽取任务已提交，请等待</p>
            <a-button size="small" @click="loadGraphData">刷新状态</a-button>
          </template>
          <template v-else>
            <p>暂无知识图谱</p>
            <p v-if="documentId" class="kg-empty-hint">点击下方按钮从该文档中抽取实体关系</p>
            <p v-else class="kg-empty-hint">点击「AI 抽取图谱」从已有文档中自动抽取实体关系</p>
            <a-button
              v-if="documentId"
              type="primary"
              size="small"
              :loading="extracting"
              @click="handleExtractSingleDoc"
            >
              <template #icon><RobotOutlined /></template>
              抽取知识图谱
            </a-button>
          </template>
        </template>
      </div>

      <!-- 节点/边详情面板：浮在画布右上角，不遮挡节点 -->
      <LbGraphNodeDetailPanel
        :visible="detailVisible"
        :title="detailTitle"
        :is-edge="detailType === 'edge'"
        :fields="detailFields"
        @close="detailVisible = false"
      >
        <template v-if="!props.documentId" #actions>
          <a-button v-if="detailType === 'node'" size="small" danger @click="confirmDeleteNode">删除节点</a-button>
          <a-button v-else-if="detailType === 'edge'" size="small" danger @click="confirmDeleteEdge">删除关系</a-button>
        </template>
      </LbGraphNodeDetailPanel>
    </div>

    <!-- 文档选择弹窗 -->
    <a-modal v-model:open="docExtractVisible" :width="520" :footer="null" :mask-closable="false">
      <template #title>
        {{ extractMode === 'single' ? '单文档抽取' : '多文档合并抽取' }}
        <a-tooltip
          :title="
            extractMode === 'single'
              ? '为每个选中的文档分别创建抽取任务，各自独立生成该文档的知识图谱。可批量选择多个文档同时提交。'
              : '将选中的多个文档内容合并，统一抽取实体关系，构建一个完整的知识库级知识图谱。所有文档的实体和关系会融合在同一张图谱中。'
          "
        >
          <QuestionCircleOutlined style="margin-left: 6px; font-size: 14px; color: var(--color-mute); cursor: help" />
        </a-tooltip>
      </template>
      <div class="dialog-scroll-body">
        <div class="doc-extract-content">
          <!-- 全选 -->
          <div class="doc-extract-header">
            <a-checkbox
              :checked="allDocSelected"
              :indeterminate="selectedDocIds.length > 0 && !allDocSelected"
              @change="(e) => handleToggleAllDoc(e.target.checked)"
            >
              全选（{{ docList.length }} 个已完成文档）
            </a-checkbox>
            <span v-if="extractMode === 'single' && selectedDocIds.length > 1" class="doc-extract-batch-hint">
              将为 {{ selectedDocIds.length }} 个文档分别创建抽取任务
            </span>
          </div>
          <a-spin :spinning="docListLoading">
            <div class="doc-extract-list">
              <div v-for="doc in docList" :key="doc.id" class="doc-extract-item">
                <a-checkbox
                  :checked="selectedDocIds.includes(doc.id)"
                  @change="
                    (e) => {
                      if (e.target.checked) selectedDocIds.push(doc.id)
                      else selectedDocIds = selectedDocIds.filter((id) => id !== doc.id)
                    }
                  "
                >
                  <a-tooltip :title="doc.name" placement="topLeft">
                    <span class="doc-extract-name">{{ doc.name }}</span>
                  </a-tooltip>
                </a-checkbox>
              </div>
              <div v-if="!docListLoading && docList.length === 0" class="doc-extract-empty">
                暂无已完成的文档，请先上传并入库文档
              </div>
            </div>
          </a-spin>
          <div class="doc-extract-actions">
            <div class="doc-extract-btn-row">
              <a-button
                type="primary"
                size="small"
                :disabled="selectedDocIds.length === 0"
                @click="handleExtractSelected"
                :loading="extracting"
              >
                {{
                  extractMode === 'single' ? `下一步（${selectedDocIds.length}）` : `下一步（${selectedDocIds.length}）`
                }}
              </a-button>
            </div>
          </div>
        </div>
      </div>
    </a-modal>

    <!-- 抽取配置弹窗 -->
    <a-modal
      v-model:open="extractConfigVisible"
      title="图谱抽取配置"
      :width="560"
      :mask-closable="false"
      @ok="handleExtractConfigSubmit"
    >
      <div class="dialog-scroll-body">
        <a-form layout="vertical">
          <a-form-item label="抽取模型">
            <ModelSelect
              v-model="extractConfigForm.modelValue"
              style="width: 100%"
              placeholder="不选择则使用系统默认模型"
              @change="onExtractConfigModelChange"
            />
          </a-form-item>
          <a-form-item label="Schema">
            <a-textarea
              v-model:value="extractConfigForm.schema"
              :rows="5"
              placeholder="描述实体类型、关系类型和属性约束，会拼接到抽取 Prompt 尾部。例如：&#10;实体类型：人物、组织、项目、技术&#10;关系类型：负责、隶属于、使用、包含"
            />
          </a-form-item>
          <a-form-item>
            <template #label>
              并发队列数
              <a-tooltip title="同时并发抽取的协程数量，越大抽取速度越快，但会消耗更多模型 API 并发额度。建议 10-50。">
                <QuestionCircleOutlined
                  style="margin-left: 4px; font-size: 13px; color: var(--color-mute); cursor: help"
                />
              </a-tooltip>
            </template>
            <a-input-number
              v-model:value="extractConfigForm.concurrency"
              :min="1"
              :max="1000"
              :step="1"
              style="width: 100%"
            />
          </a-form-item>
          <a-form-item>
            <template #label>
              模型参数 JSON
              <a-tooltip title="传给模型的额外参数，如 temperature、maxTokens 等。不同模型支持的参数不同。">
                <QuestionCircleOutlined
                  style="margin-left: 4px; font-size: 13px; color: var(--color-mute); cursor: help"
                />
              </a-tooltip>
            </template>
            <JsonInput
              v-model="extractConfigForm.modelParamsText"
              :rows="3"
              placeholder='例如 {"temperature": 0.1, "maxTokens": 4096}'
            />
          </a-form-item>
        </a-form>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, watch, nextTick, h } from 'vue'
import { useTheme } from '../composables/useTheme'
import {
  SearchOutlined,
  RobotOutlined,
  DeleteOutlined,
  CompressOutlined,
  LoadingOutlined,
  RedoOutlined,
  ClearOutlined,
  QuestionCircleOutlined,
  ThunderboltOutlined,
  SettingOutlined,
} from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import {
  getGraphSubgraph,
  getGraphStats,
  extractGraph,
  deleteGraph,
  deleteDocGraph,
  deleteGraphNode,
  deleteGraphEdge,
  getDocuments,
  getExistingDocIds,
  semanticSearchKnowledgeGraph,
} from '../api/knowledge'
import ModelSelect from './ModelSelect.vue'
import JsonInput from './JsonInput.vue'
import LbGraphNodeDetailPanel from './common/LbGraphNodeDetailPanel.vue'
import { sanitizeGraphHtml, escapeHtml } from '../utils/sanitize'
import { COLOR_PALETTE, LAYOUT_CONFIG, formatGraphData } from '../composables/useKnowledgeGraphConfig'

const { isDark } = useTheme()

const graphColors = computed(() =>
  isDark.value
    ? {
        labelFill: '#ffffff',
        edgeLabel: '#d1d5db',
        edgeStroke: '#555',
        edgeArrow: '#555',
        badgeBg: '#3b82f6',
        badgeText: '#ffffff',
        nodeStroke: '#1e1f1f',
        nodeShadow: 'rgba(0,0,0,0.5)',
      }
    : {
        labelFill: '#1e293b',
        edgeLabel: '#6b7280',
        edgeStroke: '#d1d5db',
        edgeArrow: '#d1d5db',
        badgeBg: '#2563eb',
        badgeText: '#ffffff',
        nodeStroke: '#ffffff',
        nodeShadow: '#d1d5db',
      }
)

const props = defineProps({
  knowledgeId: { type: [String, Number], required: true },
  documentId: { type: [String, Number], default: null },
  docTotal: { type: Number, default: 0 },
})

// ---- state ----
const loading = ref(false)
const extracting = ref(false)
const deleting = ref(false)
const graphReady = ref(false)
const searchText = ref('')
const searchKeywords = ref([])
const semanticQuery = ref('')
const semanticTopN = ref(10)
const semanticMinScore = ref(0.5)
const semanticSearching = ref(false)

const stats = reactive({ nodeCount: 0, edgeCount: 0 })
const hasRunningTask = ref(false)
const displayNodeCount = ref(0)
const displayEdgeCount = ref(0)

const detailVisible = ref(false)
const detailType = ref('') // 'node' | 'edge'
const selectedNode = ref(null)
const selectedEdge = ref(null)

const canvasRef = ref(null)
const canvasWrapperRef = ref(null)

// ---- 文档选择弹窗 ----
const docExtractVisible = ref(false)
const docList = ref([])
const docListLoading = ref(false)
const selectedDocIds = ref([])
const extractMode = ref('multi') // 'single' | 'multi'

// ---- 抽取配置弹窗 ----
const extractConfigVisible = ref(false)
const extractConfigForm = reactive({
  modelValue: '',
  schema: '',
  concurrency: 50,
  modelParamsText: '',
})
const extractConfigModel = ref({ providerId: null, modelId: null })
const pendingExtractIds = ref([]) // 暂存待抽取的文档 ID 列表
const pendingExtractMode = ref('') // 暂存抽取模式

function onExtractConfigModelChange({ providerId, modelId }) {
  extractConfigModel.value = { providerId, modelId }
}

/**
 * 打开抽取配置弹窗
 * @param {Array} docIds 待抽取的文档 ID 列表
 * @param {string} mode 抽取模式 'single' | 'multi' | 'singleDoc'
 */
function openExtractConfig(docIds, mode) {
  pendingExtractIds.value = docIds
  pendingExtractMode.value = mode
  // 重置表单
  extractConfigForm.modelValue = ''
  extractConfigForm.schema = ''
  extractConfigForm.concurrency = 50
  extractConfigForm.modelParamsText = ''
  extractConfigModel.value = { providerId: null, modelId: null }
  extractConfigVisible.value = true
}

let graphInstance = null
let resizeObserver = null

// ---- computed ----
const detailTitle = computed(() => {
  if (detailType.value === 'node') return '节点详情'
  if (detailType.value === 'edge') return '关系详情'
  return '详情'
})

// 节点/边字段集合：驱动 LbGraphNodeDetailPanel 的 a-descriptions 渲染
const detailFields = computed(() => {
  if (detailType.value === 'node' && selectedNode.value) {
    const n = selectedNode.value
    return [
      { label: '名称', value: n.name },
      n.score != null ? { label: '相似度', value: `${(n.score * 100).toFixed(1)}%` } : null,
      { label: '类型', value: n.entityType },
      { label: '描述', value: n.description },
      { label: '来源', value: n.source },
      { label: '文档ID', value: n.documentId },
    ].filter(Boolean)
  }
  if (detailType.value === 'edge' && selectedEdge.value) {
    const e = selectedEdge.value
    return [
      { label: '关系类型', value: e.relationType },
      { label: '描述', value: e.description },
      { label: '权重', value: e.weight },
      { label: '来源', value: e.source },
    ]
  }
  return []
})

// ---- 颜色调色板 / 布局 / 数据格式化：见 useKnowledgeGraphConfig ----

// ---- 加载图谱数据 ----
let initRetryTimer = null
let loadSeq = 0
async function loadGraphData() {
  const seq = ++loadSeq
  loading.value = true
  hasRunningTask.value = false
  graphReady.value = false
  try {
    const params = props.documentId ? { documentId: props.documentId } : {}
    const [subgraphRes, statsRes] = await Promise.all([
      getGraphSubgraph(props.knowledgeId, params),
      getGraphStats(props.knowledgeId, props.documentId),
    ])
    // 后续有更新的调用，丢弃本次结果
    if (seq !== loadSeq) return

    const subgraph = subgraphRes.data
    const statsData = statsRes.data || {}
    Object.assign(stats, {
      nodeCount: statsData.nodeCount || 0,
      edgeCount: statsData.edgeCount || 0,
    })
    hasRunningTask.value = !!statsData.hasRunningTask

    if (stats.nodeCount === 0) {
      loading.value = false
      return
    }

    displayNodeCount.value = subgraph.nodes?.length || 0
    displayEdgeCount.value = subgraph.edges?.length || 0

    graphReady.value = true
    await nextTick()
    if (seq !== loadSeq) return

    renderGraph(subgraph, seq)
  } catch (e) {
    if (seq !== loadSeq) return
    console.error('[知识图谱] 加载失败', e)
    message.error('加载知识图谱失败')
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function renderGraph(subgraph, seq) {
  // 清理旧实例
  if (graphInstance) {
    try {
      graphInstance.destroy()
    } catch {
      /* ignore */
    }
    graphInstance = null
  }
  clearTimeout(initRetryTimer)

  // 确保 canvas 已挂载且有尺寸
  if (!canvasRef.value || canvasRef.value.offsetWidth === 0 || canvasRef.value.offsetHeight === 0) {
    initRetryTimer = setTimeout(() => {
      if (seq === loadSeq) renderGraph(subgraph, seq)
    }, 50)
    return
  }

  // G6 体积大（~2.2MB），仅在真正需要渲染图谱时按需加载
  const { Graph } = await import('@antv/g6')

  canvasRef.value.innerHTML = ''
  const width = canvasRef.value.offsetWidth
  const height = canvasRef.value.offsetHeight

  graphInstance = new Graph({
    container: canvasRef.value,
    width,
    height,
    autoFit: 'view',
    autoResize: true,
    layout: LAYOUT_CONFIG,
    node: {
      type: 'circle',
      style: {
        labelText: (d) => d.data.label,
        labelFill: graphColors.value.labelFill,
        labelWordWrap: true,
        labelMaxWidth: '300%',
        badge: (d) => d.data.score != null,
        badges: (d) => {
          if (d.data.score == null) return []
          return [
            {
              text: `${(d.data.score * 100).toFixed(1)}%`,
              placement: 'right-top',
              backgroundFill: graphColors.value.badgeBg,
              fill: graphColors.value.badgeText,
              fontSize: 10,
              padding: [2, 5],
              backgroundRadius: 8,
            },
          ]
        },
        size: (d) => {
          const deg = d.data.degree || 0
          return Math.min(15 + deg * 5, 50)
        },
        opacity: 0.9,
        stroke: graphColors.value.nodeStroke,
        lineWidth: 1.5,
        shadowColor: graphColors.value.nodeShadow,
        shadowBlur: 4,
      },
      palette: {
        field: 'label',
        color: COLOR_PALETTE,
      },
    },
    edge: {
      type: 'quadratic',
      style: {
        controlPoints: (d) => {
          const offset = d.data?.curveOffset
          if (!offset) return undefined
          const sx = d.sourceNode?.style?.x ?? d.sourceNode?.x ?? 0
          const sy = d.sourceNode?.style?.y ?? d.sourceNode?.y ?? 0
          const tx = d.targetNode?.style?.x ?? d.targetNode?.x ?? 0
          const ty = d.targetNode?.style?.y ?? d.targetNode?.y ?? 0
          const mx = (sx + tx) / 2
          const my = (sy + ty) / 2
          const dx = tx - sx
          const dy = ty - sy
          const len = Math.sqrt(dx * dx + dy * dy) || 1
          return [{ x: mx + (-dy / len) * offset, y: my + (dx / len) * offset }]
        },
        labelText: (d) => d.data.label || '',
        labelFill: graphColors.value.edgeLabel,
        labelFontSize: 10,
        stroke: graphColors.value.edgeStroke,
        lineWidth: 1,
        endArrow: true,
        endArrowSize: 6,
        endArrowFill: graphColors.value.edgeArrow,
      },
    },
    behaviors: [
      'drag-canvas',
      'zoom-canvas',
      { type: 'drag-element', key: 'drag-element', disable: true },
      {
        type: 'click-select',
        degree: 1,
        state: 'selected',
        neighborState: 'active',
        unselectedState: 'inactive',
        multiple: true,
        trigger: ['shift'],
        disableDefault: false,
      },
    ],
  })

  graphInstance.on('node:click', (evt) => {
    const nodeId = evt.target.id
    const nodeData = graphInstance.getNodeData(nodeId)
    const original = nodeData?.data?.original
    if (original) {
      selectedNode.value = {
        ...original,
        score: nodeData?.data?.score ?? original.score,
      }
      detailType.value = 'node'
      detailVisible.value = true
    }
  })
  graphInstance.on('edge:click', (evt) => {
    const edgeId = evt.target.id
    const edgeData = graphInstance.getEdgeData(edgeId)
    const original = edgeData?.data?.original
    if (original) {
      selectedEdge.value = { ...original }
      detailType.value = 'edge'
      detailVisible.value = true
    }
  })
  graphInstance.on('canvas:click', () => {
    detailVisible.value = false
  })
  // 布局动画结束后启用节点拖拽
  graphInstance.on('afterlayout', () => {
    graphInstance.updateBehavior({ key: 'drag-element', disable: false })
  })

  const data = formatGraphData(subgraph)
  graphInstance.setData(data)
  graphInstance.render()

  // 监听容器尺寸变化
  if (resizeObserver && canvasRef.value) {
    resizeObserver.unobserve(canvasRef.value)
  }
  resizeObserver = new ResizeObserver(() => {
    if (graphInstance && canvasRef.value) {
      const w = canvasRef.value.offsetWidth
      const h = canvasRef.value.offsetHeight
      graphInstance.changeSize(w, h)
    }
  })
  resizeObserver.observe(canvasRef.value)
}

// ---- AI 抽取 ----
function handleExtractMenu(key) {
  if (props.docTotal === 0) {
    Modal.warning({ title: '暂无文档', content: '知识库中暂无文档，请先上传文档后再抽取知识图谱。' })
    return
  }
  extractMode.value = key === 'single' ? 'single' : 'multi'
  openDocPicker()
}

async function openDocPicker() {
  docExtractVisible.value = true
  docListLoading.value = true
  selectedDocIds.value = []
  try {
    const res = await getDocuments(props.knowledgeId, { pageNum: 1, pageSize: 200 })
    docList.value = (res.data?.records || []).filter((d) => d.status === 'completed' || d.status?.code === 'completed')
  } catch (e) {
    console.error('[知识图谱] 加载文档列表失败', e)
    message.error('加载文档列表失败')
  } finally {
    docListLoading.value = false
  }
}

const allDocSelected = computed(() => docList.value.length > 0 && selectedDocIds.value.length === docList.value.length)

function handleToggleAllDoc(checked) {
  selectedDocIds.value = checked ? docList.value.map((d) => d.id) : []
}

async function handleExtractSelected() {
  const ids = selectedDocIds.value
  if (ids.length === 0) {
    message.warning('请至少选择一个文档')
    return
  }

  // 单文档模式：检查哪些文档已有图谱，列出文档名提醒
  if (extractMode.value === 'single') {
    try {
      const res = await getExistingDocIds(props.knowledgeId, ids)
      const existingIds = (res.data || []).map(String)
      if (existingIds.length > 0) {
        const nameMap = Object.fromEntries(docList.value.map((d) => [String(d.id), d.name]))
        const names = existingIds.map((id) => nameMap[id] || `文档${id}`)
        // 文档名来自数据库（用户可控），先转义再拼成 HTML，避免 Modal 内容被注入
        const docNamesHtml = names.map((n) => `<div>· ${escapeHtml(n)}</div>`).join('')
        Modal.confirm({
          title: '已有图谱数据',
          content: h('div', [
            h('p', `以下 ${names.length} 个文档已有知识图谱数据，重新抽取将覆盖：`),
            // 文档名经 escapeHtml 转义，再经 sanitizeGraphHtml 兜底净化
            h('div', { innerHTML: sanitizeGraphHtml(docNamesHtml) }),
            h('p', '是否继续？'),
          ]),
          okText: '继续配置',
          cancelText: '取消',
          onOk: () => {
            docExtractVisible.value = false
            openExtractConfig(ids, extractMode.value)
          },
        })
        return
      }
    } catch (e) {
      console.warn('[知识图谱] 检查文档图谱状态失败', e)
    }
  }

  // 多文档模式：检查知识库级图谱
  if (extractMode.value === 'multi' && stats.nodeCount > 0) {
    Modal.confirm({
      title: '重新抽取确认',
      content: `当前知识图谱已有 ${stats.nodeCount} 个节点，多文档合并抽取将覆盖整个知识图谱，是否继续？`,
      okText: '继续配置',
      cancelText: '取消',
      onOk: () => {
        docExtractVisible.value = false
        openExtractConfig(ids, extractMode.value)
      },
    })
  } else {
    docExtractVisible.value = false
    openExtractConfig(ids, extractMode.value)
  }
}

/**
 * 解析模型参数 JSON
 */
function parseModelParams() {
  const text = extractConfigForm.modelParamsText?.trim()
  if (!text) return null
  try {
    const obj = JSON.parse(text)
    if (typeof obj !== 'object' || obj === null || Array.isArray(obj)) {
      message.warning('模型参数必须是 JSON 对象格式')
      return null
    }
    return obj
  } catch {
    message.warning('模型参数 JSON 格式不正确，请检查')
    return null
  }
}

/**
 * 抽取配置弹窗确认
 */
async function handleExtractConfigSubmit() {
  const ids = pendingExtractIds.value
  const mode = pendingExtractMode.value
  if (!ids || ids.length === 0) {
    message.warning('没有待抽取的文档')
    return
  }

  const modelParams = parseModelParams()
  if (extractConfigForm.modelParamsText?.trim() && modelParams === null) return

  extractConfigVisible.value = false
  extracting.value = true
  try {
    const { providerId, modelId } = extractConfigModel.value
    const baseData = {
      providerId: providerId || undefined,
      modelId: modelId || undefined,
      schema: extractConfigForm.schema?.trim() || undefined,
      concurrency: extractConfigForm.concurrency || 50,
      modelParams: modelParams || undefined,
    }

    if (mode === 'single') {
      // 单文档模式：为每个文档分别创建独立的抽取任务
      for (const docId of ids) {
        await extractGraph(props.knowledgeId, { ...baseData, documentIds: [docId] })
      }
      message.success(`已提交 ${ids.length} 个文档的图谱抽取任务，请前往任务中心查看进度`)
    } else if (mode === 'singleDoc') {
      // 单文档模式（从空状态/重新抽取按钮触发）
      await extractGraph(props.knowledgeId, { ...baseData, documentIds: ids })
      message.success('图谱抽取任务已提交，请前往任务中心查看进度')
    } else {
      // 多文档模式：合并为一个任务，统一构建知识图谱
      await extractGraph(props.knowledgeId, { ...baseData, documentIds: ids })
      message.success(`已提交多文档合并抽取任务（${ids.length} 个文档），请前往任务中心查看进度`)
    }
    hasRunningTask.value = true
    graphReady.value = false
  } catch (e) {
    console.error('[知识图谱] 抽取失败', e)
  } finally {
    extracting.value = false
  }
}

// 文档模式：单文档抽取（从空状态按钮或重新抽取触发）
function handleExtractSingleDoc() {
  if (!props.documentId) return
  openExtractConfig([props.documentId], 'singleDoc')
}

// ---- 清空图谱 ----
async function handleDeleteGraph() {
  deleting.value = true
  try {
    await deleteGraph(props.knowledgeId)
    message.success('图谱已清空')
    graphReady.value = false
    Object.assign(stats, { nodeCount: 0, edgeCount: 0 })
    displayNodeCount.value = 0
    displayEdgeCount.value = 0
    if (graphInstance) {
      try {
        graphInstance.destroy()
      } catch {
        /* ignore */
      }
      graphInstance = null
    }
  } catch (e) {
    console.error('[知识图谱] 清空失败', e)
  } finally {
    deleting.value = false
  }
}

async function handleDeleteDocGraph() {
  if (!props.documentId) return
  deleting.value = true
  try {
    await deleteDocGraph(props.knowledgeId, props.documentId)
    message.success('文档图谱已清空')
    graphReady.value = false
    Object.assign(stats, { nodeCount: 0, edgeCount: 0 })
    displayNodeCount.value = 0
    displayEdgeCount.value = 0
    if (graphInstance) {
      try {
        graphInstance.destroy()
      } catch {
        /* ignore */
      }
      graphInstance = null
    }
  } catch (e) {
    console.error('[知识图谱] 清空文档图谱失败', e)
  } finally {
    deleting.value = false
  }
}

// ---- 搜索 ----
function handleSearch() {
  if (!searchText.value?.trim()) return
  const keyword = searchText.value.trim()
  searchKeywords.value = [keyword]

  if (!graphInstance) return

  const { nodes, edges } = graphInstance.getData()
  const updates = {}
  const matchedIds = new Set()

  nodes.forEach((node) => {
    const label = node.data.label || ''
    const match = label.toLowerCase().includes(keyword.toLowerCase())
    if (match) {
      matchedIds.add(node.id)
      updates[node.id] = ['highlighted']
    } else {
      updates[node.id] = ['inactive']
    }
  })

  // 匹配的节点的邻居也高亮
  edges.forEach((e) => {
    if (matchedIds.has(e.source) || matchedIds.has(e.target)) {
      updates[e.source] = ['highlighted']
      updates[e.target] = ['highlighted']
    }
  })

  graphInstance.setElementState(updates)

  // 更新显示数量
  displayNodeCount.value = Object.values(updates).filter((v) => v[0] === 'highlighted').length
}

async function handleSemanticSearch() {
  if (!semanticQuery.value?.trim()) return
  const topN = semanticTopN.value > 0 ? semanticTopN.value : 10
  const minScore = semanticMinScore.value ?? undefined
  semanticSearching.value = true
  try {
    const res = await semanticSearchKnowledgeGraph(
      props.knowledgeId,
      semanticQuery.value.trim(),
      topN,
      minScore,
      props.documentId || undefined
    )
    const nodes = res.data || []
    if (nodes.length === 0) {
      message.info('未找到高于阈值的匹配节点')
      return
    }

    if (graphInstance) {
      const scoreMap = new Map(nodes.map((n) => [n.name, n.score]))
      const { nodes: graphNodes, edges } = graphInstance.getData()
      const updates = {}
      const nodeDataUpdates = []

      graphNodes.forEach((node) => {
        const label = node.data.label || ''
        if (scoreMap.has(label)) {
          updates[node.id] = ['highlighted']
          nodeDataUpdates.push({ id: node.id, data: { score: scoreMap.get(label) } })
        } else {
          updates[node.id] = ['inactive']
          if (node.data.score != null) {
            nodeDataUpdates.push({ id: node.id, data: { score: undefined } })
          }
        }
      })

      edges.forEach((e) => {
        if (updates[e.source]?.[0] === 'highlighted' || updates[e.target]?.[0] === 'highlighted') {
          updates[e.source] = ['highlighted']
          updates[e.target] = ['highlighted']
        }
      })

      if (nodeDataUpdates.length > 0) {
        graphInstance.updateNodeData(nodeDataUpdates)
      }
      graphInstance.setElementState(updates)
      searchKeywords.value = [semanticQuery.value.trim()]
      displayNodeCount.value = Object.values(updates).filter((v) => v[0] === 'highlighted').length
    }
  } catch (e) {
    console.error('[知识图谱] 语义搜索失败', e)
  } finally {
    semanticSearching.value = false
  }
}

function handleClearSearch() {
  searchKeywords.value = []
  searchText.value = ''
  semanticQuery.value = ''
  if (!graphInstance) return

  const { nodes, edges } = graphInstance.getData()
  const updates = {}
  const nodeDataUpdates = []
  nodes.forEach((n) => {
    updates[n.id] = []
    if (n.data.score != null) {
      nodeDataUpdates.push({ id: n.id, data: { score: undefined } })
    }
  })
  edges.forEach((e) => (updates[e.id] = []))
  if (nodeDataUpdates.length > 0) {
    graphInstance.updateNodeData(nodeDataUpdates)
  }
  graphInstance.setElementState(updates)

  displayNodeCount.value = stats.nodeCount
  displayEdgeCount.value = stats.edgeCount
}

// ---- 适应画布 ----
function handleFitView() {
  if (graphInstance) {
    try {
      graphInstance.fitView()
    } catch {
      /* ignore */
    }
  }
}

// ---- 删除节点 ----
function confirmDeleteNode() {
  Modal.confirm({
    title: '确定删除该节点及其关联的边？',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: handleDeleteNode,
  })
}

async function handleDeleteNode() {
  if (!selectedNode.value) return
  try {
    await deleteGraphNode(props.knowledgeId, selectedNode.value.elementId)
    message.success('节点已删除')
    detailVisible.value = false
    loadGraphData()
  } catch (e) {
    console.error('[知识图谱] 删除节点失败', e)
  }
}

// ---- 删除边 ----
function confirmDeleteEdge() {
  Modal.confirm({
    title: '确定删除该关系？',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: handleDeleteEdge,
  })
}

async function handleDeleteEdge() {
  if (!selectedEdge.value) return
  try {
    await deleteGraphEdge(props.knowledgeId, selectedEdge.value.elementId)
    message.success('关系已删除')
    detailVisible.value = false
    loadGraphData()
  } catch (e) {
    console.error('[知识图谱] 删除边失败', e)
  }
}

// ---- 生命周期 ----
onMounted(() => {
  loadGraphData()
})

onUnmounted(() => {
  clearTimeout(initRetryTimer)
  if (resizeObserver && canvasRef.value) {
    resizeObserver.unobserve(canvasRef.value)
  }
  if (graphInstance) {
    try {
      graphInstance.destroy()
    } catch {
      /* ignore */
    }
    graphInstance = null
  }
})

// 切换 tab 或文档时重新加载
watch(
  () => [props.knowledgeId, props.documentId],
  () => {
    loadGraphData()
  }
)

// 切换主题时重新渲染图谱颜色
watch(isDark, () => {
  if (graphInstance && graphReady.value) {
    loadGraphData()
  }
})
</script>

<style scoped>
.knowledge-graph-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 500px;
}

.kg-toolbar {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 12px;
  margin-bottom: 8px;
}

.kg-toolbar-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.kg-toolbar-left {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 8px;
  min-width: 0;
}

.kg-toolbar-right {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}

.kg-toolbar-divider {
  width: 1px;
  height: 18px;
  background: var(--color-hairline);
  margin: 0 4px;
}

.kg-search {
  width: 180px;
}

.kg-search-semantic {
  width: 200px;
}

.kg-semantic-help {
  color: var(--color-mute);
  cursor: help;
  font-size: 13px;
}

.kg-semantic-config {
  width: 200px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.kg-semantic-config-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.kg-semantic-config-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-ink);
}

.kg-semantic-config-hint {
  font-size: 11px;
  color: var(--color-mute);
}

.kg-stats {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 4px 12px;
  margin-bottom: 8px;
  background: var(--color-canvas-soft);
  border: 1px solid var(--color-hairline);
  border-radius: 6px;
  font-size: 13px;
}

.kg-stat-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.kg-stat-label {
  color: var(--color-mute);
  font-weight: 500;
}

.kg-stat-value {
  color: var(--color-ink);
  font-weight: 600;
}

.kg-stat-total {
  color: var(--color-mute);
  font-size: 11px;
}

.kg-canvas-wrapper {
  flex: 1;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  overflow: hidden;
  position: relative;
  min-height: 400px;
}

.kg-canvas {
  width: 100%;
  height: 100%;
  background: var(--kg-canvas-bg);
}

.kg-fit-btn {
  position: absolute;
  right: 12px;
  bottom: 12px;
  z-index: 10;
}

.kg-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--color-mute);
}

.kg-empty p {
  margin-bottom: 8px;
}

.kg-empty-hint {
  font-size: 13px;
  color: var(--color-mute);
}

/* 文档选择弹窗 */
.doc-extract-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.doc-extract-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--color-hairline);
}
.doc-extract-batch-hint {
  font-size: 12px;
  color: var(--color-warning);
}
.doc-extract-list {
  max-height: 400px;
  overflow-x: hidden;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.doc-extract-item {
  display: flex;
  align-items: center;
  padding: 6px 8px;
  border-radius: 6px;
  transition: background 0.15s;
  min-width: 0;
}
.doc-extract-item:hover {
  background: var(--color-canvas-soft);
}
.doc-extract-item :deep(.ant-checkbox-wrapper) {
  min-width: 0;
  overflow: hidden;
}
.doc-extract-name {
  font-size: 13px;
  color: var(--color-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doc-extract-empty {
  text-align: center;
  padding: 32px;
  color: var(--color-mute);
  font-size: 13px;
}
.doc-extract-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 8px;
  border-top: 1px solid var(--color-hairline);
}
.doc-extract-actions :deep(.ant-btn-primary:disabled) {
  background: var(--color-canvas-soft-2);
  border-color: var(--color-hairline);
  color: var(--color-mute);
}
.doc-extract-btn-row {
  display: flex;
  justify-content: flex-end;
}
</style>
