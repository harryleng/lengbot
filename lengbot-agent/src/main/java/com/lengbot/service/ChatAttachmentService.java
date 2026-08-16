package com.lengbot.service;

import com.lengbot.dto.ChatAttachmentDTO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 对话附件上传服务
 */
public interface ChatAttachmentService {

    /**
     * 上传对话附件到 MinIO
     *
     * @param agentId   Agent ID
     * @param sessionId 会话 ID（可为空，新会话用临时路径）
     * @param configVersion 配置版本（与 Chat 页选中版本一致，可为空）
     * @param file      文件
     * @return 附件信息
     */
    ChatAttachmentDTO upload(Long agentId, Long sessionId, Integer configVersion, MultipartFile file);

    /**
     * 为历史消息中的附件重新生成预览 URL（签名 URL 过期后刷新）
     *
     * @param attachments 含 objectKey 的附件列表
     * @return 带新 previewUrl 的附件列表
     */
    java.util.List<ChatAttachmentDTO> refreshPreviewUrls(java.util.List<ChatAttachmentDTO> attachments);

    /**
     * 删除未发送的缓冲区附件（MinIO 原文件 + 文档解析产物）
     *
     * @param agentId     Agent ID
     * @param sessionId   会话 ID（可为空）
     * @param attachment  上传接口返回的附件信息
     */
    void delete(Long agentId, Long sessionId, ChatAttachmentDTO attachment);

    /**
     * 删除已落库的附件文件（消息删除/会话清理等内部场景）
     *
     * @param sessionId  会话 ID
     * @param attachment 附件信息（含 objectKey、type、fileName）
     */
    void deleteStoredAttachment(Long sessionId, ChatAttachmentDTO attachment);
}
