package com.lengbot.util;

import com.lengbot.dto.LlmTraceSpanDTO;
import com.lengbot.entity.Message;
import com.lengbot.service.MentionTraceSnapshotService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 为 Trace 详情中的 llm_input 消息补齐 user 消息的 mention 快照。
 */
public final class LlmTraceMentionEnricher {

    private LlmTraceMentionEnricher() {
    }

    /**
     * @param spans            已解析的 Trace spans
     * @param sessionMessages  会话全部消息（按 createTime 升序）
     * @param snapshotService  mention 快照解析服务
     */
    public static void enrich(List<LlmTraceSpanDTO> spans,
                              List<Message> sessionMessages,
                              MentionTraceSnapshotService snapshotService) {
        if (spans == null || spans.isEmpty() || sessionMessages == null || sessionMessages.isEmpty()
                || snapshotService == null) {
            return;
        }
        LlmTraceSpanDTO llmInput = spans.stream()
                .filter(s -> "messages_to_llm".equals(s.getName()))
                .findFirst()
                .orElse(null);
        if (llmInput == null || llmInput.getAttributes() == null) {
            return;
        }
        Object messagesObj = llmInput.getAttributes().get("messages");
        if (!(messagesObj instanceof List<?> rawList)) {
            return;
        }

        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                continue;
            }
            if (!"user".equals(String.valueOf(rawMap.get("role")))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> traceUser = (Map<String, Object>) rawMap;
            if (hasMentions(traceUser)) {
                continue;
            }
            String traceContent = traceUser.get("content") != null ? traceUser.get("content").toString() : "";
            Message db = snapshotService.matchDbUserByContent(sessionMessages, traceContent);
            List<Map<String, Object>> mentions = snapshotService.resolveForTraceUser(db, traceContent);
            if (!mentions.isEmpty()) {
                traceUser.put("mentions", mentions);
            }
        }
    }

    private static boolean hasMentions(Map<String, Object> traceUser) {
        Object mentions = traceUser.get("mentions");
        return mentions instanceof List<?> list && !list.isEmpty();
    }
}
