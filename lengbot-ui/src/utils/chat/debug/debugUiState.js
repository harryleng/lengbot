/** Debug Lab 消息 UI 态默认值 */
export const DEFAULT_DEBUG_UI_STATE = {
  streaming: false,
  reasoningExpanded: true,
  reasoningDone: true,
  toolsDone: true,
  toolExpanded: false,
  refsSectionExpanded: true,
}

/** UI 态各开关说明（供 Debug Lab tooltip 使用） */
export const DEBUG_UI_STATE_TOOLTIPS = {
  overview:
    '调节预览消息的 UI 展示状态（对应 Chat 页 parseMessage 后的 _streaming、_reasoningExpanded 等字段），用于模拟流式输出、思考链、工具完成等场景，不改变 JSON 中的 content/metadata。',
  streaming:
    '模拟 SSE 流式输出进行中：隐藏消息操作栏（复制/反馈等），正文与工具块显示进行中态，可配合 reasoningDone/toolsDone 调试加载动画。',
  reasoningExpanded:
    '控制深度思考面板（ChatReasoningPanel）是否展开；关闭时折叠 metadata.reasoningContent，仅保留标题栏。',
  reasoningDone: '思考链是否已输出完毕。streaming 为 true 且此项为 false 时，思考区标题旁显示 loading spinner。',
  toolsDone:
    '工具 / SubAgent / 能力块是否全部执行完毕。streaming 为 true 且此项为 false 时，工具组显示「执行中」动画。',
  toolExpanded: '工具调用组（ToolCallsGroup）是否默认展开，便于查看 tool_call / tool_result 详情与渲染组件。',
  refsSectionExpanded:
    '参考文献区（ChatRagReferences）是否默认展开；需 metadata.ragReferences 有数据时才会显示该区域。',
}

/** @param {object} state */
export function normalizeDebugUiState(state = {}) {
  return {
    streaming: !!state.streaming,
    reasoningExpanded: state.reasoningExpanded !== false,
    reasoningDone: state.reasoningDone !== false,
    toolsDone: state.toolsDone !== false,
    toolExpanded: !!state.toolExpanded,
    refsSectionExpanded: state.refsSectionExpanded !== false,
  }
}
