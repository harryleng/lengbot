package com.lengbot.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.common.task.TaskCancelledException;
import com.lengbot.entity.Document;
import com.lengbot.entity.Knowledge;
import com.lengbot.enums.DocumentStatus;
import com.lengbot.service.DocumentService;
import com.lengbot.service.KnowledgeService;
import com.lengbot.service.TaskService;
import com.lengbot.util.DocumentSecurityScanUtil;
import com.lengbot.util.MinioUtil;
import com.lengbot.util.OcrUtil;
import com.lengbot.util.RedisUtil;
import com.lengbot.util.TikaUtil;
import com.lengbot.util.DocIngestUtil;
import com.lengbot.entity.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 文档上传任务执行器：MinIO上传 + Tika解析 + OCR + Markdown转换
 *
 * @author lw
 * @since 2026-05-21
 */
@Slf4j
@Component("documentUploadExecutor")
@RequiredArgsConstructor
public class DocumentUploadExecutor implements TaskExecutor {

    private final DocumentService documentService;
    private final KnowledgeService knowledgeService;
    private final TaskService taskService;
    private final MinioUtil minioUtil;
    private final TikaUtil tikaUtil;
    private final OcrUtil ocrUtil;
    private final DocumentSecurityScanUtil documentSecurityScanUtil;
    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;

    @Override
    public String execute(Task task) throws Exception {
        JsonNode payload = objectMapper.readTree(task.getPayload());
        Long documentId = payload.get("documentId").asLong();
        String tempPath = payload.get("tempPath").asText();
        boolean ocrEnabled = payload.has("ocrEnabled") && payload.get("ocrEnabled").asBoolean();

        Document doc = documentService.getById(documentId);
        if (doc == null) {
            log.warn("[文档上传执行器] 文档不存在, documentId={}", documentId);
            return "文档不存在, documentId=" + documentId;
        }

        log.info("[文档上传执行器] 开始, taskId={}, documentId={}, tempPath={}", task.getId(), documentId, tempPath);

        try {
            // 1. 上传原始文件到 MinIO
            taskService.updateProgress(task.getId(), 10, "正在上传文件...");
            Path temp = Path.of(tempPath);
            long fileSize = Files.size(temp);
            try (InputStream is = Files.newInputStream(temp)) {
                minioUtil.upload(is, doc.getFilePath(), fileSize, "application/octet-stream");
            }
            log.info("[文档上传执行器] MinIO上传完成, documentId={}", documentId);
            checkCancelled(task.getId());

            // 2. Tika 解析为 Markdown（DOCX 提取图片）
            taskService.updateProgress(task.getId(), 30, "正在解析文档...");
            String markdownContent = null;
            if ("docx".equals(doc.getFileType())) {
                try (InputStream is = Files.newInputStream(temp)) {
                    var result = tikaUtil.parseDocxToMarkdownWithImages(is);
                    markdownContent = result.markdown();
                    // 上传图片到 MinIO 并替换占位符
                    for (var img : result.images()) {
                        String imgFileName = UUID.randomUUID() + "_" + img.fileName();
                        String imgPath = String.format("knowledge/%d/images/%s",
                                doc.getKnowledgeId(), imgFileName);
                        minioUtil.upload(new ByteArrayInputStream(img.data()), imgPath, img.data().length, img.contentType());
                        String url = "/api/knowledge/images/" + doc.getKnowledgeId() + "/" + imgFileName;
                        markdownContent = markdownContent.replace(img.placeholder(), "![image](" + url + ")");
                    }
                    if (!result.images().isEmpty()) {
                        log.info("[文档上传执行器] DOCX图片提取完成, documentId={}, count={}", documentId, result.images().size());
                    }
                }
            } else {
                try (InputStream is = Files.newInputStream(temp)) {
                    markdownContent = tikaUtil.parseToMarkdown(is, doc.getName());
                }
            }
            checkCancelled(task.getId());

            // 3. OCR 增强
            if (ocrEnabled || needsOcr(markdownContent, doc.getFileType(), temp)) {
                taskService.updateProgress(task.getId(), 50, "正在OCR识别...");
                try (InputStream is = Files.newInputStream(temp)) {
                    String ocrContent = tryOcr(is, doc.getFileType());
                    if (ocrContent != null && !ocrContent.isBlank()) {
                        markdownContent = DocIngestUtil.mergeOcrContent(markdownContent, ocrContent);
                        log.info("[文档上传执行器] OCR识别完成, documentId={}, ocrLength={}", documentId, ocrContent.length());
                    }
                }
                checkCancelled(task.getId());
            }

            // 4. 内容安全扫描（知识库开启时）
            if (markdownContent != null && !markdownContent.isBlank()) {
                taskService.updateProgress(task.getId(), 70, "正在进行内容安全扫描...");
                Knowledge knowledge = knowledgeService.getById(doc.getKnowledgeId());
                documentSecurityScanUtil.scanIfEnabled(knowledge, markdownContent);
            }

            // 5. 上传 Markdown 到 MinIO
            taskService.updateProgress(task.getId(), 80, "正在保存解析结果...");
            if (markdownContent != null) {
                String markdownPath = DocIngestUtil.generateMarkdownPath(doc.getKnowledgeId(), doc.getFilePath());
                minioUtil.uploadString(markdownContent, markdownPath, "text/markdown");
                doc.setMarkdownPath(markdownPath);
            }

            // 6. 更新文档状态为 UPLOADED，清空错误消息
            doc.setStatus(DocumentStatus.UPLOADED);
            doc.setErrorMessage(null);
            documentService.updateById(doc);
            taskService.updateProgress(task.getId(), 95, "处理完成");

            // 7. 清理临时文件
            try {
                Files.deleteIfExists(temp);
            } catch (Exception e) {
                log.warn("[文档上传执行器] 临时文件清理失败, tempPath={}", tempPath, e);
            }

            log.info("[文档上传执行器] 完成, documentId={}", documentId);
            return String.format("文档上传完成, documentId=%d, markdownPath=%s", documentId, doc.getMarkdownPath());

        } catch (Exception e) {
            log.error("[文档上传执行器] 失败, documentId={}", documentId, e);
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(buildErrorMessage(e));
            documentService.updateById(doc);
            // 清理临时文件
            try {
                Files.deleteIfExists(Path.of(tempPath));
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    /**
     * 判断是否需要 OCR 增强。
     *
     * @param content  解析器已提取的文本
     * @param fileType 文件类型
     * @param file     原始文件（PDF 需要读页数来判断是否为扫描件）
     */
    private boolean needsOcr(String content, String fileType, Path file) {
        if (content == null || content.isBlank()) {
            return true;
        }
        if ("pdf".equals(fileType)) {
            return DocIngestUtil.isScannedPdf(content, file);
        }
        if (DocIngestUtil.isImageType(fileType)) {
            return content.trim().length() < DocIngestUtil.MIN_TOTAL_CHARS;
        }
        return false;
    }

    // 扫描件判定 / PDF 页数读取已移至 DocIngestUtil，与重新入库逻辑共用，避免重复魔法数字。

    private String tryOcr(InputStream inputStream, String fileType) {
        try {
            if ("pdf".equals(fileType)) {
                return ocrUtil.recognizePdf(inputStream);
            } else if (DocIngestUtil.isImageType(fileType)) {
                return ocrUtil.recognizeImage(inputStream);
            }
        } catch (Exception e) {
            log.warn("[OCR] 识别失败, fileType={}", fileType, e);
        }
        return null;
    }

    // mergeOcrContent / generateMarkdownPath 已移至 DocIngestUtil。

    private void checkCancelled(Long taskId) {
        if (redisUtil.hasCancelSignal(taskId)) {
            throw new TaskCancelledException();
        }
    }

    private String buildErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        StackTraceElement[] stack = e.getStackTrace();
        if (stack.length > 0) {
            StringBuilder sb = new StringBuilder(msg);
            for (int i = 0; i < Math.min(3, stack.length); i++) {
                sb.append("\n  at ").append(stack[i].getClassName())
                        .append(".").append(stack[i].getMethodName())
                        .append(":").append(stack[i].getLineNumber());
            }
            return sb.toString();
        }
        return msg;
    }
}
