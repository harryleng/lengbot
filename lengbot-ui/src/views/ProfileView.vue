<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">个人信息</h2>
    </div>

    <a-tabs v-model:active-key="activeTab" class="profile-tabs">
      <a-tab-pane key="info" tab="个人信息">
        <div class="content-grid">
          <div class="panel">
            <div class="panel-header">
              <h3>基本信息</h3>
            </div>
            <a-form :model="profileForm" :label-col="{ span: 6 }">
              <a-form-item label="头像">
                <div class="avatar-upload">
                  <div class="avatar-preview" :class="{ 'has-avatar': avatarUrl }">
                    <img v-if="avatarUrl" :src="avatarUrl" alt="avatar" class="avatar-img" @error="avatarUrl = ''" />
                    <span v-else class="avatar-placeholder">{{ initialLetter }}</span>
                    <div class="avatar-overlay" @click="triggerAvatarUpload">
                      <UploadOutlined />
                    </div>
                  </div>
                  <input
                    ref="avatarInputRef"
                    type="file"
                    accept=".jpg,.jpeg,.png,.gif,.webp,.bmp"
                    style="display: none"
                    @change="onAvatarFileChange"
                  />
                  <span class="avatar-tip">支持 jpg/jpeg/png/gif/webp，建议 200x200</span>
                </div>
              </a-form-item>
              <a-form-item label="用户名">
                <a-input :value="profileForm.username" disabled />
              </a-form-item>
              <a-form-item label="昵称">
                <a-input v-model:value="profileForm.nickname" placeholder="设置昵称" :maxlength="8" />
              </a-form-item>
              <a-form-item label="邮箱">
                <a-input v-model:value="profileForm.email" placeholder="设置邮箱" />
              </a-form-item>
              <a-form-item label="手机号">
                <a-input v-model:value="profileForm.phone" placeholder="设置手机号" />
              </a-form-item>
              <a-form-item label="角色">
                <a-tag :color="roleColor">{{ roleText }}</a-tag>
              </a-form-item>
              <a-form-item label="注册时间">
                <span class="info-text">{{ formatTime(profileForm.createTime) }}</span>
              </a-form-item>
              <a-form-item :wrapper-col="{ offset: 6 }">
                <button class="btn-primary" :disabled="saving" @click.prevent="handleSaveProfile">
                  <SaveOutlined />
                  保存修改
                </button>
              </a-form-item>
            </a-form>
          </div>

          <div class="panel">
            <div class="panel-header">
              <h3>修改密码</h3>
            </div>
            <a-form :model="passwordForm" :label-col="{ span: 6 }">
              <a-form-item label="原密码" required>
                <a-input-password v-model:value="passwordForm.oldPassword" placeholder="请输入原密码" />
              </a-form-item>
              <a-form-item label="新密码" required>
                <a-input-password v-model:value="passwordForm.newPassword" placeholder="请输入新密码（6-64位）" />
              </a-form-item>
              <a-form-item label="确认密码" required>
                <a-input-password v-model:value="passwordForm.confirmPassword" placeholder="请再次输入新密码" />
              </a-form-item>
              <a-form-item :wrapper-col="{ offset: 6 }">
                <button class="btn-primary" :disabled="changingPwd" @click.prevent="handleChangePassword">
                  <LockOutlined />
                  修改密码
                </button>
              </a-form-item>
            </a-form>
          </div>
        </div>
      </a-tab-pane>

      <a-tab-pane key="config" tab="个人配置">
        <div class="config-stack">
          <div class="panel">
            <div class="panel-header split-header">
              <div>
                <h3>长期记忆</h3>
                <p class="panel-subtitle">开启后，AI 会在回复时参考你的稳定偏好和背景信息</p>
              </div>
              <button class="btn-primary" :disabled="preferencesSaving" @click.prevent="handleSavePreferences">
                <SaveOutlined />
                保存配置
              </button>
            </div>
            <div class="memory-settings">
              <label class="setting-row">
                <span class="setting-text">
                  <strong>启用长期记忆</strong>
                  <small>AI 回复时参考你的稳定偏好</small>
                </span>
                <a-switch v-model:checked="preferenceForm.longMemoryEnabled" />
              </label>
              <label class="setting-row">
                <span class="setting-text">
                  <strong>自动抽取记忆</strong>
                  <small>回复结束后尝试保存明确偏好</small>
                </span>
                <a-switch
                  v-model:checked="preferenceForm.longMemoryAutoExtract"
                  :disabled="!preferenceForm.longMemoryEnabled"
                />
              </label>
              <label class="setting-row">
                <span class="setting-text">
                  <strong>每轮注入数量</strong>
                  <small>控制进入 Prompt 的记忆条数</small>
                </span>
                <a-input-number
                  v-model:value="preferenceForm.longMemoryInjectLimit"
                  :min="1"
                  :max="15"
                  :disabled="!preferenceForm.longMemoryEnabled"
                />
              </label>
              <label class="setting-row">
                <span class="setting-text">
                  <strong>记忆作用域</strong>
                  <small>决定记忆跨 Agent 还是按 Agent 匹配</small>
                </span>
                <a-select
                  v-model:value="preferenceForm.longMemoryScope"
                  :disabled="!preferenceForm.longMemoryEnabled"
                  class="setting-select"
                >
                  <a-select-option value="user">跨 Agent 生效</a-select-option>
                  <a-select-option value="agent">当前 Agent 优先</a-select-option>
                </a-select>
              </label>
            </div>
          </div>

          <div class="panel">
            <div class="panel-header split-header">
              <div>
                <h3>记忆内容</h3>
                <p class="panel-subtitle">可以手动新增、修正或停用不准确的长期记忆</p>
              </div>
              <div style="display: flex; gap: 8px">
                <button class="btn-outline" @click.prevent="loadMemories">
                  <ReloadOutlined :class="{ 'spin-animation': memoryLoading }" />
                </button>
                <button class="btn-primary" @click.prevent="openCreateMemory">新增记忆</button>
              </div>
            </div>
            <a-spin :spinning="memoryLoading">
              <a-empty v-if="!memories.length" description="暂无长期记忆" />
              <div v-else class="memory-card-grid">
                <article
                  v-for="record in memories"
                  :key="record.id"
                  class="memory-card"
                  :class="{ disabled: record.status !== 'active' }"
                >
                  <div class="memory-card-head">
                    <div class="memory-card-tags">
                      <a-tag>{{ memoryTypeLabel(record.memoryType) }}</a-tag>
                      <a-tag :color="record.status === 'active' ? 'green' : 'default'">
                        {{ statusLabel(record.status) }}
                      </a-tag>
                    </div>
                    <span class="memory-confidence">置信度 {{ record.confidence ?? 1 }}</span>
                  </div>
                  <p class="memory-content">{{ record.content }}</p>
                  <div class="keyword-list">
                    <a-tag v-for="keyword in record.keywords || []" :key="keyword">{{ keyword }}</a-tag>
                    <span v-if="!(record.keywords || []).length" class="empty-keyword">无关键词</span>
                  </div>
                  <div class="memory-card-foot">
                    <span class="memory-time">更新于 {{ formatTime(record.updateTime) }}</span>
                    <div class="memory-actions">
                      <a-tooltip title="编辑">
                        <a-button type="text" size="small" shape="circle" @click="openEditMemory(record)">
                          <EditOutlined />
                        </a-button>
                      </a-tooltip>
                      <a-tooltip :title="record.status === 'active' ? '停用' : '启用'">
                        <a-button type="text" size="small" shape="circle" @click="toggleMemoryStatus(record)">
                          <StopOutlined v-if="record.status === 'active'" />
                          <CheckCircleOutlined v-else />
                        </a-button>
                      </a-tooltip>
                      <a-tooltip title="删除">
                        <a-button type="text" size="small" shape="circle" danger @click="handleDeleteMemory(record)">
                          <DeleteOutlined />
                        </a-button>
                      </a-tooltip>
                    </div>
                  </div>
                </article>
              </div>
            </a-spin>
          </div>

          <div class="panel frame-panel">
            <div class="panel-header">
              <h3>头像框</h3>
              <p class="panel-subtitle">选择一个动态头像框展示你的个性</p>
            </div>
            <div class="frame-content">
              <div class="frame-preview">
                <AvatarFrame :frame="selectedFrame" :size="80">
                  <div class="preview-avatar">
                    <img
                      v-if="avatarUrl"
                      :src="avatarUrl"
                      alt="avatar"
                      class="preview-avatar-img"
                      @error="avatarUrl = ''"
                    />
                    <span v-else>{{ initialLetter }}</span>
                  </div>
                </AvatarFrame>
                <span class="frame-preview-label">{{ frameLabelMap[selectedFrame] || '无' }}</span>
              </div>
              <div class="frame-options">
                <div
                  v-for="opt in frameOptions"
                  :key="opt.value"
                  class="frame-option"
                  :class="{ active: selectedFrame === opt.value }"
                  @click="selectedFrame = opt.value"
                >
                  <AvatarFrame :frame="opt.value" :size="48">
                    <div class="option-avatar">
                      <img
                        v-if="avatarUrl"
                        :src="avatarUrl"
                        alt="avatar"
                        class="option-avatar-img"
                        @error="avatarUrl = ''"
                      />
                      <span v-else>{{ initialLetter }}</span>
                    </div>
                  </AvatarFrame>
                  <span class="frame-option-label">{{ opt.label }}</span>
                </div>
              </div>
              <button class="btn-primary" :disabled="savingFrame" @click.prevent="handleSaveFrame">
                <SaveOutlined />
                保存头像框
              </button>
            </div>
          </div>

          <div class="panel level-panel">
            <div class="panel-header split-header">
              <div>
                <h3>等级</h3>
                <p class="panel-subtitle">选择你的等级</p>
              </div>
              <button class="btn-primary" :disabled="savingLevel" @click.prevent="handleSaveLevel">
                <SaveOutlined />
                保存等级
              </button>
            </div>
            <div class="level-options">
              <div
                v-for="opt in levelOptions"
                :key="opt.value"
                class="level-option"
                :class="{
                  active: selectedLevel === opt.value,
                  'level-5-option': opt.value === 5,
                  'level-6-option': opt.value === 6,
                }"
                @click="selectedLevel = opt.value"
              >
                <LevelTag v-if="opt.value > 0" :level="opt.value" />
                <span v-else class="level-option-empty">无</span>
              </div>
            </div>
          </div>
        </div>
      </a-tab-pane>

      <a-tab-pane key="token" tab="Token 用量">
        <a-spin :spinning="tokenLoading">
          <div class="content-grid">
            <div class="panel">
              <div class="panel-header">
                <h3>今日 Token 用量</h3>
                <span class="panel-desc panel-desc-right">{{ tokenUsage.date }}</span>
              </div>
              <div class="panel-body">
                <div class="token-stat-row">
                  <div class="token-stat-label">今日已用</div>
                  <div class="token-stat-value">{{ formatToken(tokenUsage.userUsed) }}</div>
                  <div class="token-stat-sub">/ {{ formatToken(tokenUsage.userLimit) }}</div>
                </div>
                <a-progress :percent="usagePercent" :stroke-color="usagePercent > 80 ? '#ef4444' : '#10b981'" />
                <div class="token-stat-hint">剩余可用 {{ formatToken(remainingTokens) }} tokens</div>
              </div>
            </div>

            <div class="panel">
              <div class="panel-header">
                <h3>近 7 天累计</h3>
                <span class="panel-desc panel-desc-right">含今日</span>
              </div>
              <div class="panel-body">
                <div class="token-stat-row">
                  <div class="token-stat-label">累计消耗</div>
                  <div class="token-stat-value">{{ formatToken(tokenUsage.last7dUsed) }}</div>
                  <div class="token-stat-sub">tokens</div>
                </div>
                <div class="token-stat-hint">按自然日统计：今天 + 前 6 天</div>
              </div>
            </div>
          </div>
        </a-spin>
      </a-tab-pane>
    </a-tabs>

    <a-modal
      v-model:open="memoryModalVisible"
      :title="editingMemoryId ? '编辑长期记忆' : '新增长期记忆'"
      @ok="handleSaveMemory"
    >
      <div class="dialog-scroll-body">
        <a-form :model="memoryForm" layout="vertical">
          <a-form-item label="类型">
            <a-select v-model:value="memoryForm.memoryType">
              <a-select-option value="preference">用户偏好</a-select-option>
              <a-select-option value="profile">用户背景</a-select-option>
              <a-select-option value="project_fact">项目事实</a-select-option>
              <a-select-option value="instruction">长期指令</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="内容" required>
            <a-textarea v-model:value="memoryForm.content" :rows="4" :maxlength="1000" show-count />
          </a-form-item>
          <a-form-item label="关键词">
            <a-input v-model:value="memoryForm.keywordsText" placeholder="用逗号分隔，例如：回复风格, Java, 报告" />
          </a-form-item>
          <a-form-item label="置信度">
            <a-input-number v-model:value="memoryForm.confidence" :min="0" :max="1" :step="0.05" />
          </a-form-item>
        </a-form>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import {
  SaveOutlined,
  LockOutlined,
  UploadOutlined,
  ReloadOutlined,
  EditOutlined,
  StopOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
} from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import { getMe, updateProfile, changePassword, updateAvatarFrame, uploadAvatar } from '../api/auth'
import { getUserPreferences, updateUserPreferences } from '../api/userPreference'
import {
  listUserMemories,
  createUserMemory,
  updateUserMemory,
  updateUserMemoryStatus,
  deleteUserMemory,
} from '../api/userMemory'
import { getMyTokenUsage } from '../api/tokenBudget'
import { useUserStore } from '../stores/user'
import { formatTime } from '../utils/format'
import AvatarFrame from '../components/AvatarFrame.vue'
import LevelTag from '../components/LevelTag.vue'

const userStore = useUserStore()
const activeTab = ref('info')
const saving = ref(false)
const changingPwd = ref(false)
const savingFrame = ref(false)
const savingLevel = ref(false)
const preferencesSaving = ref(false)
const memoryLoading = ref(false)
const memoryModalVisible = ref(false)
const editingMemoryId = ref(null)
const selectedFrame = ref('')
const selectedLevel = ref(0)
const avatarUrl = ref('')
const avatarUploading = ref(false)
const avatarInputRef = ref(null)
const memories = ref([])

// Token 用量：切到 token tab 时懒加载，避免首屏多发一次请求
const tokenLoading = ref(false)
const tokenUsage = reactive({
  userUsed: 0,
  userLimit: 0,
  last7dUsed: 0,
  date: '',
})

const frameOptions = [
  { value: '', label: '无' },
  { value: 'lightning', label: '巅峰闪电' },
  { value: 'flame', label: '烈焰之环' },
  { value: 'stars', label: '星辰轨迹' },
]

const frameLabelMap = { '': '无', lightning: '巅峰闪电', flame: '烈焰之环', stars: '星辰轨迹' }

const levelOptions = [
  { value: 0, label: '无' },
  { value: 1, label: 'Lv1' },
  { value: 2, label: 'Lv2' },
  { value: 3, label: 'Lv3' },
  { value: 4, label: 'Lv4' },
  { value: 5, label: 'Lv5' },
  { value: 6, label: 'Lv6' },
]

const profileForm = reactive({
  username: '',
  nickname: '',
  email: '',
  phone: '',
  role: '',
  createTime: '',
})

const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const preferenceForm = reactive({
  longMemoryEnabled: false,
  longMemoryAutoExtract: false,
  longMemoryInjectLimit: 6,
  longMemoryScope: 'user',
})

const memoryForm = reactive({
  memoryType: 'preference',
  content: '',
  keywordsText: '',
  confidence: 1,
})

const initialLetter = computed(() => {
  return (profileForm.username || profileForm.nickname || 'U')[0]
})

const roleText = computed(() => {
  const map = { ADMIN: '管理员', USER: '普通用户' }
  return map[profileForm.role] || profileForm.role || '普通用户'
})

const roleColor = computed(() => {
  return profileForm.role === 'ADMIN' ? 'red' : 'blue'
})

// 今日消耗百分比（userLimit 为 0 时显示 0，避免 NaN）
const usagePercent = computed(() => {
  if (!tokenUsage.userLimit) return 0
  return Math.min(100, Math.round((tokenUsage.userUsed / tokenUsage.userLimit) * 100))
})

const remainingTokens = computed(() => {
  return Math.max(0, tokenUsage.userLimit - tokenUsage.userUsed)
})

// 1k 以下显示原数；1k~1m 用 K；1m 以上用 M（与 SettingsView.formatToken 口径一致）
function formatToken(val) {
  if (val == null) return '0'
  if (val >= 1_000_000) return (val / 1_000_000).toFixed(1) + 'M'
  if (val >= 1_000) return (val / 1_000).toFixed(1) + 'K'
  return String(val)
}

async function loadTokenUsage() {
  tokenLoading.value = true
  try {
    const res = await getMyTokenUsage()
    Object.assign(tokenUsage, res.data || {})
  } catch (e) {
    // 错误提示由 request.js 拦截器统一处理，此处仅重置 loading
  } finally {
    tokenLoading.value = false
  }
}

async function loadProfile() {
  try {
    const res = await getMe()
    const user = res.data
    Object.assign(profileForm, {
      username: user.username || '',
      nickname: user.nickname || '',
      email: user.email || '',
      phone: user.phone || '',
      role: user.role?.code || user.role || '',
      createTime: user.createTime || '',
    })
    selectedFrame.value = user.avatarFrame || ''
    selectedLevel.value = user.level ?? 0
    avatarUrl.value = user.avatar || ''
  } catch {
    /* interceptor已处理 */
  }
}

async function loadPreferences() {
  try {
    const res = await getUserPreferences()
    Object.assign(preferenceForm, {
      longMemoryEnabled: !!res.data?.longMemoryEnabled,
      longMemoryAutoExtract: !!res.data?.longMemoryAutoExtract,
      longMemoryInjectLimit: res.data?.longMemoryInjectLimit || 6,
      longMemoryScope: res.data?.longMemoryScope || 'user',
    })
  } catch {
    /* interceptor已处理 */
  }
}

async function loadMemories() {
  memoryLoading.value = true
  try {
    const res = await listUserMemories()
    memories.value = res.data || []
  } catch {
    /* interceptor已处理 */
  } finally {
    memoryLoading.value = false
  }
}

async function handleSaveProfile() {
  saving.value = true
  try {
    const res = await updateProfile({
      nickname: profileForm.nickname,
      email: profileForm.email,
      phone: profileForm.phone,
    })
    userStore.user.nickname = res.data.nickname
    userStore.user.email = res.data.email
    userStore.user.phone = res.data.phone
    message.success('个人信息已更新')
  } catch {
    /* interceptor已处理 */
  } finally {
    saving.value = false
  }
}

async function handleChangePassword() {
  if (!passwordForm.oldPassword) return message.warning('请输入原密码')
  if (!passwordForm.newPassword) return message.warning('请输入新密码')
  if (passwordForm.newPassword.length < 6) return message.warning('新密码至少6位')
  if (passwordForm.newPassword !== passwordForm.confirmPassword) return message.warning('两次密码不一致')

  changingPwd.value = true
  try {
    await changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword,
    })
    message.success('密码修改成功，请重新登录')
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
  } catch {
    /* interceptor已处理 */
  } finally {
    changingPwd.value = false
  }
}

async function handleSavePreferences() {
  preferencesSaving.value = true
  try {
    const res = await updateUserPreferences({ ...preferenceForm })
    Object.assign(preferenceForm, res.data || {})
    message.success('个人配置已保存')
  } catch {
    /* interceptor已处理 */
  } finally {
    preferencesSaving.value = false
  }
}

async function handleSaveMemory() {
  if (!memoryForm.content.trim()) {
    return message.warning('请输入记忆内容')
  }
  const payload = {
    memoryType: memoryForm.memoryType,
    content: memoryForm.content.trim(),
    keywords: normalizeKeywords(memoryForm.keywordsText),
    confidence: memoryForm.confidence,
  }
  try {
    if (editingMemoryId.value) {
      await updateUserMemory(editingMemoryId.value, payload)
      message.success('记忆已更新')
    } else {
      await createUserMemory(payload)
      message.success('记忆已新增')
    }
    memoryModalVisible.value = false
    await loadMemories()
  } catch {
    /* interceptor已处理 */
  }
}

function openCreateMemory() {
  editingMemoryId.value = null
  Object.assign(memoryForm, {
    memoryType: 'preference',
    content: '',
    keywordsText: '',
    confidence: 1,
  })
  memoryModalVisible.value = true
}

function openEditMemory(record) {
  editingMemoryId.value = record.id
  Object.assign(memoryForm, {
    memoryType: record.memoryType || 'preference',
    content: record.content || '',
    keywordsText: (record.keywords || []).join(', '),
    confidence: record.confidence ?? 1,
  })
  memoryModalVisible.value = true
}

async function toggleMemoryStatus(record) {
  const nextStatus = record.status === 'active' ? 'disabled' : 'active'
  try {
    await updateUserMemoryStatus(record.id, nextStatus)
    message.success(nextStatus === 'active' ? '记忆已启用' : '记忆已停用')
    await loadMemories()
  } catch {
    /* interceptor已处理 */
  }
}

function handleDeleteMemory(record) {
  Modal.confirm({
    title: '确认删除',
    content: '确定删除这条长期记忆吗？',
    okType: 'danger',
    async onOk() {
      try {
        await deleteUserMemory(record.id)
        message.success('记忆已删除')
        await loadMemories()
      } catch {
        /* interceptor已处理 */
      }
    },
  })
}

async function handleSaveFrame() {
  savingFrame.value = true
  try {
    const val = selectedFrame.value || 'none'
    await updateAvatarFrame(val)
    userStore.user.avatarFrame = selectedFrame.value || null
    message.success('头像框已更新')
  } catch {
    /* interceptor已处理 */
  } finally {
    savingFrame.value = false
  }
}

async function handleSaveLevel() {
  savingLevel.value = true
  try {
    await updateProfile({ level: selectedLevel.value })
    userStore.user.level = selectedLevel.value
    message.success('等级已更新')
  } catch {
    /* interceptor已处理 */
  } finally {
    savingLevel.value = false
  }
}

function triggerAvatarUpload() {
  avatarInputRef.value?.click()
}

async function onAvatarFileChange(e) {
  const file = e.target.files[0]
  if (!file) return
  avatarUploading.value = true
  try {
    const res = await uploadAvatar(file)
    avatarUrl.value = res.data
    userStore.user.avatar = res.data
    message.success('头像上传成功')
  } catch {
    /* interceptor已处理 */
  } finally {
    avatarUploading.value = false
    if (avatarInputRef.value) avatarInputRef.value.value = ''
  }
}

function normalizeKeywords(text) {
  return (text || '')
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter(Boolean)
    .slice(0, 12)
}

function memoryTypeLabel(type) {
  return (
    {
      preference: '用户偏好',
      profile: '用户背景',
      project_fact: '项目事实',
      instruction: '长期指令',
    }[type] || '记忆'
  )
}

function statusLabel(status) {
  return (
    {
      active: '启用',
      disabled: '停用',
      archived: '归档',
    }[status] ||
    status ||
    '未知'
  )
}

onMounted(() => {
  loadProfile()
  loadPreferences()
  loadMemories()
})

// 切到 token tab 时懒加载：避免首屏多发请求，token 数据低频访问
watch(activeTab, (val) => {
  if (val === 'token' && !tokenUsage.date) {
    loadTokenUsage()
  }
})
</script>

<style scoped>
.page {
  padding: var(--space-xl);
  padding-right: calc(var(--space-xl) + var(--scroll-content-gap));
  height: 100vh;
  overflow-y: auto;
  background: var(--color-canvas-soft);
  scrollbar-gutter: stable;
}
.page-header {
  display: flex;
  align-items: center;
  margin-bottom: 20px;
}
.page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-ink);
  margin: 0;
}
.profile-tabs {
  background: transparent;
}
.content-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.config-stack {
  display: grid;
  gap: 16px;
}
.panel {
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  padding: 20px;
}
.panel-header {
  margin-bottom: 16px;
}
.panel-header h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-ink);
  margin: 0;
}
.split-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.panel-subtitle {
  font-size: 13px;
  color: var(--color-mute);
  margin: 4px 0 0;
}
.panel-desc-right {
  margin-left: auto;
  font-size: 12px;
  color: var(--color-mute);
}
/* Token 用量 tab 专属样式 */
.token-stat-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 12px;
}
.token-stat-label {
  font-size: 13px;
  color: var(--color-mute);
}
.token-stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--color-ink);
  font-variant-numeric: tabular-nums;
}
.token-stat-sub {
  font-size: 13px;
  color: var(--color-mute);
}
.token-stat-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--color-mute);
}
.btn-primary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 16px;
  background: var(--color-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
}
.btn-primary:hover:not(:disabled) {
  background: #27272a;
}
.btn-primary:disabled {
  background: #d4d4d8;
  cursor: not-allowed;
}
.info-text {
  font-size: 14px;
  color: var(--color-mute);
}
.avatar-upload {
  display: flex;
  align-items: center;
  gap: 16px;
}
.avatar-preview {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: #0070f3;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  font-weight: 700;
  position: relative;
  cursor: pointer;
  overflow: hidden;
  flex-shrink: 0;
}
.avatar-preview.has-avatar {
  background: var(--color-canvas-soft-2);
}
.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.avatar-placeholder {
  font-size: 28px;
  font-weight: 700;
}
.avatar-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.2s;
  color: #fff;
  font-size: 20px;
}
.avatar-preview:hover .avatar-overlay {
  opacity: 1;
}
.avatar-tip {
  font-size: 12px;
  color: var(--color-mute);
}
.memory-settings {
  display: grid;
  grid-template-columns: repeat(2, minmax(240px, 1fr));
  gap: 12px;
}
.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 76px;
  padding: 14px 16px;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  color: var(--color-body);
  background: var(--color-canvas-soft);
}
.setting-text {
  display: grid;
  gap: 4px;
  min-width: 0;
}
.setting-text strong {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-ink);
}
.setting-text small {
  font-size: 12px;
  line-height: 1.4;
  color: var(--color-mute);
}
.setting-select {
  width: 160px;
}
.keyword-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.memory-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}
.memory-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 180px;
  padding: 16px;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  background: var(--color-canvas-soft);
}
.memory-card.disabled {
  opacity: 0.72;
}
.memory-card-head,
.memory-card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.memory-card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.memory-confidence,
.memory-time,
.empty-keyword {
  font-size: 12px;
  color: var(--color-mute);
  white-space: nowrap;
}
.memory-content {
  flex: 1;
  min-height: 56px;
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--color-ink);
  word-break: break-word;
}
.memory-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}
.frame-content {
  display: flex;
  align-items: center;
  gap: 32px;
}
.frame-preview {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.preview-avatar {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: #0070f3;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  font-weight: 600;
  overflow: hidden;
}
.preview-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.frame-preview-label {
  font-size: 13px;
  color: var(--color-mute);
}
.frame-options {
  display: flex;
  gap: 16px;
  flex: 1;
}
.frame-option {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 12px;
  border: 2px solid #ebebeb;
  border-radius: 8px;
  cursor: pointer;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
}
.frame-option:hover {
  border-color: var(--color-link);
}
.frame-option.active {
  border-color: var(--color-link);
  box-shadow: 0 0 0 2px rgba(0, 112, 243, 0.15);
}
.option-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: #0070f3;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 600;
  overflow: hidden;
}
.option-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.frame-option-label {
  font-size: 12px;
  color: var(--color-body);
}
.level-options {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.level-option {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 80px;
  height: 52px;
  padding: 0;
  border: 2px solid #ebebeb;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.25s;
  overflow: hidden;
}
.level-option:hover {
  border-color: var(--color-link);
  transform: translateY(-2px);
}
.level-option.active {
  border-color: var(--color-link);
  box-shadow: 0 0 0 2px rgba(0, 112, 243, 0.15);
  background: rgba(0, 112, 243, 0.02);
}
.level-5-option:hover:not(.active),
.level-6-option:hover:not(.active) {
  border-color: var(--color-hairline);
  transform: none;
  box-shadow: none;
  background: transparent;
}
.level-option-empty {
  font-size: 14px;
  color: var(--color-mute);
}
@media (max-width: 960px) {
  .content-grid,
  .memory-settings {
    grid-template-columns: 1fr;
  }
  .split-header {
    align-items: flex-start;
    flex-direction: column;
  }
  .memory-card-grid {
    grid-template-columns: 1fr;
  }
  .memory-card-foot {
    align-items: flex-start;
    flex-direction: column;
  }
  .memory-actions {
    justify-content: flex-start;
  }
  .frame-content {
    align-items: flex-start;
    flex-direction: column;
  }
  .frame-options {
    flex-wrap: wrap;
  }
}
.spin-animation {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
