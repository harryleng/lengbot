<template>
  <div class="ocr-parse-result">
    <!-- 纯文本降级 -->
    <div v-if="isPlainText" class="opr-plain">
      <pre>{{ displayText }}</pre>
    </div>

    <template v-else>
      <!-- 错误：连接/查询失败 / 解析异常 -->
      <div v-if="data.success === false || data.error" class="opr-card opr-card-error">
        <div class="opr-header opr-header-error">
          <component :is="errorIcon" class="opr-header-icon" />
          <span>{{ errorTitle }}</span>
        </div>
        <pre class="opr-error-msg">{{ data.error || '未知错误' }}</pre>
      </div>

      <!-- 成功：解析结果摘要 -->
      <div v-else-if="data.parsed_path != null" class="opr-card">
        <div class="opr-header">
          <ScanOutlined class="opr-header-icon" />
          <span class="opr-path">{{ data.source_path }}</span>
          <span v-if="data.char_count != null" class="opr-meta">{{ data.char_count }} 字</span>
          <button v-if="hasPreview" class="opr-detail-btn" @click="showModal = true">
            <EyeOutlined />
            查看详情
          </button>
        </div>

        <div class="opr-output-row">
          <FileTextOutlined class="opr-output-icon" />
          <span class="opr-output-label">已保存至</span>
          <span class="opr-output-path">{{ data.parsed_path }}</span>
        </div>

        <pre v-if="hasPreview" class="opr-preview">{{ data.preview }}</pre>
        <div v-else class="opr-empty">未识别到文本内容</div>
        <div v-if="data.truncated" class="opr-truncated-hint">... 预览已截断，完整内容请读取结果文件</div>

        <a-modal
          v-model:open="showModal"
          title="OCR 识别预览"
          :footer="null"
          :width="680"
          :body-style="{ maxHeight: '70vh', overflow: 'auto', padding: '20px' }"
          destroy-on-close
        >
          <div class="opr-modal-meta">
            <div class="opr-path-chip">
              <FileSearchOutlined class="opr-path-chip-icon" />
              <span class="opr-path-chip-label">源文件</span>
              <span class="opr-path-chip-value">{{ data.source_path }}</span>
            </div>
            <div class="opr-path-chip">
              <FileTextOutlined class="opr-path-chip-icon" />
              <span class="opr-path-chip-label">结果文件</span>
              <span class="opr-path-chip-value">{{ data.parsed_path }}</span>
            </div>
            <div class="opr-char-row">
              <span class="opr-char-count">
                <span class="opr-char-count-num">{{ data.char_count }}</span>
                <span class="opr-char-count-unit">字</span>
              </span>
            </div>
          </div>
          <pre class="opr-modal-preview"
            >{{ data.preview }}{{ data.truncated ? '\n\n... 预览已截断，完整内容请读取结果文件' : '' }}</pre>
        </a-modal>
      </div>

      <!-- 兜底 -->
      <div v-else class="opr-card">
        <pre class="opr-json">{{ formattedJson }}</pre>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import {
  FileTextOutlined,
  ScanOutlined,
  EyeOutlined,
  FileSearchOutlined,
  CloseCircleOutlined,
  ApiOutlined,
} from '@ant-design/icons-vue'

const props = defineProps({
  event: { type: Object, required: true },
})

const showModal = ref(false)

const rawResult = computed(() => props.event.result || '')

const data = computed(() => {
  try {
    return JSON.parse(rawResult.value)
  } catch {
    return null
  }
})

const isPlainText = computed(() => !data.value || typeof data.value !== 'object')
const displayText = computed(() => (typeof data.value === 'string' ? data.value : rawResult.value))

const formattedJson = computed(() => {
  if (!data.value) return rawResult.value
  try {
    return JSON.stringify(data.value, null, 2)
  } catch {
    return rawResult.value
  }
})

const hasPreview = computed(() => !!(data.value?.preview && data.value.preview.length))

// 错误分类：连接/查询失败 / 其他异常
const errorText = computed(() => String(data.value?.error || ''))
const isConnFailed = computed(
  () =>
    errorText.value.includes('初始化') ||
    errorText.value.includes('连接') ||
    errorText.value.includes('引擎') ||
    errorText.value.includes('查询') ||
    errorText.value.includes('下载') ||
    errorText.value.includes('读取')
)

const errorTitle = computed(() => (isConnFailed.value ? 'OCR 服务不可用' : 'OCR 解析失败'))

const errorIcon = computed(() => (isConnFailed.value ? ApiOutlined : CloseCircleOutlined))
</script>

<style lang="less" scoped>
.ocr-parse-result {
  font-size: 12px;

  .opr-plain pre {
    margin: 0;
    padding: 8px 10px;
    background: var(--gray-25);
    border-radius: 6px;
    line-height: 1.5;
    color: var(--gray-700);
    white-space: pre-wrap;
    word-break: break-word;
  }

  // ── 卡片容器 ──
  .opr-card {
    border: 1px solid var(--purple-300);
    border-left: 3px solid var(--purple-500);
    border-radius: 8px;
    overflow: hidden;
    background: var(--color-purple-bg);
  }
  .opr-card-error {
    border-color: var(--color-error-soft);
    border-left-color: var(--color-error);
    background: var(--color-error-bg);
  }

  // ── Header ──
  .opr-header {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 10px;
    background: var(--color-purple-bg);
    border-bottom: 1px solid var(--purple-300);
    font-size: 12px;
    font-weight: 600;
    color: var(--purple-800);
  }
  .opr-header-error {
    background: var(--color-error-bg);
    border-bottom-color: var(--color-error-soft);
    color: var(--color-error-deep);
  }
  .opr-header-icon {
    font-size: 14px;
    color: var(--purple-600);
    flex-shrink: 0;
  }
  .opr-header-error .opr-header-icon {
    color: var(--color-error);
  }

  .opr-path {
    font-family: 'Monaco', 'Menlo', monospace;
    font-size: 11px;
    color: var(--purple-700);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex: 1;
    min-width: 0;
  }
  .opr-meta {
    font-size: 11px;
    color: var(--purple-500);
    flex-shrink: 0;
    font-weight: 400;
  }
  .opr-detail-btn {
    appearance: none;
    border: 1px solid var(--purple-300);
    border-radius: 6px;
    background: var(--color-canvas);
    color: var(--purple-600);
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 12px;
    cursor: pointer;
    padding: 6px 12px;
    flex-shrink: 0;
    font-weight: 500;
    transition: all 0.2s ease;
    &:hover {
      background: var(--color-purple-bg);
      transform: translateY(-1px);
      box-shadow: 0 2px 6px rgba(139, 92, 246, 0.15);
    }
    &:active {
      transform: translateY(0);
    }
  }

  // ── 结果文件行 ──
  .opr-output-row {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 12px;
    color: var(--purple-700);
    border-bottom: 1px solid var(--purple-200);
  }
  .opr-output-icon {
    font-size: 12px;
    color: var(--purple-400);
    flex-shrink: 0;
  }
  .opr-output-label {
    font-size: 11px;
    color: var(--purple-500);
    flex-shrink: 0;
  }
  .opr-output-path {
    font-family: 'Monaco', 'Menlo', monospace;
    font-size: 11px;
    color: var(--purple-700);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  // ── 预览 ──
  .opr-preview {
    margin: 0;
    padding: 10px 12px;
    background: var(--color-purple-bg);
    color: var(--gray-700);
    font-size: 12px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
    max-height: 200px;
    overflow-y: auto;
    font-family: 'Monaco', 'Menlo', monospace;
  }
  .opr-empty {
    padding: 16px 12px;
    text-align: center;
    color: var(--purple-400);
    font-style: italic;
  }
  .opr-truncated-hint {
    padding: 6px 12px;
    font-size: 11px;
    color: var(--purple-500);
    text-align: center;
    border-top: 1px solid var(--purple-200);
    background: var(--color-purple-bg);
  }

  // ── 错误内容 ──
  .opr-error-msg {
    margin: 0;
    padding: 10px 12px;
    color: var(--color-error-deep);
    font-size: 12px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
  }

  // ── 兜底 JSON（代码块深色风格保留） ──
  .opr-json {
    margin: 0;
    padding: 10px 12px;
    background: var(--gray-900);
    color: var(--gray-100);
    font-size: 12px;
    line-height: 1.5;
    white-space: pre-wrap;
    word-break: break-word;
    font-family: 'Monaco', 'Menlo', monospace;
  }

  // ── 预览弹窗（slot 内容会被加 data-v-xxx，scoped 样式可命中） ──
  .opr-modal-meta {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin-bottom: 16px;
  }
  .opr-path-chip {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
    padding: 6px 12px;
    background: var(--color-purple-bg);
    border: 1px solid var(--purple-200);
    border-left: 3px solid var(--purple-500);
    border-radius: 6px;
  }
  .opr-path-chip-icon {
    font-size: 14px;
    color: var(--purple-600);
    flex-shrink: 0;
  }
  .opr-path-chip-label {
    font-size: 11px;
    font-weight: 600;
    color: var(--purple-500);
    white-space: nowrap;
    flex-shrink: 0;
  }
  .opr-path-chip-value {
    font-size: 12px;
    font-weight: 500;
    color: var(--purple-700);
    font-family: 'Monaco', 'Menlo', monospace;
    word-break: break-all;
    min-width: 0;
  }
  .opr-char-row {
    display: flex;
    justify-content: flex-end;
  }
  .opr-char-count {
    display: inline-flex;
    align-items: baseline;
    gap: 3px;
    padding: 3px 10px;
    background: var(--purple-700);
    border-radius: 12px;
  }
  .opr-char-count-num {
    font-size: 13px;
    font-weight: 700;
    color: var(--color-canvas);
    line-height: 1;
  }
  .opr-char-count-unit {
    font-size: 11px;
    color: var(--purple-200);
  }
  .opr-modal-preview {
    margin: 0;
    padding: 16px;
    background: var(--gray-900);
    color: var(--gray-100);
    font-size: 13px;
    line-height: 1.7;
    white-space: pre-wrap;
    word-break: break-word;
    border-radius: 8px;
    font-family: 'Monaco', 'Menlo', monospace;
  }
}
</style>
