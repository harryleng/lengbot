<template>
  <div class="debug-eval-panel">
    <div class="debug-panel-toolbar">
      <a-button type="primary" @click="$emit('parse')">解析预览</a-button>
      <a-select v-model:value="selectedSample" :options="sampleOptions" style="width: 200px" @change="loadSample" />
      <a-button @click="formatJson">格式化 JSON</a-button>
    </div>

    <div class="debug-field">
      <div class="debug-editor-label">Eval Fixture JSON</div>
      <a-textarea v-model:value="payloadJson" :rows="20" class="debug-json-textarea" @change="parseError = ''" />
    </div>

    <a-alert
      v-if="parseError"
      type="error"
      :message="parseError"
      show-icon
      closable
      class="debug-parse-error"
      @close="parseError = ''"
    />
  </div>
</template>

<script setup>
import { ref } from 'vue'

const sampleOptions = [
  { value: 'completed', label: '实验完成' },
  { value: 'running', label: '实验运行中' },
  { value: 'failed', label: '实验失败' },
  { value: 'single-evaluator', label: '单次评估器结果' },
]

const samples = {
  completed: {
    mode: 'experiment',
    experiment: {
      name: '客服 Prompt v2 回归评测',
      status: 'completed',
      progress: 100,
      datasetName: '客服高频问题集',
      datasetVersion: 'v2026.07',
      promptKey: 'support.answer',
      promptVersion: 'v2',
    },
    evaluators: [
      { evaluatorName: '准确性评估器', evaluatorVersion: 'v1', avgScore: 0.91, evaluatedCount: 20, totalCount: 20 },
      { evaluatorName: '安全性评估器', evaluatorVersion: 'v3', avgScore: 0.76, evaluatedCount: 20, totalCount: 20 },
    ],
    rows: [
      {
        id: '1',
        input: '如何上传知识库文档？',
        actualOutput: '进入知识库详情后上传文件。',
        referenceOutput: '在知识库详情页点击上传文档。',
        score: 0.92,
        reason: '回答覆盖关键步骤。',
      },
      {
        id: '2',
        input: '工作流如何发布？',
        actualOutput: '编辑后点击发布。',
        referenceOutput: '配置完整后在编排页发布。',
        score: 0.68,
        reason: '答案可用但缺少配置校验说明。',
      },
    ],
  },
  running: {
    mode: 'experiment',
    experiment: {
      name: '工具调用质量评测',
      status: 'running',
      progress: 45,
      datasetName: '工具问题集',
      datasetVersion: 'v1',
      promptKey: 'tool.router',
      promptVersion: 'draft',
    },
    evaluators: [
      { evaluatorName: '工具选择评估器', evaluatorVersion: 'v1', avgScore: 0.82, evaluatedCount: 9, totalCount: 20 },
    ],
    rows: [],
  },
  failed: {
    mode: 'experiment',
    experiment: {
      name: '异常样例评测',
      status: 'failed',
      progress: 18,
      datasetName: '异常输入集',
      datasetVersion: 'v2',
      promptKey: 'agent.guardrail',
      promptVersion: 'v1',
      errorMessage: '评估器返回 JSON 解析失败',
    },
    evaluators: [],
    rows: [],
  },
  'single-evaluator': {
    mode: 'evaluator',
    evaluator: {
      name: '准确性评估器',
      version: 'v1',
      score: 0.88,
      reason: '回答与参考答案高度一致，只有少量措辞差异。',
      prompt: '请根据 actual_output 与 expected_output 的一致性给出 0-1 分。',
    },
  },
}

const emit = defineEmits(['parse'])
const selectedSample = ref('completed')
const payloadJson = ref('')
const parseError = ref('')

function loadSample() {
  payloadJson.value = JSON.stringify(samples[selectedSample.value], null, 2)
  parseError.value = ''
}

function formatJson() {
  const payload = validateAndGetPayload()
  if (!payload) return
  payloadJson.value = JSON.stringify(payload, null, 2)
}

function validateAndGetPayload() {
  parseError.value = ''
  try {
    const payload = JSON.parse(payloadJson.value)
    if (!payload || typeof payload !== 'object') throw new Error('Fixture 必须是 JSON 对象')
    if (!payload.mode) payload.mode = 'experiment'
    if (payload.mode === 'experiment' && !payload.experiment) throw new Error('experiment 不能为空')
    if (payload.mode === 'evaluator' && !payload.evaluator) throw new Error('evaluator 不能为空')
    return payload
  } catch (e) {
    parseError.value = e.message || 'JSON 格式错误'
    return null
  }
}

loadSample()

defineExpose({ validateAndGetPayload, loadSample })
</script>

<style scoped>
.debug-eval-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.debug-panel-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.debug-field {
  margin-bottom: 12px;
}

.debug-editor-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
  margin-bottom: 8px;
}

.debug-json-textarea {
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
}

.debug-parse-error {
  margin-top: 8px;
}
</style>
