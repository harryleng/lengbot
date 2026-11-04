package com.lengbot.service;

import com.lengbot.util.ModelCalls;

import com.lengbot.model.ModelFactory;
import com.lengbot.model.ProviderResolver;
import com.lengbot.util.LlmTraceContext;
import com.lengbot.util.Msgs;
import io.agentscope.core.model.Model;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询改写服务：将用户原始问题改写为更适合知识库检索的查询
 * <p>短查询、模糊查询、代词引用等场景下，改写后能显著提升向量检索召回率。</p>
 *
 * @author lw
 * @since 2026-06-26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    private final ModelFactory modelFactory;
    private final ProviderResolver providerResolver;

    /**
     * 上下文最大字符数：改写只需消歧信息，过长会稀释语义且抬高成本。
     */
    private static final int MAX_CONTEXT_CHARS = 1200;
    /**
     * 改写结果最大字符数。超出通常意味着模型在解释而非改写，判定为无效。
     */
    private static final int MAX_REWRITTEN_CHARS = 300;

    private static final String REWRITE_SYSTEM_PROMPT = """
            你是一个查询改写助手。将用户问题改写为更适合知识库向量检索的查询。
            规则：
            1. 保持核心语义不变，不得改变用户的检索意图
            2. 若有对话历史，用它补全代词指代、省略的主语或缺失的时间/对象限定
            3. 将口语化表达转为更正式的检索词
            4. 只输出改写后的查询本身，不要输出任何解释、前缀、引号或序号
            5. 如果问题已经足够明确，直接原样输出
            """;

    /** 模型不遵守格式约定时的常见前缀，需剥离后再使用。 */
    private static final List<String> NOISE_PREFIXES = List.of(
            "改写后：", "改写后:", "改写结果：", "改写结果:",
            "改写后的查询：", "改写后的查询:", "改写：", "改写:",
            "查询：", "查询:", "输出：", "输出:", "答案：", "答案:"
    );

    /**
     * 改写查询。失败时静默降级，返回原始查询。
     *
     * @param query 原始用户问题
     * @return 改写后的查询，失败时返回原始问题
     */
    public String rewrite(String query) {
        return rewrite(query, null);
    }

    /**
     * 改写查询。失败或输出不合规时静默降级，返回原始查询。
     *
     * @param query      原始用户问题
     * @param historyCtx 对话历史摘要（可为 null/空），用于消解代词与省略
     * @return 改写后的查询，失败时返回原始问题
     */
    public String rewrite(String query, String historyCtx) {
        if (query == null || query.isBlank()) {
            return query;
        }
        try {
            Long providerId = providerResolver.resolve();
            Model model = modelFactory.getModel(providerId);

            String userContent = buildUserContent(query, historyCtx);
            List<Msg> messages = List.of(
                    Msgs.system(REWRITE_SYSTEM_PROMPT),
                    Msgs.user(userContent)
            );
            var response = LlmTraceContext.callWithoutTrace(() -> ModelCalls.call(model, messages));
            String rewritten = sanitize(Msgs.extractText(response));

            if (rewritten == null || rewritten.isBlank() || rewritten.equals(query)) {
                return query;
            }
            if (rewritten.length() > MAX_REWRITTEN_CHARS) {
                log.warn("[QueryRewrite] 改写结果过长（{} 字符），判定无效，使用原始查询", rewritten.length());
                return query;
            }
            log.info("[QueryRewrite] 原始查询: {} → 改写: {}", query, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("[QueryRewrite] 查询改写失败，使用原始查询: {}", e.getMessage());
            return query;
        }
    }

    /**
     * 组装用户侧输入。历史过长时截断尾部，优先保留最近内容。
     */
    private String buildUserContent(String query, String historyCtx) {
        if (historyCtx == null || historyCtx.isBlank()) {
            return query;
        }
        String ctx = historyCtx.trim();
        if (ctx.length() > MAX_CONTEXT_CHARS) {
            ctx = "…" + ctx.substring(ctx.length() - MAX_CONTEXT_CHARS);
        }
        return "对话历史：\n" + ctx + "\n\n当前问题：\n" + query;
    }

    /**
     * 清洗模型输出：取首段非空行、剥离噪音前缀与包裹引号。
     * <p>prompt 已要求「只输出查询本身」，但模型仍可能带前缀或解释，
     * 若直接使用会污染向量化输入，故在此统一兜底。</p>
     *
     * @param raw 模型原始输出
     * @return 清洗后的查询，无法提取有效内容时返回空白串
     */
    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();

        // 取第一行非空内容：模型常在正文后追加解释性段落
        String[] lines = text.split("\\R");
        for (String line : lines) {
            String candidate = line.trim();
            if (!candidate.isEmpty()) {
                text = candidate;
                break;
            }
        }

        for (String prefix : NOISE_PREFIXES) {
            if (text.startsWith(prefix)) {
                text = text.substring(prefix.length()).trim();
                break;
            }
        }

        // 剥离成对的包裹引号
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '“' && last == '”')
                    || (first == '`' && last == '`')) {
                text = text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }
}
