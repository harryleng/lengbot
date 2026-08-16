<template>
  <div class="detail-page">
    <LbDetailHeader
      :title="evaluator?.name || ''"
      :desc="evaluator?.description"
      :breadcrumb="[{ label: '评估器', onClick: () => router.back() }]"
      @back="router.back()"
    >
      <template #extra>
        <button class="lb-btn lb-btn--primary" @click="openVersionDialog()">
          <PlusOutlined />
          新建版本
        </button>
      </template>
    </LbDetailHeader>

    <div class="detail-page__body">
      <div class="content-grid">
        <!-- 左侧：版本列表 -->
        <div class="panel">
          <div class="panel-header">
            <h3>版本列表</h3>
          </div>
          <a-spin :spinning="versionsLoading">
            <div class="version-list" :class="{ 'version-list-min': versionsLoading }">
              <div
                v-for="v in versions"
                :key="v.id"
                class="version-item"
                :class="{ active: debugForm.versionId === v.id }"
                @click="selectVersion(v)"
              >
                <div class="version-info">
                  <span class="version-tag">{{ v.version }}</span>
                  <a-tag :color="v.status === 'published' ? 'green' : 'blue'" size="small">
                    {{ v.status === 'published' ? '已发布' : '草稿' }}
                  </a-tag>
                  <a-tooltip :title="getModelStatusHint(v)">
                    <a-tag :color="hasConfiguredModel(v) ? 'cyan' : 'warning'" size="small">
                      {{ hasConfiguredModel(v) ? getModelId(v) : '继承被测 Prompt' }}
                    </a-tag>
                  </a-tooltip>
                  <a-tooltip title="查看详情">
                    <button class="btn-icon" @click.stop="openVersionDetail(v)"><EyeOutlined /></button>
                  </a-tooltip>
                </div>
                <div class="version-meta">{{ truncate(v.prompt, 40) }}</div>
              </div>
              <div v-if="versions.length === 0 && !versionsLoading" class="version-empty">暂无版本，点击右上角创建</div>
            </div>
          </a-spin>
        </div>

        <!-- 右侧：调试区域 -->
        <div class="panel">
          <div class="panel-header">
            <h3>在线调试</h3>
          </div>
          <div class="debug-form">
            <a-form :label-col="{ flex: '0 0 100px' }">
              <a-form-item label="选择版本" required>
                <a-select
                  v-model:value="debugForm.versionId"
                  placeholder="选择要调试的版本"
                  style="width: 100%"
                  @change="onVersionSelectChange"
                >
                  <a-select-option v-for="v in versions" :key="v.id" :value="v.id">
                    {{ v.version }}
                  </a-select-option>
                </a-select>
              </a-form-item>
              <a-form-item label="输入变量">
                <div v-if="Object.keys(variableForm).length > 0" class="variable-form-fields">
                  <div v-for="(_, key) in variableForm" :key="key" class="variable-field-row">
                    <label class="variable-field-label">{{ key }}</label>
                    <a-textarea v-model:value="variableForm[key]" :rows="2" :placeholder="`请输入 ${key}`" />
                  </div>
                </div>
                <a-textarea
                  v-else
                  v-model:value="debugForm.variables"
                  :rows="5"
                  placeholder='JSON 格式，如: {"actual_output":"回答内容","expected_output":"期望内容","input":"用户输入"}'
                />
                <div v-if="Object.keys(variableForm).length === 0 && !selectedVersionObj" class="variable-hint">
                  请先在左侧选择一个版本
                </div>
              </a-form-item>
            </a-form>
            <div class="debug-actions">
              <button class="btn-primary-sm" :disabled="debugging" @click="handleDebug">
                <ThunderboltOutlined />
                {{ debugging ? '调试中...' : '执行评测' }}
              </button>
            </div>
          </div>

          <!-- 调试结果 -->
          <div v-if="debugResult" class="debug-result">
            <div class="result-header">评测结果</div>
            <div class="result-score" :class="scoreClass(debugResult.score)">
              <span class="score-label">评分</span>
              <span class="score-value">{{ debugResult.score?.toFixed(2) ?? '-' }}</span>
            </div>
            <div v-if="debugResult.reason" class="result-reason">
              <div class="reason-label">评分理由</div>
              <p>{{ debugResult.reason }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 版本详情弹窗 -->
    <a-modal
      v-model:open="versionDetailVisible"
      :title="'版本详情 - ' + (detailVersion?.version || '')"
      :width="720"
      :footer="null"
      :mask-closable="false"
    >
      <div class="dialog-scroll-body">
        <div v-if="detailVersion" class="version-detail">
          <div class="detail-row">
            <div class="detail-section" style="flex: 6">
              <div class="detail-label">评估 Prompt</div>
              <pre class="detail-pre">{{ detailVersion.prompt }}</pre>
            </div>
            <div class="detail-col">
              <div class="detail-section">
                <div class="detail-label">变量定义</div>
                <LbJsonViewer :value="detailVersion.variables" />
              </div>
              <div class="detail-section">
                <div class="detail-label">模型配置</div>
                <LbJsonViewer :value="detailVersion.modelConfig" />
              </div>
            </div>
          </div>
        </div>
      </div>
    </a-modal>

    <!-- 新建版本弹窗 -->
    <a-modal v-model:open="versionDialogVisible" title="新建版本" :width="680" :mask-closable="false">
      <div class="dialog-scroll-body">
        <a-form :model="versionForm" :label-col="{ flex: '0 0 100px' }">
          <a-form-item label="版本号" required>
            <a-input v-model:value="versionForm.version" placeholder="如: v1.0" />
          </a-form-item>
          <a-form-item label="版本描述">
            <a-input v-model:value="versionForm.versionDesc" placeholder="版本说明" />
          </a-form-item>
          <a-form-item label="评估模板" required>
            <a-textarea
              v-model:value="versionForm.prompt"
              :rows="8"
              placeholder="评估器的 Prompt 模板，使用 {{变量名}} 定义变量"
            />
          </a-form-item>
          <a-form-item label="变量定义">
            <a-textarea
              v-model:value="versionForm.variables"
              :rows="3"
              placeholder='JSON 格式，如: [{"name":"actual_output","description":"实际输出","required":true}]'
            />
          </a-form-item>
          <a-form-item label="评估模型">
            <ModelSelect
              v-model:provider-id="versionForm.providerId"
              v-model:model-id="versionForm.modelId"
              placeholder="选择用于评分的模型"
            />
            <div class="model-config-hint">
              留空时完整继承被测 Prompt 的提供商、模型与参数；仅在需要使用独立裁判模型时配置。
            </div>
          </a-form-item>
          <a-form-item label="模型参数">
            <a-textarea
              v-model:value="versionForm.modelParams"
              :rows="3"
              placeholder='JSON 格式（可选），如: {"temperature":0.3}'
            />
          </a-form-item>
        </a-form>
      </div>
      <template #footer>
        <LbDialogFooter :loading="submitting" @cancel="versionDialogVisible = false" @confirm="handleCreateVersion" />
      </template>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { PlusOutlined, AuditOutlined, ThunderboltOutlined, EyeOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import ModelSelect from '../components/ModelSelect.vue'
import LbDialogFooter from '../components/common/LbDialogFooter.vue'
import LbDetailHeader from '../components/common/LbDetailHeader.vue'
import LbJsonViewer from '../components/common/LbJsonViewer.vue'
import { getEvaluator, getEvaluatorVersions, createEvaluatorVersion, testEvaluator } from '../api/evaluator'

const route = useRoute()
const router = useRouter()
const evaluatorId = route.params.id
const evaluator = ref(null)
const versions = ref([])
const versionDialogVisible = ref(false)
const submitting = ref(false)
const debugging = ref(false)
const debugResult = ref(null)
const versionDetailVisible = ref(false)
const detailVersion = ref(null)
const versionsLoading = ref(false)
const variableForm = reactive({})
const selectedVersionObj = ref(null)

const versionForm = reactive({
  version: '',
  versionDesc: '',
  prompt: '',
  variables: '',
  providerId: null,
  modelId: null,
  modelParams: '',
})

const debugForm = reactive({
  versionId: null,
  variables: '',
})

onMounted(async () => {
  await loadEvaluator()
  await loadVersions()
})

async function loadEvaluator() {
  const res = await getEvaluator(evaluatorId)
  evaluator.value = res.data
}

async function loadVersions() {
  versionsLoading.value = true
  try {
    const res = await getEvaluatorVersions(evaluatorId)
    versions.value = res.data || []
  } finally {
    versionsLoading.value = false
  }
}

function selectVersion(v) {
  debugForm.versionId = v.id
  populateVariableForm(v)
}

function onVersionSelectChange(versionId) {
  const v = versions.value.find((v) => v.id === versionId)
  if (v) populateVariableForm(v)
}

function populateVariableForm(v) {
  // 清空旧变量
  Object.keys(variableForm).forEach((k) => delete variableForm[k])
  selectedVersionObj.value = v
  // 从版本的 variables 字段解析变量定义
  if (v.variables) {
    try {
      const defs = typeof v.variables === 'string' ? JSON.parse(v.variables) : v.variables
      if (Array.isArray(defs)) {
        defs.forEach((d) => {
          const name = d.name || d.variable || d
          if (typeof name === 'string') {
            variableForm[name] = ''
          }
        })
      }
    } catch {
      /* ignore */
    }
  }
}

function openVersionDialog() {
  Object.assign(versionForm, {
    version: '',
    versionDesc: '',
    prompt: '',
    variables: '',
    providerId: null,
    modelId: null,
    modelParams: '',
  })
  versionDialogVisible.value = true
}

async function handleCreateVersion() {
  if (!versionForm.version.trim()) return message.warning('请输入版本号')
  if (!versionForm.prompt.trim()) return message.warning('请输入评估模板')
  if (Boolean(versionForm.providerId) !== Boolean(versionForm.modelId)) {
    return message.warning('请同时选择模型提供商和模型，或两者都留空以继承被测 Prompt')
  }
  let modelParams = {}
  if (versionForm.modelParams.trim()) {
    try {
      modelParams = JSON.parse(versionForm.modelParams)
    } catch {
      return message.warning('模型参数 JSON 格式不正确')
    }
    if (!modelParams || Array.isArray(modelParams) || typeof modelParams !== 'object') {
      return message.warning('模型参数必须是 JSON 对象')
    }
    if (!versionForm.providerId) {
      return message.warning('继承被测 Prompt 时不能单独设置评估器模型参数')
    }
  }
  submitting.value = true
  try {
    await createEvaluatorVersion({
      evaluatorId,
      version: versionForm.version,
      prompt: versionForm.prompt,
      variables: versionForm.variables,
      modelConfig: versionForm.providerId
        ? JSON.stringify({
            ...modelParams,
            providerId: String(versionForm.providerId),
            modelId: versionForm.modelId,
          })
        : '{}',
    })
    message.success('版本创建成功')
    versionDialogVisible.value = false
    loadVersions()
  } finally {
    submitting.value = false
  }
}

async function handleDebug() {
  if (!debugForm.versionId) return message.warning('请选择版本')
  let variables = '{}'
  if (Object.keys(variableForm).length > 0) {
    // 使用表单输入的变量
    variables = JSON.stringify(variableForm)
  } else if (debugForm.variables.trim()) {
    try {
      JSON.parse(debugForm.variables)
      variables = debugForm.variables
    } catch {
      return message.warning('变量 JSON 格式不正确')
    }
  }
  debugging.value = true
  debugResult.value = null
  try {
    const res = await testEvaluator({
      evaluatorVersionId: debugForm.versionId,
      variables,
    })
    debugResult.value = res.data || {}
    message.success('评测完成')
  } catch {
    // error handled by interceptor
  } finally {
    debugging.value = false
  }
}

function scoreClass(score) {
  if (score == null) return ''
  if (score >= 0.8) return 'score-high'
  if (score >= 0.5) return 'score-mid'
  return 'score-low'
}

function truncate(str, len) {
  if (!str) return ''
  return str.length > len ? str.substring(0, len) + '...' : str
}

function openVersionDetail(v) {
  detailVersion.value = v
  versionDetailVisible.value = true
}

function parseModelConfig(version) {
  if (!version?.modelConfig) return {}
  try {
    return typeof version.modelConfig === 'string' ? JSON.parse(version.modelConfig) : version.modelConfig
  } catch {
    return {}
  }
}

function hasConfiguredModel(version) {
  const config = parseModelConfig(version)
  return Boolean(config.providerId && config.modelId)
}

function getModelId(version) {
  return parseModelConfig(version).modelId || '继承被测 Prompt'
}

function getModelStatusHint(version) {
  const config = parseModelConfig(version)
  return hasConfiguredModel(version)
    ? `providerId: ${config.providerId} / model: ${config.modelId}`
    : '该版本将完整继承被测 Prompt 的模型提供商、模型和参数。'
}
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.btn-back {
  background: none;
  border: none;
  color: var(--color-mute);
  cursor: pointer;
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 8px;
}
.btn-back:hover {
  color: var(--color-link);
}
.model-config-hint {
  margin-top: 6px;
  color: var(--color-mute);
  font-size: 12px;
  line-height: 1.5;
}
.page-title {
  font-size: 24px;
  font-weight: 600;
  color: var(--color-ink);
  margin-bottom: 4px;
}
.page-desc {
  font-size: 14px;
  color: var(--color-mute);
}
.header-actions {
  display: flex;
  gap: 8px;
}
.btn-primary-sm {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  background: var(--color-primary);
  color: #fff;
  border: none;
  border-radius: 100px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}
.btn-primary-sm:hover:not(:disabled) {
  background: #27272a;
}
.btn-primary-sm:disabled {
  background: var(--color-hairline-strong);
  color: var(--color-mute);
  cursor: not-allowed;
}
.btn-cancel {
  padding: 6px 16px;
  background: transparent;
  border: 1px solid var(--color-hairline);
  border-radius: 100px;
  cursor: pointer;
  font-size: 13px;
}
.btn-cancel:hover {
  border-color: var(--color-link);
  color: var(--color-link);
}

.content-grid {
  display: grid;
  grid-template-columns: 2fr 3fr;
  gap: 16px;
  height: 100%;
}
.panel {
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  padding: 16px;
  min-width: 0;
  overflow: hidden;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.panel-header h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-ink);
  margin: 0;
}

.version-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: calc(100vh - 260px);
  overflow-y: auto;
}
.version-list-min {
  min-height: 120px;
}
.version-item {
  padding: 10px 12px;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.15s;
  overflow: hidden;
}
.version-item:hover {
  border-color: var(--color-link);
}
.version-item.active {
  border-color: var(--color-link);
  background: var(--color-info-bg);
}
.btn-icon {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-mute);
  margin-left: auto;
}
.btn-icon:hover {
  background: var(--color-canvas-soft-2);
  color: var(--color-link);
}
.version-info {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.version-tag {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-ink);
}
.version-meta {
  font-size: 12px;
  color: var(--color-mute);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.version-empty {
  text-align: center;
  padding: 40px;
  color: var(--color-mute);
  font-size: 13px;
}

.debug-form {
  margin-bottom: 24px;
}
.debug-actions {
  display: flex;
  justify-content: flex-end;
}
.debug-result {
  border-top: 1px solid var(--color-hairline);
  padding-top: 24px;
}
.result-header {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-ink);
  margin-bottom: 16px;
}
.result-score {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  border-radius: 8px;
  margin-bottom: 16px;
}
.result-score.score-high {
  background: var(--color-success-bg);
}
.result-score.score-mid {
  background: var(--color-warn-bg-deep);
}
.result-score.score-low {
  background: var(--color-error-bg);
}
.score-label {
  font-size: 14px;
  color: var(--color-mute);
}
.score-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--color-ink);
}
.result-reason {
  padding: 12px 16px;
  background: var(--color-canvas-soft);
  border-radius: 8px;
}
.reason-label {
  font-size: 13px;
  color: var(--color-mute);
  margin-bottom: 8px;
}
.result-reason p {
  margin: 0;
  font-size: 14px;
  color: var(--color-ink);
  line-height: 1.6;
}

.dialog-footer {
  display: flex;
  justify-content: space-between;
  margin-top: 16px;
}
.dialog-footer-right {
  display: flex;
  gap: 8px;
}

.version-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.detail-row {
  display: flex;
  gap: 16px;
}
.detail-col {
  flex: 4;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}
.detail-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-mute);
  margin-bottom: 8px;
}
.detail-pre {
  background: var(--color-canvas-soft);
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  padding: 12px 16px;
  font-size: 13px;
  color: var(--color-ink);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  max-height: 300px;
  overflow-y: auto;
}

.variable-form-fields {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.variable-field-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.variable-field-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-ink);
}
.variable-hint {
  font-size: 12px;
  color: var(--color-mute);
  margin-top: 4px;
}
</style>
