package com.lengbot.util;

import com.lengbot.dto.ChatAttachmentDTO;
import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import com.lengbot.util.Msgs;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 构建带图片/视频的多模态 UserMessage（OpenAI 兼容 data URL）
 */
@Slf4j
public final class ChatMessageMediaUtil {

    private ChatMessageMediaUtil() {
    }

    /**
     * 构建用户消息：文本 + 附件（base64 data URL）
     */
    public static Msg buildUserMessage(String text, List<ChatAttachmentDTO> attachments,
                                               MinioUtil minioUtil) {
        if (attachments == null || attachments.isEmpty()) {
            return Msgs.user(text != null ? text : "");
        }
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(Msgs.textBlock(text != null && !text.isBlank() ? text : "请根据附件内容回答。"));
        for (ChatAttachmentDTO att : attachments) {
            if (att.getObjectKey() == null || att.getObjectKey().isBlank()) {
                continue;
            }
            try {
                byte[] bytes = minioUtil.downloadBytes(att.getObjectKey());
                String mime = att.getMimeType() != null ? att.getMimeType() : "application/octet-stream";
                String base64 = Base64.getEncoder().encodeToString(bytes);
                blocks.add(Msgs.imageBlock(base64, mime));
            } catch (Exception e) {
                log.warn("[ChatMedia] 读取附件失败: key={}, error={}", att.getObjectKey(), e.getMessage());
            }
        }
        if (blocks.size() <= 1) {
            return Msgs.user(text != null ? text : "");
        }
        return Msgs.user(blocks);
    }
}
