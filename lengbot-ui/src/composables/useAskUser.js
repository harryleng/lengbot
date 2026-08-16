import { reactive, provide } from 'vue'

/** Manages AskUser prompts while keeping legacy single-question events readable. */
export function useAskUser({ messages }) {
  const askUserModal = reactive({
    visible: false,
    questions: [],
    messageIndex: -1,
  })

  function findAskUserEvent(msg) {
    if (!msg?._toolEvents?.length) return null
    for (let i = msg._toolEvents.length - 1; i >= 0; i--) {
      const evt = msg._toolEvents[i]
      if (evt.type === 'tool_result' && evt.toolName === 'ask_user') {
        try {
          const parsed = JSON.parse(evt.result)
          if (parsed && typeof parsed === 'object' && (parsed.question || parsed.questions)) return parsed
        } catch {
          /* ignore malformed historic events */
        }
      }
    }
    return null
  }

  function normalizeQuestions(payload) {
    const source =
      Array.isArray(payload?.questions) && payload.questions.length > 0
        ? payload.questions
        : [
            {
              questionId: 'question_1',
              question: payload?.question,
              options: payload?.options || [],
              multiSelect: false,
              allowOther: true,
            },
          ]
    return source
      .slice(0, 5)
      .map((item, index) => ({
        questionId: String(item.questionId || `question_${index + 1}`),
        question: String(item.question || '').trim(),
        options: Array.isArray(item.options) ? item.options : [],
        multiSelect: item.multiSelect === true,
        allowOther: item.allowOther !== false,
      }))
      .filter((item) => item.question)
  }

  function isAskUserUnanswered(msgIndex) {
    const msg = messages.value[msgIndex]
    if (!msg || msg.role !== 'assistant' || !findAskUserEvent(msg)) return false
    for (let i = msgIndex + 1; i < messages.value.length; i++) {
      if (messages.value[i].role === 'user') return false
    }
    return true
  }

  function showAskUserModal(msgIndex) {
    const askData = findAskUserEvent(messages.value[msgIndex])
    const questions = normalizeQuestions(askData)
    if (!askData || questions.length === 0) return
    askUserModal.questions = questions
    askUserModal.messageIndex = msgIndex
    askUserModal.visible = true
  }

  function recordAskUserAnswers(messageIndex, answers) {
    const msg = messages.value[messageIndex]
    if (!msg?._toolEvents?.length) return
    for (let i = msg._toolEvents.length - 1; i >= 0; i--) {
      const event = msg._toolEvents[i]
      if (event.type === 'tool_result' && event.toolName === 'ask_user') {
        event.userAnswers = { ...answers }
        return
      }
    }
  }

  function getAskUserAnswers(messageIndex) {
    const askData = findAskUserEvent(messages.value[messageIndex])
    const questions = normalizeQuestions(askData)
    if (!questions.length) return null

    for (let i = messageIndex + 1; i < messages.value.length; i++) {
      const msg = messages.value[i]
      if (msg?.role !== 'user') continue
      const content = String(msg.content || '')
      const answers = {}
      questions.forEach((question) => {
        const line = content.split('\n').find((item) => item.startsWith(`- ${question.question}：`))
        if (!line) return
        const value = line.substring(`- ${question.question}：`.length).trim()
        answers[question.questionId] = question.multiSelect ? value.split('、').filter(Boolean) : value
      })
      if (Object.keys(answers).length === questions.length) return answers
      return null
    }
    return null
  }

  provide('showAskUserModal', showAskUserModal)
  provide('isAskUserUnanswered', isAskUserUnanswered)
  provide('getAskUserAnswers', getAskUserAnswers)

  return {
    askUserModal,
    findAskUserEvent,
    isAskUserUnanswered,
    showAskUserModal,
    recordAskUserAnswers,
    getAskUserAnswers,
  }
}
