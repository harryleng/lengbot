<template>
  <div v-if="isPlainText" class="plain-result">
    <pre>{{ displayText }}</pre>
  </div>
  <div v-else>
    <section v-for="(question, index) in questions" :key="question.questionId" class="ask-question">
      <div class="ask-question-title">
        <QuestionCircleOutlined />
        <span>{{ index + 1 }}. {{ question.question }}</span>
      </div>
      <div v-if="question.options.length" class="ask-options">
        <div
          v-for="option in question.options"
          :key="option"
          :class="['ask-option', { selected: isSelectedOption(question, option) }]"
        >
          <span>{{ option }}</span>
          <CheckCircleOutlined v-if="isSelectedOption(question, option)" />
        </div>
      </div>
      <div v-if="answerText(question)" class="ask-answer">
        <CheckCircleOutlined />
        <span>用户回答：{{ answerText(question) }}</span>
      </div>
    </section>
    <div v-if="!answered" class="ask-pending">
      <ClockCircleOutlined />
      <span>{{ isWorkflowMode ? '等待您在下方表单中回答…' : '等待用户回答...' }}</span>
    </div>
    <div v-if="!answered && !isWorkflowMode" class="ask-action">
      <button type="button" @click="handleAnswer">
        <QuestionCircleOutlined />
        回答
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, inject } from 'vue'
import { QuestionCircleOutlined, CheckCircleOutlined, ClockCircleOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  event: { type: Object, required: true },
  messageIndex: { type: Number, default: -1 },
})

const showAskUserModal = inject('showAskUserModal', null)
const isAskUserUnanswered = inject('isAskUserUnanswered', null)
const getAskUserAnswers = inject('getAskUserAnswers', null)
const isWorkflowMode = computed(() => props.messageIndex < 0)
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

const questions = computed(() => {
  const source =
    Array.isArray(data.value?.questions) && data.value.questions.length
      ? data.value.questions
      : [{ questionId: 'question_1', question: data.value?.question, options: data.value?.options || [] }]
  return source
    .map((item, index) => ({
      questionId: String(item?.questionId || `question_${index + 1}`),
      question: String(item?.question || '').trim(),
      options: Array.isArray(item?.options) ? item.options : [],
    }))
    .filter((item) => item.question)
})

const answers = computed(
  () => props.event.userAnswers || getAskUserAnswers?.(props.messageIndex) || data.value?.user_answer || null
)
const answered = computed(() => {
  if (answers.value) return true
  if (isWorkflowMode.value) return false
  return !isAskUserUnanswered || props.messageIndex < 0 || !isAskUserUnanswered(props.messageIndex)
})

function answerValue(question) {
  const value = answers.value
  if (value == null) return null
  if (typeof value === 'object' && !Array.isArray(value)) return value[question.questionId]
  return questions.value.length === 1 ? value : null
}

function answerText(question) {
  const value = answerValue(question)
  return Array.isArray(value) ? value.join('、') : value == null ? '' : String(value)
}

function isSelectedOption(question, option) {
  const value = answerValue(question)
  return Array.isArray(value) ? value.includes(option) : value === option
}

function handleAnswer() {
  if (showAskUserModal && props.messageIndex >= 0) showAskUserModal(props.messageIndex)
}
</script>

<style scoped>
.plain-result {
  margin: 0;
  padding: 8px 10px;
  color: #374151;
  background: #fafafa;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.plain-result pre {
  margin: 0;
  font: inherit;
}
.ask-question {
  margin-bottom: 10px;
  padding: 10px 12px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
}
.ask-question-title,
.ask-answer,
.ask-pending {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  line-height: 1.6;
}
.ask-question-title {
  color: #1e40af;
  font-weight: 500;
}
.ask-options {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}
.ask-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 9px;
  color: #1f2937;
  background: rgba(255, 255, 255, 0.68);
  border: 1px solid #93c5fd;
  border-radius: 6px;
  font-size: 12px;
}
.ask-option.selected {
  color: #166534;
  background: #f0fdf4;
  border-color: #86efac;
}
.ask-answer {
  margin-top: 8px;
  padding-top: 8px;
  color: #166534;
  border-top: 1px solid #bfdbfe;
}
.ask-pending {
  padding: 8px 12px;
  color: #92400e;
  background: #fefce8;
  border: 1px solid #fde68a;
  border-radius: 8px;
  font-size: 12px;
}
.ask-action {
  margin-top: 8px;
}
.ask-action button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  color: #fff;
  background: #0070f3;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}
</style>
