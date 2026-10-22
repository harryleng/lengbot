package com.lengbot.service.chat;

import com.lengbot.constant.ConfigKeys;
import com.lengbot.entity.ChatSessionSummary;
import com.lengbot.entity.Message;
import com.lengbot.enums.MessageRole;
import com.lengbot.mapper.ChatSessionSummaryMapper;
import com.lengbot.model.ModelFactory;
import com.lengbot.model.ProviderResolver;
import com.lengbot.util.LlmTraceContext;
import com.lengbot.util.ModelCalls;
import com.lengbot.util.Msgs;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话上下文摘要服务
 *
 * <p><b>为什么要独立成服务：</b>原先的摘要逻辑内联在 {@code MessageMiddleware.summarizeIfNeeded} 里，
 * 生成结果只存在内存 List 中、不落库，导致长会话体积一旦超过阈值就会<b>每轮</b>同步调用一次 LLM
 * 重新生成摘要 —— 这段耗时完全压在首字延迟上（且走 {@code callWithoutTrace}，trace 里还看不到）。
 * 本服务把摘要结果落库复用，把「每轮一次同步调用」降级为「首次一次 + 之后后台刷新」。</p>
 *
 * <p><b>复用判定：</b>缓存记录 {@code upto_message_id}（摘要覆盖到哪条消息）。
 * 下一轮只要该消息仍在窗口内、且其后的新消息体积未再次超过阈值，就直接复用旧摘要、
 * 把新消息接在后面 —— 稳态下首字前<b>零 LLM 调用</b>。
 * 只有累积的新消息再次超阈值时才重新生成，而此时有旧摘要兜底，可丢到后台线程异步刷新。</p>
 *
 * @author lw
 * @since 2026-10-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextSummaryService {

    private final ChatSessionSummaryMapper summaryMapper;
    private final ModelFactory modelFactory;
    private final ProviderResolver providerResolver;
    private final ToolEventGenerator toolEventGenerator;

    @Qualifier("lengBotExecutor")
    private final ThreadPoolTaskExecutor lengBotExecutor;

    /** 同一会话的并发刷新去重（单实例语义）：避免多路请求重复触发摘要调用 */
    private static final Set<Long> REFRESHING = ConcurrentHashMap.newKeySet();

    /**
     * 按需压缩上下文：优先复用缓存摘要，未命中且无旧摘要可用时才同步调用 LLM。
     *
     * @param sessionId 会话ID
     * @param history   历史消息（正序，已排除当前轮用户消息）
     * @param configMap Agent 运行时配置
     * @param ctx       对话上下文（用于推送 SSE 占位事件，可空）
     * @return 组装后的历史消息列表
     */
    public List<Message> summarizeIfNeeded(Long sessionId, List<Message> history,
                                           Map<String, Object> configMap, ChatContext ctx) {
        if (history == null || history.isEmpty() || configMap == null) {
            return history;
        }
        if (!Boolean.TRUE.equals(configMap.get(ConfigKeys.Agent.ENABLE_SUMMARY))) {
            return history;
        }

        double thresholdKb = resolveThresholdKb(configMap);
        int keepRecent = resolveKeepRecent(configMap);

        long totalChars = history.stream().mapToLong(this::charsOf).sum();
        double totalKb = totalChars / 1024.0;
        if (totalKb <= thresholdKb) {
            return history;
        }

        // 体积已超阈值：keepRecent 按实际条数收敛，保证「被压缩部分」至少 2 条。
        // 原判定 `history.size() > keepRecent + 2` 是按聊天轮次设计的，会漏掉
        // 「条数少但单条极胖」的工具型会话 —— 实测 6 条 / 203KB 的会话被它拦下，
        // 结果是 203KB 每轮原样进上下文，既不压缩也不摘要。压缩的真实需求是体积，不是轮次。
        int effectiveKeep = effectiveKeepRecent(keepRecent, history.size());
        if (effectiveKeep < 1) {
            return history;
        }

        ChatSessionSummary cache = loadCache(sessionId);
        if (cache != null && cache.getSummary() != null && !cache.getSummary().isBlank()) {
            int idx = indexOfMessageId(history, cache.getUptoMessageId());
            List<Message> tail = idx >= 0
                    ? new ArrayList<>(history.subList(idx + 1, history.size()))
                    : tailByKeepRecent(history, effectiveKeep);

            double tailKb = tail.stream().mapToLong(this::charsOf).sum() / 1024.0;
            if (idx >= 0 && tailKb <= thresholdKb) {
                // 命中缓存：稳态路径，首字前零 LLM 调用
                log.debug("[Chat][Summary] 复用缓存摘要: sessionId={}, upto={}, 后续{}条({}KB)",
                        sessionId, cache.getUptoMessageId(), tail.size(), String.format("%.1f", tailKb));
                return compose(cache.getSummary(), tail);
            }

            // 缓存已过时：用旧摘要顶住本轮，后台线程重新生成，用户无感
            log.info("[Chat][Summary] 缓存摘要已过时，异步刷新: sessionId={}, 后续{}KB / 阈值{}KB",
                    sessionId, String.format("%.1f", tailKb), thresholdKb);
            refreshAsync(sessionId, new ArrayList<>(history), configMap);
            return compose(cache.getSummary(), tail);
        }

        // 无缓存（首次）：只能同步生成一次，保留占位事件提示前端
        return generateSync(sessionId, history, configMap, ctx, effectiveKeep, totalKb);
    }

    // ------------------------------------------------------------------
    // 同步生成（仅首次触发）
    // ------------------------------------------------------------------

    private List<Message> generateSync(Long sessionId, List<Message> history, Map<String, Object> configMap,
                                      ChatContext ctx, int keepRecent, double totalKb) {
        emit(ctx, toolEventGenerator.contextCompressionEvent("started",
                "压缩 " + history.size() + " 条 / " + String.format("%.1f", totalKb) + "KB"));
        try {
            List<Message> older = new ArrayList<>(history.subList(0, history.size() - keepRecent));
            String summary = callSummaryModel(older, configMap);
            if (summary == null || summary.isBlank()) {
                emit(ctx, toolEventGenerator.contextCompressionEvent("failed", "摘要内容为空，使用原始上下文"));
                return history;
            }
            saveCache(sessionId, older, summary, configMap);

            List<Message> result = compose(summary, tailByKeepRecent(history, keepRecent));
            log.info("[Chat][Summary] 首次摘要完成: 原始{}条({}KB) → 摘要+最近{}条",
                    history.size(), String.format("%.1f", totalKb), keepRecent);
            emit(ctx, toolEventGenerator.contextCompressionEvent("completed",
                    "压缩完成，保留 " + result.size() + " 条"));
            return result;
        } catch (Exception e) {
            log.warn("[Chat][Summary] 摘要生成失败，使用原始上下文: {}", e.getMessage());
            emit(ctx, toolEventGenerator.contextCompressionEvent("failed", "摘要生成失败：" + e.getMessage()));
            return history;
        }
    }

    // ------------------------------------------------------------------
    // 异步刷新
    // ------------------------------------------------------------------

    /**
     * 后台重新生成摘要。已有旧摘要兜底，故失败也不影响本轮对话。
     *
     * @param historySnapshot 调用时刻的历史快照（均为已落库数据，异步线程读安全）
     */
    public void refreshAsync(Long sessionId, List<Message> historySnapshot, Map<String, Object> configMap) {
        if (historySnapshot == null || historySnapshot.isEmpty()) {
            return;
        }
        if (!REFRESHING.add(sessionId)) {
            return;
        }
        try {
            lengBotExecutor.execute(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    int keepRecent = resolveKeepRecent(configMap);
                    int effectiveKeep = effectiveKeepRecent(keepRecent, historySnapshot.size());
                    if (effectiveKeep < 1) {
                        return;
                    }
                    List<Message> older = new ArrayList<>(
                            historySnapshot.subList(0, historySnapshot.size() - effectiveKeep));
                    String summary = callSummaryModel(older, configMap);
                    if (summary != null && !summary.isBlank()) {
                        saveCache(sessionId, older, summary, configMap);
                        log.info("[Chat][Summary] 异步刷新完成: sessionId={}, 覆盖{}条, 耗时{}ms",
                                sessionId, older.size(), System.currentTimeMillis() - t0);
                    }
                } catch (Exception e) {
                    log.warn("[Chat][Summary] 异步刷新失败: sessionId={}, {}", sessionId, e.getMessage());
                } finally {
                    REFRESHING.remove(sessionId);
                }
            });
        } catch (Exception e) {
            REFRESHING.remove(sessionId);
            log.warn("[Chat][Summary] 异步刷新提交失败: sessionId={}, {}", sessionId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 缓存读写
    // ------------------------------------------------------------------

    private ChatSessionSummary loadCache(Long sessionId) {
        try {
            return summaryMapper.selectBySessionId(sessionId);
        } catch (Exception e) {
            log.warn("[Chat][Summary] 读取摘要缓存失败: sessionId={}, {}", sessionId, e.getMessage());
            return null;
        }
    }

    private void saveCache(Long sessionId, List<Message> older, String summary, Map<String, Object> configMap) {
        try {
            if (older.isEmpty()) {
                return;
            }
            ChatSessionSummary entity = new ChatSessionSummary();
            entity.setSessionId(sessionId);
            entity.setUptoMessageId(older.get(older.size() - 1).getId());
            entity.setCoveredCount(older.size());
            entity.setCoveredChars((int) older.stream().mapToLong(this::charsOf).sum());
            entity.setSummary(summary);
            entity.setModel(resolveModelName(configMap));
            summaryMapper.upsert(entity);
        } catch (Exception e) {
            // 落库失败不影响本轮：最坏情况是下一轮重新生成一次
            log.warn("[Chat][Summary] 摘要缓存落库失败: sessionId={}, {}", sessionId, e.getMessage());
        }
    }

    /** 删除会话摘要缓存（删除会话 / 清空历史时调用） */
    public void evict(Long sessionId) {
        try {
            summaryMapper.deleteBySessionId(sessionId);
        } catch (Exception e) {
            log.warn("[Chat][Summary] 清除摘要缓存失败: sessionId={}, {}", sessionId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 模型调用
    // ------------------------------------------------------------------

    private String callSummaryModel(List<Message> older, Map<String, Object> configMap) {
        Long providerId = providerResolver.resolveFromConfig(configMap);
        Model model = modelFactory.getModel(providerId);

        int toolResultCharLimit = resolveToolResultCharLimit(configMap);
        StringBuilder conversationText = new StringBuilder();
        for (Message msg : older) {
            String role = msg.getRole() == MessageRole.USER ? "用户" : "助手";
            String content = msg.getContent() != null ? msg.getContent() : "";
            if (msg.getRole() == MessageRole.TOOL && content.length() > toolResultCharLimit) {
                content = content.substring(0, toolResultCharLimit) + "\n[已截断，共" + content.length() + "字符]";
            }
            conversationText.append(role).append("：").append(content).append("\n");
        }

        String summaryPromptText = "你是一个对话摘要助手。请将以下对话内容压缩为简明摘要，保留关键信息、决策和上下文要点。只输出摘要，不要添加额外说明。";
        Object customPrompt = configMap.get(ConfigKeys.Agent.SUMMARY_PROMPT);
        if (customPrompt instanceof String cp && !cp.isBlank()) {
            summaryPromptText = cp;
        }

        List<Msg> summaryMessages = new ArrayList<>();
        summaryMessages.add(Msgs.system(summaryPromptText));
        summaryMessages.add(Msgs.user("请对以下对话进行摘要：\n\n" + conversationText));

        ChatResponse response = LlmTraceContext.callWithoutTrace(() -> ModelCalls.call(model, summaryMessages));
        return Msgs.extractText(response).trim();
    }

    private String resolveModelName(Map<String, Object> configMap) {
        try {
            Model model = modelFactory.getModel(providerResolver.resolveFromConfig(configMap));
            return model != null ? String.valueOf(model) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 体积统计须覆盖 content + metadata + tool_events 三个落库字段，否则大型工具结果会被漏算 */
    private long charsOf(Message m) {
        long len = 0;
        if (m.getContent() != null) {
            len += m.getContent().length();
        }
        if (m.getMetadata() != null) {
            len += m.getMetadata().length();
        }
        if (m.getToolEvents() != null) {
            len += m.getToolEvents().length();
        }
        return len;
    }

    private List<Message> compose(String summary, List<Message> tail) {
        List<Message> result = new ArrayList<>();
        Message summaryMsg = new Message();
        summaryMsg.setRole(MessageRole.SYSTEM);
        summaryMsg.setContent("以下是之前对话的摘要：\n" + summary);
        result.add(summaryMsg);
        result.addAll(tail);
        return result;
    }

    private List<Message> tailByKeepRecent(List<Message> history, int keepRecent) {
        int from = Math.max(0, history.size() - keepRecent);
        return new ArrayList<>(history.subList(from, history.size()));
    }

    private int indexOfMessageId(List<Message> history, Long messageId) {
        if (messageId == null) {
            return -1;
        }
        for (int i = 0; i < history.size(); i++) {
            if (messageId.equals(history.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    private double resolveThresholdKb(Map<String, Object> configMap) {
        Object v = configMap.get(ConfigKeys.Agent.SUMMARY_THRESHOLD_KB);
        return v instanceof Number n ? n.doubleValue() : 100.0;
    }

    private int resolveKeepRecent(Map<String, Object> configMap) {
        Object v = configMap.get(ConfigKeys.Agent.SUMMARY_KEEP_MESSAGES);
        return v instanceof Number n ? Math.clamp(n.intValue(), 1, 50) : 6;
    }

    /**
     * 体积超阈值后，把 keepRecent 收敛到实际条数允许的范围内，保证被压缩部分至少 2 条。
     *
     * <p>条数不足（≤2 条）时返回 0，表示无法压缩 —— 此时 older 会是空集，
     * 摘要没有内容可压，直接保留原始上下文。</p>
     */
    private int effectiveKeepRecent(int keepRecent, int historySize) {
        return Math.min(keepRecent, historySize - 2);
    }

    private int resolveToolResultCharLimit(Map<String, Object> configMap) {
        Object v = configMap.get(ConfigKeys.Agent.SUMMARY_TOOL_RESULT_TOKEN_LIMIT);
        int tokenLimit = v instanceof Number n ? Math.clamp(n.intValue(), 50, 5000) : 500;
        return tokenLimit * 4;
    }

    private void emit(ChatContext ctx, String statusJson) {
        if (ctx != null && statusJson != null && !statusJson.isBlank()
                && ctx.getPreStreamStatusEvents() != null) {
            ctx.getPreStreamStatusEvents().add(ToolEventGenerator.STATUS_PREFIX + statusJson);
        }
    }
}
