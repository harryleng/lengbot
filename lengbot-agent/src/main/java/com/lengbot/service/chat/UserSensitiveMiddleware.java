package com.lengbot.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.enums.MessageRole;
import com.lengbot.util.SensitiveWordFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.lengbot.service.chat.ToolEventGenerator.STATUS_PREFIX;

/**
 * 用户输入敏感词拦截（在保存用户消息、调用模型/工作流之前）
 * <p>命中拦截时同步落库 USER 原文 + ASSISTANT 拦截提示两条消息，避免：
 * <ul>
 *   <li>用户原文丢失：刷新后历史只剩拦截提示，看不清用户说了什么</li>
 *   <li>ASSISTANT 提示二次落库：buildDoneEvent 重复保存同样文本</li>
 * </ul>
 * 落库完成后置 ctx.sensitiveUserBlocked=true，让 buildDoneEvent 跳过常规 AI 落库流程</p>
 *
 * @author finch
 * @since 2026-05-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSensitiveMiddleware implements ChatMiddleware {

    private static final String SENSITIVE_SCOPE_USER_INPUT = "user_input";

    private final MessageMiddleware messageMiddleware;
    private final ObjectMapper objectMapper;
    private final ToolEventGenerator toolEventGenerator;

    @Override
    public Flux<String> execute(ChatContext ctx, ChatMiddlewareChain next) {
        String userMessage = ctx.getRequest().getMessage();
        if ((userMessage == null || userMessage.isBlank())
                && ctx.getRequest().getAttachments() != null && !ctx.getRequest().getAttachments().isEmpty()) {
            userMessage = "请根据附件内容回答。";
        }
        Long agentId = ctx.getAgent() != null ? ctx.getAgent().getId() : null;
        SensitiveWordFilter.FilterResult check = SensitiveWordFilter.checkUserInput(
                userMessage, ctx.getConfigMap(), agentId, ctx.getSessionId());

        if (check.blocked()) {
            String tip = check.text();
            ctx.getFullReply().append(tip);
            // 1. 落库 USER 原文（含 attachments/mentions/requestId 元数据），保留用户上下文
            messageMiddleware.persistUserMessage(ctx);
            // 2. 落库 ASSISTANT 拦截提示，metadata 标记 sensitiveBlock=user_input 供历史回显渲染
            Long assistantMessageId = messageMiddleware.saveMessage(
                    ctx.getSessionId(), MessageRole.ASSISTANT, tip,
                    buildSensitiveMetadata(), 0);
            ctx.setAssistantMessageId(assistantMessageId);
            // 3. 标记敏感拦截已落库，buildDoneEvent 据此跳过二次落库与标题/记忆抽取等后置流程
            ctx.setSensitiveUserBlocked(true);
            return Flux.just(STATUS_PREFIX + toolEventGenerator.sensitiveBlockEvent(SENSITIVE_SCOPE_USER_INPUT, tip));
        }
        return next.proceed(ctx);
    }

    /**
     * 构建 ASSISTANT 拦截提示的 metadata：sensitiveBlock=user_input 供前端历史消息渲染拦截样式
     */
    private String buildSensitiveMetadata() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sensitiveBlock", SENSITIVE_SCOPE_USER_INPUT);
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("[UserSensitive] 序列化 metadata 失败: {}", e.getMessage());
            return null;
        }
    }
}
