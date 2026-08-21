package com.lengbot.controller;

import com.lengbot.service.sandbox.SandboxFs;
import com.lengbot.service.sandbox.SandboxPath;
import com.lengbot.util.SandboxPathValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * 沙盒文件访问接口（本地磁盘后端 {@code lengbot.sandbox.backend=local} 时的交付文件下载通道）。
 * <p>URL 形态与 MinIO 对象键一致：{@code /api/sandbox/files/{minioPath}}，
 * 如 {@code /api/sandbox/files/sessions/123/outputs/reports/report.md}。</p>
 * <ul>
 *   <li>不带参数 → Content-Disposition: inline（浏览器内联预览）</li>
 *   <li>{@code ?attachment=1} → Content-Disposition: attachment（强制下载）</li>
 * </ul>
 * <p>路径安全：{@link SandboxPathValidator#checkReadable} 只放行 skills/ 与 sessions/ 前缀，
 * 并拒绝 {@code ..} 路径遍历。</p>
 *
 * @author lw
 * @since 2026-08-21
 */
@Slf4j
@Tag(name = "沙盒文件", description = "沙盒文件访问（本地磁盘后端下载通道）")
@RestController
@RequestMapping("/api/sandbox/files")
@RequiredArgsConstructor
public class SandboxFileController {

    private final SandboxFs sandboxFs;

    @Operation(summary = "读取沙盒文件（inline 预览或下载）")
    @GetMapping("/{*minioPath}")
    public ResponseEntity<Resource> getFile(
            @PathVariable String minioPath,
            @RequestParam(name = "attachment", defaultValue = "0") int attachment) {
        String rel = SandboxPathValidator.normalize(minioPath);
        SandboxPathValidator.checkReadable(rel);
        SandboxPath path = toSandboxPath(rel);

        byte[] bytes = sandboxFs.readBytes(path);
        String name = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
        String contentType = inferContentType(name);

        log.info("[SandboxFile] 读取沙盒文件: path={}, size={}, attachment={}", rel, bytes.length, attachment);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, buildContentDisposition(name, attachment != 0))
                .cacheControl(CacheControl.noCache())
                .body(new ByteArrayResource(bytes));
    }

    /** 从 MinIO 对象键反推 SandboxPath（读操作下 type 不影响 {@link SandboxPath#toMinioPath()} 结果） */
    private static SandboxPath toSandboxPath(String rel) {
        if (rel.startsWith("skills/")) {
            return new SandboxPath(SandboxPath.PathType.SKILL, rel.substring("skills/".length()));
        }
        if (rel.startsWith("sessions/")) {
            return new SandboxPath(SandboxPath.PathType.WORKSPACE, rel.substring("sessions/".length()));
        }
        throw new IllegalArgumentException("非法的沙盒路径前缀: " + rel);
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
