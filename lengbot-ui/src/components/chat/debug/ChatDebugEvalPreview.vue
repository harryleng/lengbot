<template>
  <ChatDebugPreviewShell class="debug-eval-preview">
    <div v-if="payload" class="eval-preview-surface">
      <template v-if="payload.mode === 'evaluator'">
        <div class="eval-header">
          <div>
            <div class="eval-title">{{ payload.evaluator?.name || '评估器结果' }}</div>
            <div class="eval-desc">{{ payload.evaluator?.version || '-' }}</div>
          </div>
          <div class="eval-score" :class="scoreClass(payload.evaluator?.score)">
            <span>{{ formatScore(payload.evaluator?.score) }}</span>
            <small>评分</small>
          </div>
        </div>
        <div class="eval-section">
          <div class="eval-section-title">评分理由</div>
          <p class="eval-reason">{{ payload.evaluator?.reason || '-' }}</p>
        </div>
        <div class="eval-section">
          <div class="eval-section-title">评估 Prompt</div>
          <pre class="eval-pre">{{ payload.evaluator?.prompt || '-' }}</pre>
        </div>
      </template>

      <template v-else>
        <div class="eval-header">
          <div>
            <div class="eval-title">{{ payload.experiment?.name || '实验预览' }}</div>
            <div class="eval-desc">
              {{ payload.experiment?.datasetName || '-' }} · {{ payload.experiment?.promptKey || '-' }}
            </div>
          </div>
          <a-tag :color="statusColor(payload.experiment?.status)">
            {{ statusLabel(payload.experiment?.status) }}
          </a-tag>
        </div>

        <a-alert
          v-if="payload.experiment?.status === 'failed' && payload.experiment?.errorMessage"
          type="error"
          show-icon
          class="eval-alert"
          message="实验执行失败"
          :description="payload.experiment.errorMessage"
        />

        <div v-if="payload.experiment?.status === 'running'" class="eval-running">
          <a-progress :percent="payload.experiment?.progress || 0" />
          <span>实验正在评测中</span>
        </div>

        <div class="eval-card-grid">
          <div v-for="ev in payload.evaluators || []" :key="ev.evaluatorName" class="eval-card">
            <div class="eval-card-name">{{ ev.evaluatorName }}</div>
            <div class="eval-card-version">{{ ev.evaluatorVersion || '-' }}</div>
            <div class="eval-score compact" :class="scoreClass(ev.avgScore)">
              <span>{{ formatScore(ev.avgScore) }}</span>
              <small>平均分</small>
            </div>
            <a-progress
              :percent="Math.round((ev.avgScore || 0) * 100)"
              :show-info="false"
              :stroke-color="progressColor(ev.avgScore)"
            />
            <div class="eval-card-meta">已评测 {{ ev.evaluatedCount || 0 }} / {{ ev.totalCount || 0 }}</div>
          </div>
        </div>

        <a-table
          v-if="payload.rows?.length"
          :data-source="payload.rows"
          :columns="columns"
          :pagination="false"
          row-key="id"
          size="small"
          class="eval-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'score'">
              <span class="score-tag" :class="scoreClass(record.score)">{{ formatScore(record.score) }}</span>
            </template>
            <template v-else>
              <span class="cell-preview">{{ record[column.dataIndex] || '-' }}</span>
            </template>
          </template>
        </a-table>
      </template>
    </div>
    <div v-else class="debug-preview-empty">点击「解析预览」查看 Eval 渲染结果</div>
  </ChatDebugPreviewShell>
</template>

<script setup>
import ChatDebugPreviewShell from './ChatDebugPreviewShell.vue'

defineProps({
  payload: { type: Object, default: null },
})

const columns = [
  { title: '输入', dataIndex: 'input', key: 'input', ellipsis: true },
  { title: '实际输出', dataIndex: 'actualOutput', key: 'actualOutput', ellipsis: true },
  { title: '参考答案', dataIndex: 'referenceOutput', key: 'referenceOutput', ellipsis: true },
  { title: '评分', dataIndex: 'score', key: 'score', width: 76 },
  { title: '理由', dataIndex: 'reason', key: 'reason', ellipsis: true },
]

function formatScore(score) {
  return score == null ? '-' : Number(score).toFixed(2)
}

function scoreClass(score) {
  if (score == null) return ''
  if (score >= 0.8) return 'score-high'
  if (score >= 0.5) return 'score-mid'
  return 'score-low'
}

function progressColor(score) {
  if (score == null) return '#d9d9d9'
  if (score >= 0.8) return '#52c41a'
  if (score >= 0.5) return '#faad14'
  return '#ff4d4f'
}

function statusLabel(status) {
  return (
    {
      completed: '已完成',
      running: '运行中',
      failed: '失败',
      stopped: '已停止',
    }[status] || '未知'
  )
}

function statusColor(status) {
  return (
    {
      completed: 'green',
      running: 'blue',
      failed: 'red',
      stopped: 'orange',
    }[status] || 'default'
  )
}
</script>

<style scoped>
.debug-eval-preview {
  height: 100%;
}

.eval-preview-surface {
  width: 100%;
  max-width: 960px;
  padding: 16px 20px;
  margin: 0 auto;
}

.eval-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
}

.eval-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--gray-900);
}

.eval-desc,
.eval-card-version,
.eval-card-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--gray-500);
}

.eval-alert,
.eval-running,
.eval-card-grid,
.eval-section {
  margin-bottom: 14px;
}

.eval-running {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--gray-200);
  border-radius: 6px;
  background: var(--color-canvas);
}

.eval-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 10px;
}

.eval-card {
  padding: 12px;
  border: 1px solid var(--gray-200);
  border-radius: 6px;
  background: var(--color-canvas);
}

.eval-card-name,
.eval-section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
}

.eval-score {
  min-width: 86px;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--gray-50);
  text-align: center;
}

.eval-score.compact {
  margin: 12px 0 8px;
}

.eval-score span {
  display: block;
  font-size: 22px;
  font-weight: 700;
}

.eval-score small {
  color: var(--gray-500);
}

.score-high span,
.score-tag.score-high {
  color: #16a34a;
}

.score-mid span,
.score-tag.score-mid {
  color: #d97706;
}

.score-low span,
.score-tag.score-low {
  color: #dc2626;
}

.eval-reason {
  margin: 8px 0 0;
  color: var(--gray-700);
  line-height: 1.7;
}

.eval-pre {
  margin: 8px 0 0;
  padding: 12px;
  border: 1px solid var(--gray-200);
  border-radius: 6px;
  background: var(--color-canvas-soft);
  white-space: pre-wrap;
  word-break: break-word;
}

.score-tag {
  display: inline-block;
  min-width: 42px;
  padding: 2px 6px;
  border-radius: 999px;
  background: var(--gray-100);
  text-align: center;
  font-weight: 600;
}

.cell-preview {
  display: inline-block;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.debug-preview-empty {
  padding: 48px 24px;
  text-align: center;
  color: var(--gray-400);
  font-size: 14px;
}
</style>
