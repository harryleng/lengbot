package com.lengbot.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文档入库共享工具：扫描件判定、markdown 路径生成、OCR 内容合并。
 * <p>抽出来的原因是：上传阶段（DocumentUploadExecutor）与重新入库阶段
 * （DocumentServiceImpl.processDocumentWithProgress）都要用同一套扫描件判定逻辑，
 * 避免两处各写一份魔法数字导致日后漂移。</p>
 *
 * @author lw
 * @since 2026-09-30
 */
public final class DocIngestUtil {

    private DocIngestUtil() {
    }

    /**
     * PDF 扫描件判定阈值：每页平均有效字符数低于该值即视为「无文本层」。
     * <p>为什么按页均而不是按总长度：扫描件往往带 PDF 书签，Tika 能从中抠出上千字的目录，
     * 只看总长度会把它误判成「内容足够」，从而永远绕开 OCR。</p>
     */
    public static final double MIN_CHARS_PER_PAGE = 20.0;

    /** 拿不到 PDF 页数时的兜底阈值（沿用原判据） */
    public static final int MIN_TOTAL_CHARS = 50;

    public static boolean isImageType(String fileType) {
        return "jpg".equals(fileType) || "jpeg".equals(fileType) || "png".equals(fileType)
                || "bmp".equals(fileType) || "tiff".equals(fileType) || "tif".equals(fileType);
    }

    /**
     * 读取 PDF 页数，失败返回 -1（交由调用方兜底）
     */
    public static int countPdfPages(Path file) {
        if (file == null || !Files.exists(file)) {
            return -1;
        }
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            return pdf.getNumberOfPages();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 扫描件判定：按「每页平均有效字符数」而不是总字符数。
     */
    public static boolean isScannedPdf(String content, Path file) {
        int pages = countPdfPages(file);
        if (pages <= 0) {
            // 读不到页数（损坏/加密）时退回原判据，不改变既有行为
            return content == null || content.trim().length() < MIN_TOTAL_CHARS;
        }
        int effectiveChars = content == null ? 0 : content.replaceAll("\\s+", "").length();
        double charsPerPage = (double) effectiveChars / pages;
        if (charsPerPage < MIN_CHARS_PER_PAGE) {
            return true;
        }
        return false;
    }

    public static String generateMarkdownPath(Long knowledgeId, String filePath) {
        String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        return String.format("knowledge/%d/parsed/%s.md", knowledgeId, baseName);
    }

    public static String mergeOcrContent(String originalContent, String ocrContent) {
        if (originalContent == null || originalContent.isBlank()) {
            return ocrContent;
        }
        return originalContent + "\n\n---\n\n## OCR 识别内容\n\n" + ocrContent;
    }
}
