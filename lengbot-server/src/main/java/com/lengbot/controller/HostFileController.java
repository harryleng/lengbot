package com.lengbot.controller;

import com.lengbot.util.LocalFilePathValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 主机文件访问接口（lengbot.local-file.root 配置后生效）。
 * <p>为会话文件树中“outputs 分区改挂主机根”的文件提供预览/下载通道，
 * 路径安全由 {@link LocalFilePathValidator} 保证（只允许落在白名单根目录内，拒绝 .. 遍历）。</p>
 * <ul>
 *   <li>不带参数 → Content-Disposition: inline（浏览器内联预览）</li>
 *   <li>{@code ?attachment=1} → Content-Disposition: attachment（强制下载）</li>
 * </ul>
 *
 * @author lw
 * @since 2026-08-23
 */
@Slf4j
@Tag(name = "主机文件", description = "主机文件访问（local-file.root 白名单下载通道）")
@RestController
@RequestMapping("/api/host/files")
public class HostFileController {

    @Value("${lengbot.local-file.root:}")
    private String localFileRootRaw;

    @Operation(summary = "读取主机白名单内的文件（inline 预览或下载）")
    @GetMapping("/{*path}")
    public ResponseEntity<Resource> getFile(
            @PathVariable String path,
            @RequestParam(name = "attachment", defaultValue = "0") int attachment) {
        if (localFileRootRaw == null || localFileRootRaw.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Path root = Path.of(localFileRootRaw).toAbsolutePath().normalize();
        Path target;
        try {
            target = LocalFilePathValidator.resolve(path, root);
        } catch (SecurityException e) {
            log.warn("[HostFile] 路径越界被拒绝: path={}", path);
            return ResponseEntity.status(403).build();
        }
        if (!Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            String name = target.getFileName().toString();
            String contentType = inferContentType(name);
            log.info("[HostFile] 读取主机文件: path={}, size={}, attachment={}", path, bytes.length, attachment);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, buildContentDisposition(name, attachment != 0))
                    .cacheControl(CacheControl.noCache())
                    .body(new ByteArrayResource(bytes));
        } catch (Exception e) {
            log.warn("[HostFile] 读取失败: path={}, error={}", path, e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }

    /** 构建 Content-Disposition（支持中文文件名：ascii fallback + UTF-8 编码） */
    private static String buildContentDisposition(String name, boolean attachment) {
        String asciiName = name.replaceAll("[^\\x20-\\x7E]", "_");
        if (asciiName.isBlank()) {
            asciiName = "download";
        }
        asciiName = asciiName.replace("\"", "");
        String encodedUtf8 = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = attachment ? "attachment" : "inline";
        return disposition + "; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedUtf8;
    }

    private static String inferContentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        return "application/octet-stream";
    }
}
