package com.lengbot.controller;

import com.lengbot.common.Result;
import com.lengbot.vo.ChunkVO;
import com.lengbot.vo.DocumentDownloadVO;
import com.lengbot.vo.DocumentStreamVO;
import com.lengbot.dto.IngestDTO;
import com.lengbot.vo.UrlFetchPreviewVO;
import com.lengbot.dto.UrlSaveDTO;
import com.lengbot.entity.Document;
import com.lengbot.entity.Task;
import com.lengbot.service.ChunkService;
import com.lengbot.service.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 知识库文档管理接口
 *
 * @author lw
 * @since 2026-06-21
 */
@Tag(name = "知识库文档管理", description = "文档上传、入库、预览、下载、分块查看")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeDocController {

    private final DocumentService documentService;
    private final ChunkService chunkService;
    private final ObjectMapper objectMapper;

    // ========== 文档管理 ==========

    @Operation(summary = "上传文档到知识库（需要DEVELOPER及以上权限）")
    @PostMapping("/{id}/documents")
    public Result<Document> uploadDocument(@PathVariable Long id,
                                            @RequestParam("file") MultipartFile file,
                                            @RequestParam(defaultValue = "false") boolean ocrEnabled,
                                            @RequestParam(required = false) String force) {
        return Result.ok(documentService.uploadDocument(id, file, ocrEnabled, force));
    }

    @Operation(summary = "批量上传文档到知识库（需要DEVELOPER及以上权限）")
    @PostMapping("/{id}/documents/batch")
    public Result<List<Document>> uploadDocuments(@PathVariable Long id,
                                                   @RequestParam("files") List<MultipartFile> files,
                                                   @RequestParam(defaultValue = "false") boolean ocrEnabled,
                                                   @RequestParam(required = false) String force) {
        return Result.ok(documentService.uploadDocuments(id, files, ocrEnabled, force));
    }

    @Operation(summary = "预览URL网页内容（不入库）")
    @PostMapping("/{id}/documents/preview-url")
    public Result<UrlFetchPreviewVO> previewUrlDocument(@PathVariable Long id,
                                                         @RequestParam String url) {
        return Result.ok(documentService.previewUrlDocument(id, url));
    }

    @Operation(summary = "保存已预览的URL网页内容")
    @PostMapping("/{id}/documents/save-url")
    public Result<Document> saveUrlDocument(@PathVariable Long id,
                                             @Valid @RequestBody UrlSaveDTO request) {
        return Result.ok(documentService.saveUrlDocument(id, request));
    }

    @Operation(summary = "从URL抓取内容到知识库（需要DEVELOPER及以上权限）")
    @PostMapping("/{id}/documents/fetch-url")
    public Result<Document> fetchUrlDocument(@PathVariable Long id,
                                              @RequestParam String url) {
        return Result.ok(documentService.fetchUrlDocument(id, url));
    }

    @Operation(summary = "获取知识库下的文档列表（需要成员权限）")
    @GetMapping("/{id}/documents")
    public Result<?> listDocuments(@PathVariable Long id,
                                   @RequestParam(required = false) String keyword,
                                   @RequestParam(defaultValue = "1") int pageNum,
                                   @RequestParam(defaultValue = "50") int pageSize) {
        return Result.ok(documentService.listByKnowledgeIdWithPage(id, keyword, pageNum, pageSize));
    }

    @Operation(summary = "获取文档详情（需要成员权限）")
    @GetMapping("/documents/{docId}")
    public Result<Document> getDocument(@PathVariable Long docId) {
        return Result.ok(documentService.getById(docId));
    }

    @Operation(summary = "删除文档（需要DEVELOPER及以上权限）")
    @DeleteMapping("/documents/{docId}")
    public Result<Void> deleteDocument(@PathVariable Long docId) {
        documentService.deleteDocument(docId);
        return Result.ok();
    }

    @Operation(summary = "文档入库：分块+向量化（需要DEVELOPER及以上权限）")
    @PostMapping("/documents/{docId}/ingest")
    public Result<Task> ingestDocument(@PathVariable Long docId,
                                        @Valid @RequestBody IngestDTO request) throws Exception {
        String embeddingJson = objectMapper.writeValueAsString(request);
        return Result.ok(documentService.ingestDocument(docId, embeddingJson));
    }

    @Operation(summary = "手动同步 URL 文档（重新抓取，内容变更时更新并重新入库）")
    @PostMapping("/documents/{docId}/sync-url")
    public Result<Document> syncUrlDocument(@PathVariable Long docId) {
        return Result.ok(documentService.syncUrlDocument(docId));
    }

    @Operation(summary = "预览分块结果（不入库）")
    @PostMapping("/documents/{docId}/preview-chunks")
    public Result<List<String>> previewChunks(@PathVariable Long docId,
                                               @Valid @RequestBody IngestDTO request) throws Exception {
        String embeddingJson = objectMapper.writeValueAsString(request);
        return Result.ok(documentService.previewChunks(docId, embeddingJson));
    }

    @Operation(summary = "预览文档内容（需要成员权限）")
    @GetMapping("/documents/{docId}/preview")
    public Result<String> previewDocument(@PathVariable Long docId) {
        return Result.ok(documentService.previewDocument(docId));
    }

    @Operation(summary = "获取文档下载信息（预签名URL+文件类型）")
    @GetMapping("/documents/{docId}/download")
    public Result<DocumentDownloadVO> getDocumentDownloadUrl(@PathVariable Long docId) {
        return Result.ok(documentService.getDocumentDownloadUrl(docId));
    }

    @Operation(summary = "代理下载文档文件（强制下载，文件名正确）")
    @GetMapping("/documents/{docId}/download-file")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable Long docId) {
        DocumentStreamVO stream = documentService.downloadDocumentAsStream(docId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(stream.getFileName(), StandardCharsets.UTF_8)
                .build();
        // StreamingResponseBody 直接把流拷到响应输出，流只被消费一次，规避 InputStreamResource 被重复读取的异常
        StreamingResponseBody body = outputStream -> {
            try (InputStream in = stream.getInputStream()) {
                in.transferTo(outputStream);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stream.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(body);
    }

    @Operation(summary = "同源内联预览文档（供 iframe 渲染，规避跨域 MinIO 预签名 URL 被浏览器/插件拦截）")
    @GetMapping("/documents/{docId}/preview-file")
    public ResponseEntity<StreamingResponseBody> previewFile(@PathVariable Long docId) {
        // 复用下载流（已含权限校验 + 内容类型解析），仅将 Content-Disposition 改为 inline，
        // 使浏览器内置阅读器可内联渲染，且同源无 X-Frame-Options/插件拦截问题。
        DocumentStreamVO stream = documentService.downloadDocumentAsStream(docId);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(stream.getFileName(), StandardCharsets.UTF_8)
                .build();
        StreamingResponseBody body = outputStream -> {
            try (InputStream in = stream.getInputStream()) {
                in.transferTo(outputStream);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stream.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .body(body);
    }

    @Operation(summary = "代理获取知识库文档图片（供 Markdown 预览）")
    @GetMapping("/images/{knowledgeId}/{filename}")
    public ResponseEntity<StreamingResponseBody> getKnowledgeImage(
            @PathVariable Long knowledgeId, @PathVariable String filename) {
        // 业务编排（路径拼装 + MinIO stat/download）下沉到 DocumentService，Controller 仅做 Optional → ResponseEntity 的 HTTP 翻译
        return documentService.serveKnowledgeImage(knowledgeId, filename)
                .map(stream -> {
                    StreamingResponseBody body = outputStream -> {
                        try (InputStream in = stream.getInputStream()) {
                            in.transferTo(outputStream);
                        }
                    };
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(stream.getContentType()))
                            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)))
                            .body(body);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ========== 分块查看 ==========

    @Operation(summary = "获取文档的分块列表（含向量化状态）")
    @GetMapping("/documents/{docId}/chunks")
    public Result<List<ChunkVO>> listChunks(@PathVariable Long docId) {
        return Result.ok(chunkService.listChunkVOsByDocumentId(docId));
    }
}
