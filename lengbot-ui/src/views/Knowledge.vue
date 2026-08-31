<template>
  <div class="kb-page">
    <!-- 顶部标题 -->
    <div class="kb-top">
      <div>
        <h1 class="kb-title">知识库</h1>
        <div class="kb-crumb">工作台 / <b>知识库管理</b></div>
      </div>
      <button class="kb-btn-new" @click="openCreateModal">+ 新建知识库</button>
    </div>

    <!-- 统计条 -->
    <div class="kb-stats">
      <div class="kb-stat s1">
        <div class="ico">📚</div>
        <div><div class="num">{{ list.length }}</div><div class="lbl">总知识库</div></div>
      </div>
      <div class="kb-stat s2">
        <div class="ico">📄</div>
        <div><div class="num">{{ totalDocs }}</div><div class="lbl">总文档</div></div>
      </div>
      <div class="kb-stat s3">
        <div class="ico">🧠</div>
        <div><div class="num">{{ totalChunks }}</div><div class="lbl">总向量</div></div>
      </div>
      <div class="kb-stat s4">
        <div class="ico">⚡</div>
        <div><div class="num">{{ formatTokenCount(totalTokens) }}</div><div class="lbl">总 Token</div></div>
      </div>
    </div>

    <!-- 工具栏 -->
    <div class="kb-toolbar">
      <div class="kb-search">
        <SearchOutlined />
        <input v-model="searchText" placeholder="搜索知识库..." />
      </div>
      <div class="kb-filter" @click="showTypeMenu = !showTypeMenu">
        全部类型 ▾
        <div v-if="showTypeMenu" class="kb-filter-menu">
          <div :class="{ active: filterType === 'all' }" @click.stop="setFilter('all')">全部类型</div>
          <div :class="{ active: filterType === 'pg' }" @click.stop="setFilter('pg')">PostgreSQL</div>
          <div :class="{ active: filterType === 'milvus' }" @click.stop="setFilter('milvus')">Milvus</div>
          <div :class="{ active: filterType === 'dify' }" @click.stop="setFilter('dify')">Dify Dataset</div>
        </div>
      </div>
      <button class="kb-refresh" @click="refresh"><ReloadOutlined /> 刷新</button>
    </div>

    <!-- 卡片网格 -->
    <a-spin :spinning="loading">
      <div class="kb-cards">
        <div
          v-for="k in displayList"
          :key="k.id"
          class="kb-card"
          @click="router.push(`/app/knowledge/${k.id}`)"
        >
          <div class="c-head">
            <div class="c-ico" :class="iconClass(k.type)">{{ iconLetter(k) }}</div>
            <div class="c-tit">
              <div class="n">{{ k.name }}</div>
              <div class="row"><span class="c-tag" :class="tagClass(k.type)">{{ typeLabel(k.type) }}</span></div>
            </div>
          </div>
          <div class="c-desc">{{ k.description || '暂无描述' }}</div>
          <div class="c-metrics">
            <div class="metric">
              <div class="m-ico">📄</div>
              <div class="m-num">{{ k.documentCount || 0 }}</div>
              <div class="m-lbl">文档</div>
            </div>
            <div class="metric">
              <div class="m-ico">🧠</div>
              <div class="m-num">{{ k.chunkCount || 0 }}</div>
              <div class="m-lbl">向量</div>
            </div>
            <div class="metric">
              <div class="m-ico">⚡</div>
              <div class="m-num">{{ formatTokenCount(k.totalTokens) }}</div>
              <div class="m-lbl">Token</div>
            </div>
          </div>
          <div class="c-foot">
            <span class="st"><span class="d"></span>已连接</span>
            <div class="c-actions">
              <button class="act" @click.stop="router.push(`/app/knowledge/${k.id}`)">查看详情</button>
              <button class="act primary" @click.stop="router.push(`/app/knowledge/${k.id}`)">管理</button>
              <a-tooltip title="删除知识库">
                <button class="act act-del" @click.stop="handleDelete(k.id)"><DeleteOutlined /></button>
              </a-tooltip>
            </div>
          </div>
        </div>

        <LbEmptyState
          v-if="displayList.length === 0 && !loading"
          :icon="DatabaseOutlined"
          :title="searchText || filterType !== 'all' ? '没有匹配的知识库' : '还没有知识库，点击右上角创建一个吧'"
        />
      </div>
    </a-spin>

    <!-- 创建弹窗 -->
    <a-modal v-model:open="showCreate" title="新建知识库" :width="720" :mask-closable="false">
      <div class="dialog-scroll-body">
        <a-form :model="form" :label-col="{ flex: '0 0 110px' }">
          <a-form-item label="名称" required>
            <a-input v-model:value="form.name" placeholder="知识库名称（不超过30字）" :maxlength="30" show-count />
          </a-form-item>
          <a-form-item label="描述">
            <a-textarea
              v-model:value="form.description"
              :rows="3"
              placeholder="知识库描述（不超过50字，可选）"
              :maxlength="50"
              show-count
            />
          </a-form-item>
          <a-form-item label="知识库类型" required>
            <div class="kb-type-cards">
              <div class="kb-type-card" :class="{ active: form.type === 'pg' }" @click="form.type = 'pg'">
                <div class="kb-type-header">
                  <DatabaseOutlined class="kb-type-icon" />
                  <span class="kb-type-title">PostgreSQL</span>
                </div>
                <div class="kb-type-desc">
                  基于 pgvector 向量扩展，轻量易部署，适合中小规模知识库，与 PostgreSQL 生态无缝集成
                </div>
              </div>
              <div class="kb-type-card" :class="{ active: form.type === 'milvus' }" @click="form.type = 'milvus'">
                <div class="kb-type-header">
                  <CloudServerOutlined class="kb-type-icon" />
                  <span class="kb-type-title">Milvus</span>
                </div>
                <div class="kb-type-desc">
                  高性能分布式向量数据库，支持亿级向量检索、混合检索（BM25 + 向量），适合大规模生产场景
                </div>
              </div>
              <div class="kb-type-card" :class="{ active: form.type === 'dify' }" @click="form.type = 'dify'">
                <div class="kb-type-header">
                  <ApiOutlined class="kb-type-icon" />
                  <span class="kb-type-title">Dify Dataset</span>
                </div>
                <div class="kb-type-desc">连接已有 Dify 知识库，只读检索；文档、分块和问答由 Dify 管理</div>
              </div>
            </div>
          </a-form-item>
          <template v-if="form.type === 'dify'">
            <a-form-item label="Dify API 地址" required>
              <a-input v-model:value="form.difyConfig.apiUrl" placeholder="https://dify.example.com/v1" />
            </a-form-item>
            <a-form-item label="Dataset ID" required>
              <a-input v-model:value="form.difyConfig.datasetId" placeholder="Dify Dataset ID" />
            </a-form-item>
            <a-form-item label="Dataset Token" required>
              <a-input-password v-model:value="form.difyConfig.token" placeholder="仅用于加密保存，不会回显" />
            </a-form-item>
            <a-alert
              type="info"
              show-icon
              message="测试不会保存配置；创建时会再次验证连接。Dify Dataset 为只读知识库。"
            />
          </template>
          <a-form-item v-else label="Embed模型" required>
            <ModelSelect v-model="form.embeddingModel" model-type="embedding" placeholder="选择嵌入模型" />
          </a-form-item>
        </a-form>
      </div>
      <template #footer>
        <a-button @click="showCreate = false">取消</a-button>
        <a-button v-if="form.type === 'dify'" :loading="testingDifyConnection" @click="handleTestDifyConnection">
          测试连接
        </a-button>
        <a-button type="primary" :loading="submitting" @click="handleCreate">创建</a-button>
      </template>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  DeleteOutlined,
  SearchOutlined,
  ReloadOutlined,
  DatabaseOutlined,
  CloudServerOutlined,
  ApiOutlined,
} from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import { getKnowledgeList, createKnowledge, deleteKnowledge, testDifyDraftConnection } from '../api/knowledge'
import ModelSelect from '../components/ModelSelect.vue'
import LbEmptyState from '../components/common/LbEmptyState.vue'

const router = useRouter()
const list = ref([])
const loading = ref(false)
const searchText = ref('')
const showCreate = ref(false)
const submitting = ref(false)
const testingDifyConnection = ref(false)
const filterType = ref('all')
const showTypeMenu = ref(false)
const form = reactive({
  name: '',
  description: '',
  type: 'pg',
  embeddingModel: null,
  difyConfig: { apiUrl: '', datasetId: '', token: '' },
})

const totalDocs = computed(() => list.value.reduce((s, k) => s + (k.documentCount || 0), 0))
const totalChunks = computed(() => list.value.reduce((s, k) => s + (k.chunkCount || 0), 0))
const totalTokens = computed(() => list.value.reduce((s, k) => s + (k.totalTokens || 0), 0))
const displayList = computed(() =>
  filterType.value === 'all' ? list.value : list.value.filter((k) => k.type === filterType.value)
)

function formatTokenCount(count) {
  if (!count || count <= 0) return '0'
  if (count >= 1000000) return (count / 1000000).toFixed(1) + 'M'
  if (count >= 1000) return (count / 1000).toFixed(1) + 'K'
  return String(count)
}

function typeLabel(type) {
  if (type === 'milvus') return '向量数据库'
  if (type === 'dify') return 'Dify Dataset'
  if (type === 'pg') return 'PostgreSQL 向量'
  return type || '未知类型'
}
function iconClass(type) {
  if (type === 'milvus') return 'mint'
  if (type === 'pg') return 'peach'
  if (type === 'dify') return 'lav'
  return 'mint'
}
function tagClass(type) {
  if (type === 'milvus') return ''
  if (type === 'pg') return 'peach'
  if (type === 'dify') return 'lav'
  return ''
}
function iconLetter(k) {
  if (k.type === 'milvus') return 'M'
  if (k.type === 'dify') return 'D'
  if (k.type === 'pg') return 'P'
  return (k.name || '?').charAt(0)
}
function setFilter(t) {
  filterType.value = t
  showTypeMenu.value = false
}

function openCreateModal() {
  form.embeddingModel = null
  form.difyConfig.apiUrl = ''
  form.difyConfig.datasetId = ''
  form.difyConfig.token = ''
  showCreate.value = true
}

async function loadData() {
  loading.value = true
  try {
    const params = { pageNum: 1, pageSize: 50 }
    if (searchText.value) params.name = searchText.value
    const res = await getKnowledgeList(params)
    list.value = res.data.records || []
  } finally {
    loading.value = false
  }
}

// 刷新按钮语义：清空搜索关键词与类型筛选，回到全量列表
function refresh() {
  searchText.value = ''
  filterType.value = 'all'
  loadData()
}

let searchDebounceTimer = null
watch(searchText, () => {
  clearTimeout(searchDebounceTimer)
  // 立刻置 loading，避免 debounce 的 300ms 窗口期里 list=[] + loading=false 触发空状态闪现
  loading.value = true
  searchDebounceTimer = setTimeout(() => loadData(), 300)
})

function handleDelete(id) {
  Modal.confirm({
    title: '确认删除知识库',
    content: '删除后知识库及其所有文档将无法恢复，是否继续？',
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      await deleteKnowledge(id)
      message.success('删除成功')
      loadData()
    },
  })
}

async function handleCreate() {
  if (!form.name.trim()) {
    message.warning('请输入名称')
    return
  }
  if (form.type !== 'dify' && !form.embeddingModel) {
    message.warning('请选择 Embed 模型')
    return
  }
  if (
    form.type === 'dify' &&
    (!form.difyConfig.apiUrl.trim() || !form.difyConfig.datasetId.trim() || !form.difyConfig.token.trim())
  ) {
    message.warning('请填写 Dify API 地址、Dataset ID 和 Token')
    return
  }
  submitting.value = true
  try {
    await createKnowledge({
      ...form,
      embeddingModel: form.type === 'dify' ? null : form.embeddingModel,
      config: '{}',
    })
    message.success('创建成功')
    showCreate.value = false
    form.name = ''
    form.description = ''
    form.type = 'pg'
    loadData()
  } finally {
    submitting.value = false
  }
}

async function handleTestDifyConnection() {
  if (!form.difyConfig.apiUrl.trim() || !form.difyConfig.datasetId.trim() || !form.difyConfig.token.trim()) {
    message.warning('请填写 Dify API 地址、Dataset ID 和 Token')
    return
  }
  testingDifyConnection.value = true
  try {
    await testDifyDraftConnection({ ...form.difyConfig })
    message.success('Dify Dataset 连接成功')
  } finally {
    testingDifyConnection.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.kb-page {
  min-height: 100%;
  padding: 8px 8px 24px;
  color: #5A534A;
  font-family: "PingFang SC", "Yuanti SC", "YouYuan", "Microsoft YaHei", sans-serif;
  background:
    radial-gradient(420px 320px at 8% 0%, rgba(255, 224, 163, .5), transparent 60%),
    radial-gradient(420px 320px at 100% 100%, rgba(184, 232, 216, .5), transparent 60%),
    radial-gradient(360px 300px at 85% 10%, rgba(255, 201, 192, .45), transparent 60%),
    radial-gradient(300px 260px at 20% 90%, rgba(227, 213, 245, .4), transparent 60%),
    #FFF9F0;
  border-radius: 24px;
}

/* ===== 顶部标题 ===== */
.kb-top {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 6px;
}
.kb-title {
  font-size: 24px;
  font-weight: 800;
  color: #A98D5F;
}
.kb-crumb {
  font-size: 12px;
  color: #9B9284;
  margin-top: 6px;
}
.kb-crumb b {
  color: #8FBFA9;
  font-weight: 700;
}
.kb-btn-new {
  height: 40px;
  padding: 0 22px;
  border: none;
  border-radius: 999px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 700;
  color: #7A5B2E;
  background: linear-gradient(135deg, #FFE0A3, #FFC9C0);
  box-shadow: 0 8px 20px rgba(255, 192, 168, .4);
  transition: transform .15s ease, box-shadow .15s ease;
}
.kb-btn-new:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 26px rgba(255, 192, 168, .5);
}

/* ===== 统计条 ===== */
.kb-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin: 18px 0;
}
.kb-stat {
  background: #FFFDF8;
  border-radius: 20px;
  padding: 16px 18px;
  box-shadow: 0 10px 26px rgba(196, 167, 140, .14);
  display: flex;
  align-items: center;
  gap: 12px;
}
.kb-stat .ico {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}
.kb-stat.s1 .ico { background: #FFE0A3; }
.kb-stat.s2 .ico { background: #B8E8D8; }
.kb-stat.s3 .ico { background: #FFC9C0; }
.kb-stat.s4 .ico { background: #E3D5F5; }
.kb-stat .num {
  font-size: 25px;
  font-weight: 800;
  color: #A98D5F;
  line-height: 1.1;
}
.kb-stat .lbl {
  font-size: 12px;
  color: #9B9284;
  margin-top: 2px;
}

/* ===== 工具栏 ===== */
.kb-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}
.kb-search {
  flex: 1;
  max-width: 340px;
  height: 40px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  border-radius: 999px;
  background: #FFFDF8;
  box-shadow: 0 6px 16px rgba(196, 167, 140, .12);
  color: #9B9284;
}
.kb-search input {
  background: transparent;
  border: none;
  outline: none;
  color: #5A534A;
  font-size: 13.5px;
  width: 100%;
}
.kb-search input::placeholder { color: #C4BBAE; }
.kb-filter {
  position: relative;
  height: 40px;
  padding: 0 16px;
  border-radius: 999px;
  font-size: 13.5px;
  color: #9B9284;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  background: #FFFDF8;
  box-shadow: 0 6px 16px rgba(196, 167, 140, .12);
  border: none;
  transition: all .15s ease;
  user-select: none;
}
.kb-filter:hover {
  color: #5A534A;
  transform: translateY(-2px);
}
.kb-filter-menu {
  position: absolute;
  top: 46px;
  left: 0;
  min-width: 160px;
  background: #FFFDF8;
  border-radius: 16px;
  box-shadow: 0 14px 34px rgba(196, 167, 140, .24);
  padding: 6px;
  z-index: 20;
}
.kb-filter-menu div {
  padding: 9px 14px;
  border-radius: 12px;
  font-size: 13.5px;
  color: #7D6F5E;
  cursor: pointer;
  font-weight: 600;
}
.kb-filter-menu div:hover { background: rgba(255, 224, 163, .4); }
.kb-filter-menu div.active {
  color: #6A8F82;
  background: #B8E8D8;
  font-weight: 700;
}
.kb-refresh {
  height: 40px;
  padding: 0 18px;
  border-radius: 999px;
  font-size: 13.5px;
  color: #9B9284;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  background: #FFFDF8;
  box-shadow: 0 6px 16px rgba(196, 167, 140, .12);
  border: none;
  transition: all .15s ease;
}
.kb-refresh:hover {
  color: #5A534A;
  transform: translateY(-2px);
}

/* ===== 知识库卡片 ===== */
.kb-cards {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}
.kb-card {
  background: #FFFDF8;
  border-radius: 24px;
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  box-shadow: 0 14px 34px rgba(196, 167, 140, .16);
  transition: all .18s ease;
  border: 1px solid rgba(255, 255, 255, .9);
  cursor: pointer;
}
.kb-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 22px 46px rgba(196, 167, 140, .24);
}
.c-head {
  display: flex;
  align-items: center;
  gap: 14px;
}
.c-ico {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  font-weight: 800;
  color: #7A6A5A;
  box-shadow: 0 8px 18px rgba(196, 167, 140, .25);
}
.c-ico.mint { background: linear-gradient(135deg, #B8E8D8, #EAFBF4); }
.c-ico.peach { background: linear-gradient(135deg, #FFC9C0, #FFE9E4); }
.c-ico.lav { background: linear-gradient(135deg, #E3D5F5, #F6EEFD); }
.c-tit {
  flex: 1;
  min-width: 0;
}
.c-tit .n {
  font-size: 18px;
  font-weight: 800;
  color: #6A5F50;
}
.c-tit .row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}
.c-tag {
  font-size: 11.5px;
  font-weight: 700;
  color: #8FBFA9;
  background: rgba(184, 232, 216, .5);
  border-radius: 999px;
  padding: 3px 10px;
}
.c-tag.peach { color: #D98C72; background: rgba(255, 201, 192, .5); }
.c-tag.lav { color: #9B7FC4; background: rgba(227, 213, 245, .5); }
.c-desc {
  font-size: 13px;
  color: #9B9284;
  line-height: 1.7;
}
.c-metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.metric {
  background: #F7F1E7;
  border-radius: 16px;
  padding: 12px 14px;
}
.metric .m-ico { font-size: 15px; margin-bottom: 6px; }
.metric .m-num {
  font-size: 19px;
  font-weight: 800;
  color: #6A5F50;
}
.metric .m-lbl {
  font-size: 11px;
  color: #9B9284;
  margin-top: 2px;
  font-weight: 600;
}
.c-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 14px;
  border-top: 2px dashed #FFE0A3;
}
.st {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 700;
  padding: 5px 14px;
  border-radius: 999px;
  color: #6A9B82;
  background: rgba(184, 232, 216, .5);
}
.st .d {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #7FBFA0;
  box-shadow: 0 0 8px #7FBFA0;
}
.c-actions {
  display: flex;
  gap: 8px;
}
.act {
  font-size: 12.5px;
  padding: 8px 18px;
  border-radius: 999px;
  cursor: pointer;
  color: #9B9284;
  background: #F7F1E7;
  border: none;
  transition: all .15s ease;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.act:hover {
  color: #fff;
  background: linear-gradient(135deg, #FFC9C0, #FFE0A3);
  box-shadow: 0 6px 14px rgba(255, 192, 168, .4);
}
.act.primary { color: #D98C72; background: rgba(255, 201, 192, .4); }
.act-del { padding: 8px 12px; }

@media (max-width: 1200px) {
  .kb-stats { grid-template-columns: repeat(2, 1fr); }
  .kb-cards { grid-template-columns: 1fr; }
}

/* ===== 知识库类型选择卡片（创建弹窗） ===== */
.kb-type-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  width: 100%;
}
.kb-type-card {
  border: 1.5px solid #e4e4e7;
  border-radius: 10px;
  padding: 14px 16px;
  cursor: pointer;
  transition: all 0.15s;
  background: var(--color-canvas);
}
.kb-type-card:hover { border-color: var(--color-mute); }
.kb-type-card.active {
  border-color: var(--color-ink);
  background: var(--color-canvas-soft);
  box-shadow: 0 0 0 1px var(--color-primary);
}
.kb-type-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.kb-type-icon {
  font-size: 18px;
  color: var(--color-mute);
  transition: color 0.15s;
}
.kb-type-card.active .kb-type-icon { color: var(--color-ink); }
.kb-type-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-ink);
}
.kb-type-desc {
  font-size: 12px;
  color: var(--color-mute);
  line-height: 1.5;
}
@media (max-width: 640px) {
  .kb-type-cards { grid-template-columns: 1fr; }
}
</style>
