<template>
  <a-modal
    :open="open"
    title="AI 向您提问"
    :footer="null"
    :mask-closable="false"
    width="620px"
    @update:open="$emit('update:open', $event)"
  >
    <div class="ask-user-form">
      <section v-for="(question, index) in questions" :key="question.questionId" class="ask-question">
        <div class="question-title">
          <span>{{ index + 1 }}</span>
          {{ question.question }}
        </div>
        <div v-if="question.options?.length" class="option-list">
          <button
            v-for="option in question.options"
            :key="option"
            type="button"
            class="option-button"
            :class="{ selected: isSelected(question, option) }"
            @click="toggleOption(question, option)"
          >
            {{ option }}
          </button>
        </div>
        <a-textarea
          v-if="question.allowOther || !question.options?.length"
          v-model:value="otherAnswers[question.questionId]"
          :placeholder="question.options?.length ? '可补充其他回答' : '请输入您的回答'"
          :auto-size="{ minRows: 2, maxRows: 4 }"
        />
      </section>
      <div class="form-actions">
        <a-button type="primary" :disabled="!canSubmit" @click="submit">提交回答并继续</a-button>
      </div>
    </div>
  </a-modal>
</template>

<script setup>
import { computed, reactive, watch } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  questions: { type: Array, default: () => [] },
})

const emit = defineEmits(['update:open', 'submit'])
const selectedAnswers = reactive({})
const otherAnswers = reactive({})

watch(
  () => [props.open, props.questions],
  () => {
    if (!props.open) return
    Object.keys(selectedAnswers).forEach((key) => delete selectedAnswers[key])
    Object.keys(otherAnswers).forEach((key) => delete otherAnswers[key])
  },
  { deep: true }
)

function isSelected(question, option) {
  const value = selectedAnswers[question.questionId]
  return question.multiSelect ? Array.isArray(value) && value.includes(option) : value === option
}

function toggleOption(question, option) {
  const key = question.questionId
  if (question.multiSelect) {
    const values = Array.isArray(selectedAnswers[key]) ? [...selectedAnswers[key]] : []
    const index = values.indexOf(option)
    if (index >= 0) values.splice(index, 1)
    else values.push(option)
    selectedAnswers[key] = values
    return
  }
  selectedAnswers[key] = selectedAnswers[key] === option ? '' : option
}

function valueOf(question) {
  const selected = selectedAnswers[question.questionId]
  const other = String(otherAnswers[question.questionId] || '').trim()
  if (question.multiSelect) {
    const values = Array.isArray(selected) ? [...selected] : []
    if (other) values.push(other)
    return values
  }
  return other || selected || ''
}

const canSubmit = computed(
  () =>
    props.questions.length > 0 &&
    props.questions.every((question) => {
      const value = valueOf(question)
      return Array.isArray(value) ? value.length > 0 : Boolean(String(value).trim())
    })
)

function submit() {
  if (!canSubmit.value) return
  const answers = {}
  const lines = []
  props.questions.forEach((question) => {
    const value = valueOf(question)
    answers[question.questionId] = value
    lines.push(`- ${question.question}：${Array.isArray(value) ? value.join('、') : value}`)
  })
  emit('submit', { answers, text: `以下是对上一轮提问的回答：\n${lines.join('\n')}` })
}
</script>

<style scoped lang="less">
.ask-user-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.ask-question {
  padding: 12px;
  border: 1px solid var(--gray-100);
  border-radius: 10px;
  background: var(--gray-25);
}
.question-title {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 10px;
  color: var(--gray-800);
  font-weight: 500;
  line-height: 1.55;
}
.question-title span {
  display: inline-flex;
  flex: 0 0 20px;
  justify-content: center;
  align-items: center;
  height: 20px;
  border-radius: 50%;
  background: var(--main-600);
  color: #fff;
  font-size: 12px;
}
.option-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.option-button {
  border: 1px solid var(--gray-200);
  border-radius: 6px;
  padding: 6px 10px;
  background: #fff;
  color: var(--gray-600);
  cursor: pointer;
}
.option-button.selected {
  border-color: var(--main-500);
  background: var(--main-50);
  color: var(--main-700);
}
.form-actions {
  display: flex;
  justify-content: flex-end;
}
</style>
