package com.lengbot.service.chat;

import com.lengbot.dto.ChatRequestDTO;
import com.lengbot.dto.LlmTraceSpanDTO;
import com.lengbot.entity.Agent;
import com.lengbot.entity.ToolCall;
import com.lengbot.util.SensitiveWordFilter;
import lombok.Data;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.ToolBase;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 对话管道上下文，承载整个中间件链的共享状态
 *
 * @author lw
 * @since 2026-05-23
 */
@Data
public class ChatContext {

    // ===== 输入 =====
    private ChatRequestDTO request;

    // ===== InitMiddleware 解析 =====
    private Long userId;
    private Long sessionId;
    private Agent agent;
    private Map<String, Object> configMap;
    private Long providerId;

    // ===== 版本快照绑定 ID（configVersion 或已发布版本快照中的绑定，优先级高于 agent 表当前值） =====
    private List<Long> versionToolIds;
    private List<Long> versionKnowledgeIds;
    private List<Long> versionMcpServerIds;
    private List<Long> versionSubAgentIds;
    private List<Long> versionSkillIds;

    // ===== MentionMiddleware 构建 =====
    /** 本轮 @ 提及的资源范围（null 表示无 mention） */
    private MentionScope mentionScope;

    // ===== MessageMiddleware 构建 =====
    private List<io.agentscope.core.message.Msg> messages;
    /** 与 messages 中 UserMessage 顺序对齐的 mention 快照，供 Trace 历史消息回显 */
    private List<List<Map<String, Object>>> traceUserMentionsPerMessage;

    // ===== SkillPrepMiddleware 准备 =====
    /** 由 Skill 拼接出来的额外系统提示词（追加到 Agent.systemPrompt 之后） */
    private String skillSystemAppendix;
    /** 由 Skill 引入的额外 Tool ID（合并入 ToolPrep 的工具集合） */
    private List<Long> skillExtraToolIds;
    /** 由 Skill 引入的额外 MCP Server ID */
    private List<Long> skillExtraMcpServerIds;
    /** 启用的 Skill 名称列表（用于 trace 与日志） */
    private List<String> activeSkillNames;
    /** 启用的 Skill 详情（用于前端 skill_active 事件展示） */
    private List<Map<String, Object>> activeSkillDetails;
    /** toolName → Skill 详情映射（用于工具调用时按需推送 skill_active 事件） */
    private Map<String, Map<String, Object>> toolNameToSkillDetail;
    /** 已激活的 Skill slug 集合（跨轮次持久化在 Redis 中，每请求加载） */
    private Set<String> activatedSkills;

    // ===== SubAgentPrepMiddleware 准备 =====
    /** 当前 Agent 绑定的可委派 SubAgent ID 列表 */
    private List<Long> boundSubAgentIds;

    // ===== ToolPrepMiddleware 准备 =====
    private Model chatModel;
    private GenerateOptions toolOptions;
    private Map<String, ToolBase> toolCallbackMap;
    /** toolName → displayName 映射（前端展示用） */
    private Map<String, String> toolDisplayNameMap;
    /** toolName → icon 映射（前端头像图标用，Ant Design 图标组件名） */
    private Map<String, String> toolIconMap;
    /** MCP toolName to MCP Server icon for this request. */
    private Map<String, String> mcpToolIconMap;
    /** Effective MCP callback names after callback de-duplication. */
    private Set<String> mcpToolNames;

    // ===== 消息ID =====
    /** 用户消息ID（MessageMiddleware 保存后写入） */
    private Long userMessageId;
    /** 助手消息ID（buildDoneEvent 保存后写入） */
    private Long assistantMessageId;
    /** 用户消息的父消息ID（ask_user 触发时指向触发该问题的助手消息） */
    private Long userMessageParentId;

    // ===== 流式累加器 =====
    private String requestId;
    private StringBuilder fullReply;
    private StringBuilder reasoningContent;
    private String[] ragMetadataHolder;
    private int[] toolCallCountHolder;
    private int[] inputTokenHolder;
    private int[] outputTokenHolder;
    private List<Map<String, Object>> toolEventsList;
    /** 工作流节点执行事件（WORKFLOW 类型 Agent） */
    private List<Map<String, Object>> workflowEventsList;
    private List<LlmTraceSpanDTO> spans;
    private long startTime;

    /** 流式模型调用是否最终失败 */
    private boolean streamFailed;
    /** 流式模型调用最终失败提示 */
    private String streamErrorMessage;
    /** 流式模型调用最终失败错误码 */
    private String streamErrorCode;
    /**
     * 用户输入敏感词拦截已落库标记
     * <p>UserSensitiveMiddleware 命中拦截后会同步落 USER + ASSISTANT 两条消息，
     * 置 true 让 buildDoneEvent 跳过二次落库与标题/记忆抽取等后置流程</p>
     */
    private boolean sensitiveUserBlocked;

    /**
     * AI 输出敏感词拦截已触发标记
     * <p>流式过程中首次命中敏感词后置 true，后续 chunk 全部跳过处理（不再追加 reasoning/fullReply、不再重复发 sensitive_block），
     * buildPersistMetadata 据此跳过 reasoningContent 暴露</p>
     */
    private boolean sensitiveAiBlocked;

    /**
     * 本轮请求内存级 todos 累加快照
     * <p>初始值由 ToolPrepMiddleware.prepare 从数据库加载；
     * 每次 write_todos 工具执行成功后由 ChatServiceImpl.executeToolCallback 回写合并结果，
     * 保证同一轮内多次 write_todos 调用都能拿到上一轮的最新合并基准，避免覆盖式丢项</p>
     */
    private List<Map<String, String>> currentTodosSnapshot;

    private volatile boolean aborted;
    private volatile String abortReason;

    /** 流式敏感词过滤状态（按累积全文过滤，避免分片漏拦） */
    private SensitiveWordFilter.StreamState sensitiveStreamState;

    /**
     * 流式开始前累积的状态事件（context_compression 等）
     * <p>由 MessageMiddleware.buildMessages 在摘要等阶段写入；
     * execute 在调用 next.proceed 前取出并 prepend 到主 Flux，确保事件在 LLM 输出前到达前端</p>
     */
    private List<String> preStreamStatusEvents = new ArrayList<>();

    /** 流式 inline thinking 标签解析（Ollama 等模型） */
    com.lengbot.util.InlineThinkingStreamParser inlineThinkingParser;

    /** 流式 getText() 快照，用于从累积文本中提取增量 */
    private String streamTextSnapshot = "";

    /** 模型原始流式输出（未剥离标签），入库前兜底解析用（每轮 LLM 调用重置） */
    private StringBuilder rawLlmStreamText = new StringBuilder();

    /** Trace 用：整轮对话模型原始输出累积（含 thinking 标签，不重置、不删改） */
    private StringBuilder traceCompleteReply = new StringBuilder();

    /** Trace 用：metadata 通道的原始 reasoning 累积（MiMo 等，与正文分通道时不合并删改） */
    private StringBuilder traceMetadataReasoning = new StringBuilder();

    /** 流式已解析并推送的 reasoning 快照（raw 重 parse diff 用） */
    private String streamReasoningParsedSnapshot = "";

    /** 流式已解析并推送的 content 快照（raw 重 parse diff 用） */
    private String streamContentParsedSnapshot = "";

    /** inline thinking 是否已完成最终解析（Trace 与入库共用，避免重复解析） */
    private boolean inlineThinkingFinalized;

    /** 待持久化的工具调用记录（assistant 消息保存后批量写入，关联 messageId） */
    private List<ToolCall> pendingToolCalls;

    // ===== 子代理流式事件队列 =====
    private Queue<SubAgentEvent> subAgentEventQueue;

    /** 当前 SubAgent 委派对应的 contentOffset（主 Agent 回复中的插入位置） */
    private Integer subAgentContentOffset;

    /** 当前 SubAgent 委派对应的正文前缀锚点，入库/前端据此精确切分正文，避免截断 */
    private String subAgentContentPrefixAnchor;

    /** 当前 assistant 消息内 SubAgent 委派序号（同 offset 多次委派时递增） */
    private Integer subAgentDelegationIndex;

    /** 当前子任务所属批次，用于并行 SSE 路由。 */
    private String subAgentBatchId;

    /** 当前子任务 ID，用于并行 SSE 路由。 */
    private String subAgentTaskId;

    /** 当前子任务在批次中的序号。 */
    private Integer subAgentTaskIndex;

    /**
     * 分配 SubAgent 委派序号：首次 0，同条消息内再次委派递增。
     *
     * @return 委派序号
     */
    public int assignSubAgentDelegationIndex() {
        if (subAgentDelegationIndex == null) {
            subAgentDelegationIndex = 0;
        } else {
            subAgentDelegationIndex++;
        }
        return subAgentDelegationIndex;
    }

    /** 流式模式下实时推送 [STATUS] JSON 到 SSE（由 ChatServiceImpl 注入） */
    private java.util.function.Consumer<String> realtimeStatusEmitter;

    /**
     * LLM 流式 tool_call args 累积器（按 toolCallId 维度）
     * <p>用于 write_file 等大段 arguments 生成期间节流推送字数进度；流结束须清理。</p>
     */
    private final Map<String, ToolCallAccumulator> toolArgsAccumulators = new ConcurrentHashMap<>();

    /**
     * 实时推送结构化状态事件 JSON（不含 [STATUS] 前缀）
     *
     * @param statusJson 状态事件 JSON
     */
    public void emitRealtimeStatus(String statusJson) {
        if (realtimeStatusEmitter != null && statusJson != null && !statusJson.isBlank()) {
            realtimeStatusEmitter.accept(statusJson);
        }
    }

    public void requestAbort(String reason) {
        this.aborted = true;
        this.abortReason = reason;
    }

    /**
     * 子代理流式事件（token/tool_call/tool_result）
     */
    public record SubAgentEvent(String type, String subagentName, String content, int contentOffset) {}

    /**
     * 推送子代理事件到队列
     */
    public void pushSubAgentEvent(SubAgentEvent event) {
        if (subAgentEventQueue != null && event != null) {
            subAgentEventQueue.add(event);
        }
    }

    /**
     * 取出并清空所有待消费的子代理事件
     */
    public List<SubAgentEvent> drainSubAgentEvents() {
        if (subAgentEventQueue == null) {
            return List.of();
        }
        List<SubAgentEvent> events = new ArrayList<>();
        SubAgentEvent event;
        while ((event = subAgentEventQueue.poll()) != null) {
            events.add(event);
        }
        return events;
    }

    /**
     * 追加思考内容（入库前清理 NUL 等 PostgreSQL 非法字符）
     *
     * @param chunk 思考片段
     * @return 实际追加的清理后文本，未追加则返回空串
     */
    public String appendReasoningContent(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        String cleaned = com.lengbot.util.TextNormalizeUtil.sanitizeForDatabase(chunk);
        if (cleaned.isEmpty()) {
            return "";
        }
        reasoningContent.append(cleaned);
        return cleaned;
    }

    /**
     * 基于当前 raw 全文重 parse，与上次快照 diff 得到本次 SSE 应推送的 reasoning/content 增量。
     * <p>Trace 完整回复已验证正确；流式展示与入库均以此为准，避免增量 feed 状态机与全文 parse 不一致。</p>
     */
    public com.lengbot.util.InlineThinkingStreamParser.ParseResult computeInlineThinkingStreamDelta() {
        String raw = getRawLlmStreamText().toString();
        if (raw.isEmpty()) {
            return com.lengbot.util.InlineThinkingStreamParser.ParseResult.empty();
        }
        com.lengbot.util.InlineThinkingStreamParser.ParseResult full =
                com.lengbot.util.InlineThinkingStreamParser.parseCompleteRaw(raw);
        String reasoningDelta = suffixDelta(streamReasoningParsedSnapshot, full.reasoningDelta());
        String contentDelta = suffixDelta(streamContentParsedSnapshot, full.contentDelta());
        streamReasoningParsedSnapshot = full.reasoningDelta();
        streamContentParsedSnapshot = full.contentDelta();
        return new com.lengbot.util.InlineThinkingStreamParser.ParseResult(reasoningDelta, contentDelta);
    }

    private static String suffixDelta(String previous, String current) {
        if (current == null || current.isEmpty()) {
            return "";
        }
        if (previous == null || previous.isEmpty()) {
            return current;
        }
        if (current.equals(previous)) {
            return "";
        }
        if (current.startsWith(previous)) {
            return current.substring(previous.length());
        }
        // 闭标签 trim 等导致 current 比 previous 短：勿把整段 reasoning 再推一次 SSE
        if (previous.startsWith(current)) {
            return "";
        }
        int prefixLen = longestCommonPrefixLength(previous, current);
        if (prefixLen >= previous.length()) {
            return current.substring(prefixLen);
        }
        return current.substring(prefixLen);
    }

    private static int longestCommonPrefixLength(String a, String b) {
        int limit = Math.min(a.length(), b.length());
        int i = 0;
        while (i < limit && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /**
     * 流式结束后最终解析 thinking 标签并规范化 reasoning / 正文（Trace 与入库前调用）。
     * <p>以 traceCompleteReply 全文 parse 为准（与 Trace AI 完整回复同源），不再信任增量 feed 累加结果。</p>
     */
    public void finalizeInlineThinking() {
        if (inlineThinkingFinalized) {
            return;
        }
        // 敏感拦截场景：fullReply 已被改写为拦截文案，禁止用 rawLlmStreamText 重新 parse 覆盖（会还原出敏感原文），
        // 也不再用 traceCompleteReply 推导 reasoning（拦截后 reasoning 不应暴露给前端/入库）
        if (sensitiveAiBlocked) {
            inlineThinkingFinalized = true;
            return;
        }

        String rawText = traceCompleteReply != null ? traceCompleteReply.toString() : "";
        String metaReasoning = traceMetadataReasoning != null ? traceMetadataReasoning.toString() : "";

        if (com.lengbot.util.InlineThinkingStreamParser.containsThinkingTags(rawText)) {
            com.lengbot.util.InlineThinkingStreamParser.ParseResult parsed =
                    com.lengbot.util.InlineThinkingStreamParser.parseComplete(rawText);
            reasoningContent.setLength(0);
            fullReply.setLength(0);
            if (!metaReasoning.isEmpty()) {
                appendReasoningContent(metaReasoning);
            }
            if (!parsed.reasoningDelta().isEmpty()) {
                appendReasoningContent(parsed.reasoningDelta());
            }
            fullReply.append(parsed.contentDelta());
        } else if (!metaReasoning.isEmpty()) {
            reasoningContent.setLength(0);
            appendReasoningContent(metaReasoning);
        } else if (!rawText.isEmpty() && fullReply.isEmpty()) {
            fullReply.append(rawText);
        }

        if (!reasoningContent.isEmpty()) {
            String normalized = com.lengbot.util.InlineThinkingStreamParser.normalizeReasoningText(
                    reasoningContent.toString());
            reasoningContent.setLength(0);
            reasoningContent.append(normalized);
        }
        if (!fullReply.isEmpty()) {
            String normalized = com.lengbot.util.InlineThinkingStreamParser.normalizeContentText(
                    fullReply.toString());
            fullReply.setLength(0);
            fullReply.append(normalized);
        }
        inlineThinkingFinalized = true;
    }

    /**
     * 工厂方法：创建上下文并初始化所有累加器
     */
    public static ChatContext of(ChatRequestDTO request) {
        ChatContext ctx = new ChatContext();
        ctx.request = request;
        ctx.fullReply = new StringBuilder();
        ctx.reasoningContent = new StringBuilder();
        ctx.ragMetadataHolder = new String[]{null};
        ctx.toolCallCountHolder = new int[]{0};
        ctx.inputTokenHolder = new int[]{0};
        ctx.outputTokenHolder = new int[]{0};
        ctx.toolEventsList = new ArrayList<>();
        ctx.workflowEventsList = new ArrayList<>();
        ctx.spans = new ArrayList<>();
        ctx.pendingToolCalls = new ArrayList<>();
        ctx.activatedSkills = new LinkedHashSet<>();
        ctx.subAgentEventQueue = new ConcurrentLinkedQueue<>();
        ctx.inlineThinkingParser = new com.lengbot.util.InlineThinkingStreamParser();
        ctx.rawLlmStreamText = new StringBuilder();
        ctx.traceCompleteReply = new StringBuilder();
        ctx.traceMetadataReasoning = new StringBuilder();
        return ctx;
    }

    /**
     * 追加 Trace 完整回复（模型原始输出，不做标签剥离或换行规范化）。
     */
    public void appendTraceCompleteReply(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (traceCompleteReply == null) {
            traceCompleteReply = new StringBuilder();
        }
        traceCompleteReply.append(delta);
    }

    /**
     * 追加 Trace 用 metadata reasoning 原始片段（MiMo 等分通道 reasoning）。
     */
    public void appendTraceMetadataReasoning(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        if (traceMetadataReasoning == null) {
            traceMetadataReasoning = new StringBuilder();
        }
        traceMetadataReasoning.append(chunk);
    }

    /**
     * 构建 Trace 的 AI 完整回复：优先流式原文（含 thinking 标签）；metadata reasoning 与正文分通道时按序拼接。
     */
    public String buildTraceCompleteReply() {
        String rawText = traceCompleteReply != null ? traceCompleteReply.toString() : "";
        String metaReasoning = traceMetadataReasoning != null ? traceMetadataReasoning.toString() : "";
        if (!metaReasoning.isEmpty() && !rawText.isEmpty()) {
            return metaReasoning + rawText;
        }
        if (!rawText.isEmpty()) {
            return rawText;
        }
        return metaReasoning;
    }

    /**
     * 获取流式 inline thinking 标签解析器（Ollama 等模型）
     */
    public com.lengbot.util.InlineThinkingStreamParser getInlineThinkingParser() {
        return inlineThinkingParser;
    }

    /**
     * 新一轮 LLM 流式调用前重置文本快照与 inline thinking 解析器。
     */
    public void resetStreamTextTracking() {
        streamTextSnapshot = "";
        streamReasoningParsedSnapshot = "";
        streamContentParsedSnapshot = "";
        if (rawLlmStreamText == null) {
            rawLlmStreamText = new StringBuilder();
        } else {
            rawLlmStreamText.setLength(0);
        }
    }

    /**
     * 追加模型原始流式文本（含 thinking 标签，供入库前兜底解析）。
     */
    public void appendRawLlmStreamText(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (rawLlmStreamText == null) {
            rawLlmStreamText = new StringBuilder();
        }
        rawLlmStreamText.append(delta);
        appendTraceCompleteReply(delta);
    }

    /**
     * 获取模型原始流式输出累积
     */
    public StringBuilder getRawLlmStreamText() {
        if (rawLlmStreamText == null) {
            rawLlmStreamText = new StringBuilder();
        }
        return rawLlmStreamText;
    }

    /**
     * 从流式 getText() 提取增量（兼容累积全文与纯增量两种模式，忽略重复/回退 chunk）。
     *
     * @param currentText 当前 chunk 的 getText() 返回值
     * @return 相对已消费文本的新增片段
     */
    public String consumeStreamTextDelta(String currentText) {
        if (currentText == null || currentText.isEmpty()) {
            return "";
        }
        // 1. 累积模式：currentText 在 snapshot 基础上延长
        if (!streamTextSnapshot.isEmpty()
                && currentText.startsWith(streamTextSnapshot)
                && currentText.length() > streamTextSnapshot.length()) {
            String delta = currentText.substring(streamTextSnapshot.length());
            streamTextSnapshot = currentText;
            return delta;
        }
        // 2. 完全重复
        if (currentText.equals(streamTextSnapshot)) {
            return "";
        }
        // 3. 累积回退 / 更短前缀重发（如先收到 "Okay, the" 再收到 "Okay"）
        if (!streamTextSnapshot.isEmpty()
                && currentText.length() <= streamTextSnapshot.length()
                && streamTextSnapshot.startsWith(currentText)) {
            return "";
        }
        // 4. 纯增量重复 chunk（snapshot 已以 currentText 结尾）
        if (!streamTextSnapshot.isEmpty()
                && currentText.length() <= streamTextSnapshot.length()
                && streamTextSnapshot.endsWith(currentText)) {
            return "";
        }
        // 5. 纯增量追加
        streamTextSnapshot = streamTextSnapshot + currentText;
        return currentText;
    }
}
