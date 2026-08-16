package com.lengbot.util;

import com.lengbot.dto.ChatAttachmentDTO;
import com.lengbot.dto.ChatRequestDTO;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 将发给模型的消息序列化为 Trace 可观测结构（完整文本 + 附件元数据，不截断正文）
 */
public final class LlmTraceMessageSerializer {

    private LlmTraceMessageSerializer() {
    }

    /** 孤立 USER 占位 ASSISTANT 的标识文本 */
    private static final String ORPHAN_PLACEHOLDER = "（未完成的回复）";

    /**
     * @param messages     实际发给 LLM 的消息列表
     * @param request      本轮对话请求（用于附件 previewUrl 等）
     * @param lastUserHasAttachments 当前轮用户消息是否带附件（用于对齐 media 与 DTO）
     */
    public static List<Map<String, Object>> toTraceMessages(
            List<Msg> messages,
            ChatRequestDTO request,
            boolean lastUserHasAttachments) {
        return toTraceMessages(messages, request, lastUserHasAttachments, null);
    }

    /**
     * @param userMentionsPerUserIndex 与 messages 中 UserMessage 出现顺序对齐的 mention 快照
     */
    public static List<Map<String, Object>> toTraceMessages(
            List<Msg> messages,
            ChatRequestDTO request,
            boolean lastUserHasAttachments,
            List<List<Map<String, Object>>> userMentionsPerUserIndex) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatAttachmentDTO> currentAttachments = request != null && request.getAttachments() != null
                ? request.getAttachments() : List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        // 定位最后一条 UserMessage：该条及之后为本轮消息，之前为历史消息
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MsgRole.USER) {
                lastUserIdx = i;
                break;
            }
        }
        int userMsgIdx = 0;
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            boolean isCurrentUser = lastUserHasAttachments && i == lastUserIdx;
            Map<String, Object> item = toTraceMessageItem(msg, isCurrentUser ? currentAttachments : List.of());
            // 标记消息来源：历史 / 本轮
            item.put("source", i < lastUserIdx ? "history" : "current");
            // 标记孤立 USER 占位 ASSISTANT（内容检测，兼容 DB Message 和 AgentScope Msg）
            if (ORPHAN_PLACEHOLDER.equals(extractText(msg))) {
                item.put("orphanPlaceholder", true);
            }
            if (msg.getRole() == MsgRole.USER && userMentionsPerUserIndex != null
                    && userMsgIdx < userMentionsPerUserIndex.size()) {
                List<Map<String, Object>> mentions = userMentionsPerUserIndex.get(userMsgIdx);
                if (mentions != null && !mentions.isEmpty()) {
                    item.put("mentions", mentions);
                }
                userMsgIdx++;
            } else if (msg.getRole() == MsgRole.USER) {
                userMsgIdx++;
            }
            result.add(item);
        }
        return result;
    }

    public static Map<String, Object> toTraceMessageItem(Msg msg, List<ChatAttachmentDTO> attachmentHints) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", msg.getRole().name().toLowerCase());
        item.put("content", extractText(msg));
        if (msg.getRole() == MsgRole.USER) {
            List<Map<String, Object>> mediaTrace = traceMediaList(msg, attachmentHints);
            if (!mediaTrace.isEmpty()) {
                item.put("media", mediaTrace);
            }
        }
        return item;
    }

    private static String extractText(Msg msg) {
        if (msg.getContent() == null) {
            return "";
        }
        return msg.getContent().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
    }

    private static List<Map<String, Object>> traceMediaList(Msg msg, List<ChatAttachmentDTO> hints) {
        if (msg.getContent() == null) {
            return List.of();
        }
        List<ImageBlock> images = msg.getContent().stream()
                .filter(block -> block instanceof ImageBlock)
                .map(block -> (ImageBlock) block)
                .toList();
        if (images.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            ImageBlock image = images.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            ChatAttachmentDTO hint = hints != null && i < hints.size() ? hints.get(i) : null;
            if (hint != null) {
                if (hint.getType() != null) {
                    m.put("type", hint.getType());
                }
                if (hint.getFileName() != null) {
                    m.put("fileName", hint.getFileName());
                }
                if (hint.getPreviewUrl() != null) {
                    m.put("previewUrl", hint.getPreviewUrl());
                }
                if (hint.getObjectKey() != null) {
                    m.put("objectKey", hint.getObjectKey());
                }
                if (hint.getMimeType() != null) {
                    m.put("mimeType", hint.getMimeType());
                }
            } else {
                // AgentScope ImageBlock 数据在 Source 子类中（Base64Source / URLSource）
                Source src = image.getSource();
                if (src instanceof io.agentscope.core.message.Base64Source b64) {
                    m.put("inlineData", true);
                    String data = b64.getData();
                    if (data != null) {
                        m.put("approxChars", data.length());
                    }
                    String mediaType = b64.getMediaType();
                    if (mediaType != null) {
                        m.put("mimeType", mediaType);
                    }
                } else if (src instanceof io.agentscope.core.message.URLSource url) {
                    m.put("inlineData", false);
                    if (url.getUrl() != null) {
                        m.put("url", url.getUrl());
                    }
                    String mediaType = url.getMimeType();
                    if (mediaType != null) {
                        m.put("mimeType", mediaType);
                    }
                }
            }
            result.add(m);
        }
        return result;
    }
}
