<template>
  <div class="voice-manage">
    <!-- 页头 -->
    <div class="vm-header">
      <div>
        <h2 class="vm-title">音色管理</h2>
        <p class="vm-sub">缓存云端的 TTS 音色并整理收藏、分组与备注，离线也能选用；首次为空会自动从引擎同步一次。</p>
      </div>
      <a-button type="primary" :loading="syncing" @click="onSync">
        <template #icon><CloudSyncOutlined /></template>
        从云端同步
      </a-button>
    </div>

    <!-- 筛选栏 -->
    <div class="vm-filters">
      <a-select
        v-model:value="filters.provider"
        class="vm-filter"
        placeholder="引擎"
        allow-clear
        :options="providerOptions"
        @change="onProviderChange"
      />
      <a-select
        v-model:value="filters.locale"
        class="vm-filter"
        placeholder="语言"
        allow-clear
        :options="localeOptions"
        @change="loadVoices"
      />
      <a-select
        v-model:value="filters.gender"
        class="vm-filter"
        placeholder="性别"
        allow-clear
        :options="genderOptions"
        @change="loadVoices"
      />
      <a-select
        v-model:value="filters.group"
        class="vm-filter"
        placeholder="分组"
        allow-clear
        :options="groupOptions"
        @change="loadVoices"
      />
      <a-input-search
        v-model:value="filters.keyword"
        class="vm-filter vm-filter--search"
        placeholder="搜索音色名 / 展示名"
        allow-clear
        @search="loadVoices"
        @change="onKeywordChange"
      />
      <a-switch
        v-model:checked="filters.favorite"
        checked-children="收藏"
        un-checked-children="全部"
        @change="loadVoices"
      />
      <a-button :loading="loading" @click="loadVoices">
        <template #icon><ReloadOutlined /></template>
        刷新
      </a-button>
    </div>

    <!-- 表格 -->
    <a-table
      :columns="columns"
      :data-source="voices"
      :loading="loading"
      row-key="voiceURI"
      size="middle"
      :pagination="{ pageSize: 20, showSizeChanger: true, pageSizeOptions: ['20', '50', '100'] }"
      bordered
    >
      <!-- 收藏 -->
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'favorite'">
          <a-button
            type="text"
            :title="record.favorite ? '取消收藏' : '收藏'"
            @click="onToggleFavorite(record)"
          >
            <StarFilled v-if="record.favorite" style="color: #faad14; font-size: 16px" />
            <StarOutlined v-else style="color: #8c8c8c; font-size: 16px" />
          </a-button>
        </template>

        <!-- 分组（内联编辑） -->
        <template v-else-if="column.key === 'voiceGroup'">
          <a-input
            :value="record.voiceGroup"
            size="small"
            placeholder="未分组"
            @blur="(e) => onSaveGroup(record, e.target.value)"
            @press-enter="(e) => onSaveGroup(record, e.target.value)"
          />
        </template>

        <!-- 备注（内联编辑） -->
        <template v-else-if="column.key === 'remark'">
          <a-input
            :value="record.remark"
            size="small"
            placeholder="添加备注"
            @blur="(e) => onSaveRemark(record, e.target.value)"
            @press-enter="(e) => onSaveRemark(record, e.target.value)"
          />
        </template>

        <!-- 操作 -->
        <template v-else-if="column.key === 'action'">
          <a-button type="link" size="small" :loading="previewing === record.voiceURI" @click="onPreview(record)">
            <template #icon><CustomerServiceOutlined /></template>
            试听
          </a-button>
        </template>

        <template v-else>{{ record[column.key] }}</template>
      </template>
    </a-table>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import {
  StarOutlined,
  StarFilled,
  ReloadOutlined,
  CloudSyncOutlined,
  CustomerServiceOutlined,
} from '@ant-design/icons-vue'
import { getTtsVoices, syncTtsVoices, updateTtsVoiceMeta, getTtsVoiceGroups } from '../api/ttsVoice'
import { useBackendTts } from '../composables/useBackendTts'

const backendTts = useBackendTts()

const loading = ref(false)
const syncing = ref(false)
const previewing = ref('')
const voices = ref([])
const allVoices = ref([]) // 仅受引擎过滤的全集，用于构建语言/性别下拉选项，避免筛选后选项塌陷
const groups = ref([])
const providerOptions = ref([])

const filters = reactive({
  provider: undefined,
  locale: undefined,
  gender: undefined,
  group: undefined,
  keyword: '',
  favorite: false,
})

const columns = [
  { title: '收藏', key: 'favorite', width: 60, align: 'center' },
  { title: '音色名', dataIndex: 'voiceURI', key: 'voiceURI', width: 220, ellipsis: true },
  { title: '展示名', dataIndex: 'friendlyName', key: 'friendlyName', width: 160, ellipsis: true },
  { title: '语言', dataIndex: 'locale', key: 'locale', width: 90 },
  { title: '性别', dataIndex: 'gender', key: 'gender', width: 80 },
  { title: '引擎', dataIndex: 'provider', key: 'provider', width: 100 },
  { title: '分组', key: 'voiceGroup', width: 140 },
  { title: '备注', key: 'remark', width: 180 },
  { title: '操作', key: 'action', width: 90, align: 'center', fixed: 'right' },
]

const localeOptions = computed(() => {
  const set = new Set(allVoices.value.map((v) => v.locale).filter(Boolean))
  return [...set].sort().map((l) => ({ label: l, value: l }))
})
const genderOptions = computed(() => {
  const set = new Set(allVoices.value.map((v) => v.gender).filter(Boolean))
  return [...set].sort().map((g) => ({ label: g, value: g }))
})
const groupOptions = computed(() =>
  groups.value.map((g) => ({ label: g, value: g }))
)

async function loadVoices() {
  loading.value = true
  try {
    const params = {}
    if (filters.provider) params.provider = filters.provider
    if (filters.locale) params.locale = filters.locale
    if (filters.gender) params.gender = filters.gender
    if (filters.group) params.group = filters.group
    if (filters.favorite) params.favorite = true
    if (filters.keyword) params.keyword = filters.keyword
    const res = await getTtsVoices(params)
    voices.value = res.data || []
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

// 仅按引擎加载全集，用于下拉选项（不受语言/性别/分组/关键字筛选影响）
async function loadBaseVoices() {
  try {
    const params = {}
    if (filters.provider) params.provider = filters.provider
    const res = await getTtsVoices(params)
    allVoices.value = res.data || []
  } catch {
    allVoices.value = []
  }
}

async function loadGroups() {
  try {
    const res = await getTtsVoiceGroups()
    groups.value = res.data || []
  } catch {
    groups.value = []
  }
}

async function loadProviders() {
  try {
    const info = await backendTts.provider()
    const list = info.available || []
    providerOptions.value = list.map((p) => ({ label: p, value: p }))
  } catch {
    providerOptions.value = [{ label: 'edge-tts', value: 'edge-tts' }]
  }
}

async function onSync() {
  syncing.value = true
  try {
    const res = await syncTtsVoices(filters.provider || undefined)
    const n = (res.data && res.data.synced) || 0
    message.success(`同步完成，新增/更新 ${n} 条音色`)
    await Promise.all([loadVoices(), loadGroups()])
  } catch {
    // 拦截器已提示
  } finally {
    syncing.value = false
  }
}

async function onToggleFavorite(record) {
  try {
    await updateTtsVoiceMeta(record.voiceURI, { favorite: !record.favorite })
    record.favorite = !record.favorite
  } catch {
    // 拦截器已提示
  }
}

async function onSaveGroup(record, value) {
  const v = (value || '').trim()
  if (v === (record.voiceGroup || '')) return
  try {
    await updateTtsVoiceMeta(record.voiceURI, { voiceGroup: v })
    record.voiceGroup = v
    await loadGroups()
  } catch {
    // 拦截器已提示
  }
}

async function onSaveRemark(record, value) {
  const v = value || ''
  if (v === (record.remark || '')) return
  try {
    await updateTtsVoiceMeta(record.voiceURI, { remark: v })
    record.remark = v
  } catch {
    // 拦截器已提示
  }
}

async function onPreview(record) {
  if (previewing.value) return
  previewing.value = record.voiceURI
  try {
    const audio = await backendTts.synthesize('你好，这是数字人音色试听。', {
      voice: record.voiceURI,
      provider: record.provider,
    })
    await audio.play()
  } catch {
    message.error('试听失败，请检查 TTS 引擎连通性')
  } finally {
    previewing.value = ''
  }
}

function onKeywordChange() {
  // 清空关键字时自动重新查询（allow-clear 触发）
  if (!filters.keyword) loadVoices()
}

async function onProviderChange() {
  await Promise.all([loadBaseVoices(), loadVoices()])
}

onMounted(() => {
  loadProviders()
  loadGroups()
  loadBaseVoices()
  loadVoices()
})
</script>

<style scoped>
.voice-manage {
  padding: 20px 24px;
  height: 100%;
  overflow: auto;
  box-sizing: border-box;
}
.vm-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.vm-title {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 600;
  color: var(--color-ink);
}
.vm-sub {
  margin: 0;
  font-size: 13px;
  color: var(--color-mute);
  max-width: 720px;
  line-height: 1.5;
}
.vm-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.vm-filter {
  width: 150px;
}
.vm-filter--search {
  width: 220px;
}
</style>
