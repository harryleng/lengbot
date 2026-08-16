<template>
  <div>
    <div v-if="isPlainText" class="sql-result-plain">
      <pre>{{ displayText }}</pre>
    </div>

    <template v-else>
      <!-- SQL 代码块 -->
      <div class="sql-code-block">
        <CodeOutlined class="sql-code-icon" />
        <code class="sql-code-text">{{ data.sql }}</code>
      </div>

      <!-- 结果表格 -->
      <div v-if="data.rows?.length" class="sql-table-wrap">
        <div class="inline-table-scroll sql-table-scroll">
          <table class="sql-table">
            <thead>
              <tr>
                <th class="sql-th sql-th-idx">#</th>
                <th v-for="(col, ci) in data.columns" :key="ci" class="sql-th">{{ col }}</th>
                <th class="sql-th sql-th-action">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(row, ri) in displayRows"
                :key="ri"
                :class="['sql-row', ri % 2 === 0 ? 'sql-row-even' : 'sql-row-odd']"
              >
                <td class="sql-td sql-td-idx">{{ ri + 1 }}</td>
                <td v-for="(val, vi) in row" :key="vi" class="sql-td" :title="String(val ?? '')">{{ val ?? '' }}</td>
                <td class="sql-td sql-td-action">
                  <button class="sql-detail-btn" @click="openRowDetail(ri)">详情</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-else class="sql-empty">查询无结果</div>

      <!-- 统计行 -->
      <div class="sql-stats">
        <span>共 {{ data.total_rows }} 行</span>
        <span v-if="data.has_more" class="sql-stats-warn">（仅显示前 {{ data.rows.length }} 行）</span>
        <span class="sql-stats-elapsed">耗时 {{ data.elapsed_ms }}ms</span>
        <button class="sql-stats-toggle" @click="detailVisible = true">
          <ExpandOutlined />
          查看全部
        </button>
      </div>
    </template>

    <!-- 全量详情弹窗 -->
    <a-modal
      v-model:open="detailVisible"
      title="查询结果详情"
      :footer="null"
      width="900px"
      :body-style="{ maxHeight: '70vh', overflow: 'auto', padding: '16px' }"
    >
      <div class="dialog-scroll-body">
        <div class="sql-modal-sqlbar">
          <code class="sql-modal-sql">{{ data.sql }}</code>
          <span class="sql-modal-stats">共 {{ data.total_rows }} 行 · {{ data.elapsed_ms }}ms</span>
        </div>
        <div class="sql-modal-table-wrap">
          <div class="modal-table-scroll sql-modal-scroll">
            <table class="sql-table sql-modal-table">
              <thead>
                <tr>
                  <th class="sql-th sql-th-idx sql-th-sticky-corner">#</th>
                  <th v-for="(col, ci) in data.columns" :key="ci" class="sql-th sql-th-sticky-top">{{ col }}</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(row, ri) in data.rows"
                  :key="ri"
                  :class="['sql-row', ri % 2 === 0 ? 'sql-row-even' : 'sql-row-odd']"
                >
                  <td class="sql-td sql-td-idx sql-td-sticky-left">{{ ri + 1 }}</td>
                  <td v-for="(val, vi) in row" :key="vi" class="sql-td sql-modal-cell" :title="String(val ?? '')">
                    {{ val ?? '' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </a-modal>

    <!-- 单行详情弹窗 -->
    <a-modal
      v-model:open="rowDetailVisible"
      :title="'第 ' + (activeRowIndex + 1) + ' 行详情'"
      :footer="null"
      width="680px"
      :body-style="{ maxHeight: '70vh', overflow: 'auto', padding: '16px' }"
    >
      <div class="dialog-scroll-body">
        <div v-if="activeRow" class="sql-row-detail-list">
          <div v-for="(val, ci) in activeRow" :key="ci" class="sql-row-detail-item">
            <div class="sql-row-detail-head">
              {{ data.columns?.[ci] || '列 ' + (ci + 1) }}
            </div>
            <div class="sql-row-detail-body">
              {{ val ?? '' }}
            </div>
          </div>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { CodeOutlined, ExpandOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  event: { type: Object, required: true },
})

const rawResult = computed(() => props.event.result || '')
const detailVisible = ref(false)
const rowDetailVisible = ref(false)
const activeRowIndex = ref(0)

const data = computed(() => {
  try {
    return JSON.parse(rawResult.value)
  } catch {
    return null
  }
})
const isPlainText = computed(() => !data.value || typeof data.value !== 'object')
const displayText = computed(() => (typeof data.value === 'string' ? data.value : rawResult.value))

// 内嵌表格最多显示 5 行
const displayRows = computed(() => {
  if (!data.value?.rows) return []
  return data.value.rows.slice(0, 5)
})

// 当前行数据
const activeRow = computed(() => {
  if (!data.value?.rows || activeRowIndex.value >= data.value.rows.length) return null
  return data.value.rows[activeRowIndex.value]
})

function openRowDetail(ri) {
  activeRowIndex.value = ri
  rowDetailVisible.value = true
}
</script>

<style scoped>
/* 纯文本结果（非结构化返回） */
.sql-result-plain {
  margin: 0;
  padding: 8px 10px;
  background: var(--color-canvas-soft);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--color-text-dark);
  white-space: pre-wrap;
  word-break: break-word;
}
.sql-result-plain pre {
  margin: 0;
}

/* SQL 代码块（始终深色，与代码编辑器风格一致） */
.sql-code-block {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 10px;
  background: var(--gray-900);
  border-radius: 8px;
  margin-bottom: 8px;
}
.sql-code-icon {
  color: #569cd6;
  font-size: 13px;
  margin-top: 1px;
  flex-shrink: 0;
}
.sql-code-text {
  font-size: 12px;
  color: var(--gray-100);
  line-height: 1.5;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 结果表格 */
.sql-table-wrap {
  border: 1px solid var(--blue-200);
  border-radius: 8px;
  overflow: hidden;
  margin-bottom: 6px;
  background: var(--color-canvas);
}
.sql-table-scroll {
  overflow-x: auto;
  max-height: 240px;
  overflow-y: auto;
}
.sql-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.sql-th {
  text-align: left;
  padding: 7px 10px;
  background: var(--blue-100);
  border-bottom: 1px solid var(--blue-200);
  color: var(--blue-800);
  font-weight: 600;
  white-space: nowrap;
  position: sticky;
  top: 0;
  z-index: 1;
}
.sql-th-idx {
  text-align: center;
  padding: 7px 8px;
  width: 32px;
}
.sql-th-action {
  text-align: center;
  padding: 7px 8px;
  width: 60px;
}
.sql-th-sticky-corner {
  position: sticky;
  top: 0;
  left: 0;
  z-index: 2;
}
.sql-th-sticky-top {
  position: sticky;
  top: 0;
  z-index: 1;
}
.sql-row-even {
  background: var(--color-canvas);
}
.sql-row-odd {
  background: var(--blue-50);
}
.sql-td {
  padding: 6px 10px;
  border-bottom: 1px solid var(--blue-100);
  color: var(--color-text-dark);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sql-td-idx {
  text-align: center;
  padding: 6px 8px;
  color: var(--color-mute);
  font-size: 10px;
}
.sql-td-action {
  text-align: center;
  padding: 6px 8px;
}
.sql-td-sticky-left {
  position: sticky;
  left: 0;
  z-index: 1;
  white-space: nowrap;
}
.sql-detail-btn {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--blue-600);
  font-size: 11px;
  cursor: pointer;
  padding: 2px 6px;
  white-space: nowrap;
}
.sql-empty {
  text-align: center;
  padding: 16px;
  color: var(--color-mute);
  font-size: 12px;
  background: var(--color-canvas-soft);
  border-radius: 8px;
  margin-bottom: 6px;
}

/* 统计行 */
.sql-stats {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--blue-100);
  border-radius: 8px;
  font-size: 11px;
  color: var(--blue-800);
}
.sql-stats-warn {
  color: var(--color-error);
}
.sql-stats-elapsed {
  margin-left: auto;
  color: var(--blue-500);
}
.sql-stats-toggle {
  appearance: none;
  border: 1px solid var(--blue-200);
  border-radius: 4px;
  background: var(--blue-50);
  color: var(--blue-700);
  font-size: 11px;
  padding: 2px 10px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

/* 全量详情弹窗 */
.sql-modal-sqlbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding: 10px 12px;
  background: var(--blue-100);
  border: 1px solid var(--blue-200);
  border-radius: 8px;
}
.sql-modal-sql {
  font-size: 12px;
  color: var(--blue-800);
  font-family: 'Monaco', 'Menlo', monospace;
  flex: 1;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
}
.sql-modal-stats {
  font-size: 11px;
  color: var(--blue-500);
  white-space: nowrap;
}
.sql-modal-table-wrap {
  border: 1px solid var(--blue-200);
  border-radius: 8px;
  overflow: hidden;
}
.sql-modal-scroll {
  overflow: auto;
  max-height: 60vh;
}
.sql-modal-table {
  min-width: 100%;
  width: auto;
}
.sql-modal-cell {
  white-space: nowrap;
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 单行详情弹窗 */
.sql-row-detail-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.sql-row-detail-item {
  border: 1px solid var(--blue-200);
  border-radius: 8px;
  overflow: hidden;
}
.sql-row-detail-head {
  padding: 6px 10px;
  background: var(--blue-100);
  font-size: 12px;
  font-weight: 600;
  color: var(--blue-800);
  border-bottom: 1px solid var(--blue-200);
}
.sql-row-detail-body {
  padding: 10px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--color-text-dark);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 300px;
  overflow-y: auto;
}
</style>
