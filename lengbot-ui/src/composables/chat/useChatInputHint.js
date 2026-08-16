import { ref, computed, watch, nextTick, onUnmounted } from 'vue'

/**
 * 输入框下方：有历史消息时轮播 Agent 推荐问题，否则显示免责声明
 * @param {object} options
 * @param {import('vue').Ref<Array>} options.messages
 * @param {import('vue').Ref<Array>|import('vue').ComputedRef<Array>} options.currentRecommendedQuestions
 * @param {import('vue').Ref<string|null>} options.selectedAgentId
 * @param {import('vue').Ref<string>} options.input
 * @param {import('vue').Ref} options.inputRef
 */
export function useChatInputHint({ messages, currentRecommendedQuestions, selectedAgentId, input, inputRef }) {
  const inputHintQuestions = computed(() => currentRecommendedQuestions.value.filter((q) => q && String(q).trim()))
  const showInputQuestionCarousel = computed(() => messages.value.length > 0 && inputHintQuestions.value.length > 0)
  const showInputDisclaimer = computed(() => !showInputQuestionCarousel.value)
  const questionRotateIndex = ref(0)
  let questionRotateTimer = null

  function applyRecommendedQuestion(q) {
    if (!q) return
    input.value = q
    nextTick(() => inputRef.value?.focus())
  }

  function stopQuestionRotateTimer() {
    if (questionRotateTimer) {
      clearInterval(questionRotateTimer)
      questionRotateTimer = null
    }
  }

  function startQuestionRotateTimer() {
    stopQuestionRotateTimer()
    if (!showInputQuestionCarousel.value || inputHintQuestions.value.length <= 1) return
    questionRotateTimer = setInterval(() => {
      const len = inputHintQuestions.value.length
      if (len > 1) {
        questionRotateIndex.value = (questionRotateIndex.value + 1) % len
      }
    }, 5000)
  }

  watch(
    showInputQuestionCarousel,
    (show) => {
      questionRotateIndex.value = 0
      if (show) {
        startQuestionRotateTimer()
      } else {
        stopQuestionRotateTimer()
      }
    },
    { immediate: true }
  )

  watch(
    [selectedAgentId, inputHintQuestions],
    () => {
      questionRotateIndex.value = 0
      if (showInputQuestionCarousel.value) {
        startQuestionRotateTimer()
      }
    },
    { deep: true }
  )

  onUnmounted(() => {
    stopQuestionRotateTimer()
  })

  return {
    inputHintQuestions,
    showInputQuestionCarousel,
    showInputDisclaimer,
    questionRotateIndex,
    applyRecommendedQuestion,
    stopQuestionRotateTimer,
    startQuestionRotateTimer,
  }
}
