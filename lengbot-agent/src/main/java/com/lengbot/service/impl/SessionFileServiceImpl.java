package com.lengbot.service.impl;

import com.lengbot.common.BizException;
import com.lengbot.vo.SessionAttachmentVO;
import com.lengbot.vo.SessionFileContentVO;
import com.lengbot.vo.SessionFileEntryVO;
import com.lengbot.vo.SessionFileStatsVO;
import com.lengbot.vo.SessionFileTreeResponseVO;
import com.lengbot.enums.ErrorCode;
import com.lengbot.enums.SessionAttachmentSource;
import com.lengbot.service.ChatSessionService;
import com.lengbot.service.SessionFileService;
import com.lengbot.util.MinioUtil;
import com.lengbot.util.SessionStoragePath;
import io.minio.StatObjectResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话文件服务实现：基于 MinIO 非递归扫描构建懒加载目录树，索引 enrich 来源信息。
 *
 * @author lw
 * @since 2026-06-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionFileServiceImpl implements SessionFileService {

    private static final Set<String> TEXT_MIMES = Set.of(
            "text/plain", "text/markdown", "text/html", "text/csv", "application/json", "application/xml");
    /** 底层为纯文本、可直接读取预览的扩展名（与前端 filePreview.js 的 TEXT_SOURCE_PREVIEW_EXTENSIONS 保持一致） */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "text", "csv", "tsv", "json", "jsonl", "ndjson", "xml", "log",
            "yaml", "yml", "ini", "conf", "cfg", "toml", "properties", "env", "editorconfig",
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "py", "java", "go", "rs", "rb", "php",
            "c", "h", "cpp", "hpp", "cc", "cs", "kt", "kts", "scala", "swift", "sql", "sh", "bash", "zsh",
            "gradle", "dockerfile", "gitignore", "makefile");
    private static final long MAX_TEXT_PREVIEW_BYTES = 512 * 1024L;

    private final MinioUtil minioUtil;
    private final ChatSessionService chatSessionService;

    /** 沙盒后端：local 时文件树改读本地磁盘，minio 时沿用 MinIO（默认） */
    @Value("${lengbot.sandbox.backend:minio}")
    private String sandboxBackend;

    /** 本地磁盘后端根目录（与 LocalDiskSandboxFs 解析方式一致） */
    @Value("${lengbot.sandbox.local-root:./data/sandbox}")
    private String localRootRaw;

    /** 主机文件访问根（lengbot.local-file.root）：配置后，outputs 分区改挂到 {root}/outputs；留空则 outputs 仍读会话沙盒 */
    @Value("${lengbot.local-file.root:}")
    private String localFileRootRaw;

    private boolean isLocal() {
        return "local".equalsIgnoreCase(sandboxBackend);
    }

    /** 解析本地沙盒根（与 LocalDiskSandboxFs 构造一致：相对路径按进程 CWD 解析为绝对路径） */
    private Path localRootPath() {
        return Path.of(localRootRaw).toAbsolutePath().normalize();
    }

    /** 主机文件访问根（规范化绝对路径），未配置返回 null */
    private Path localFileRootPath() {
        if (localFileRootRaw == null || localFileRootRaw.isBlank()) {
            return null;
        }
        return Path.of(localFileRootRaw).toAbsolutePath().normalize();
    }

    /** 是否启用主机根（lengbot.local-file.root 已配置） */
    private boolean hostFsEnabled() {
        return localFileRootPath() != null;
    }

    /** outputs 分区是否改挂到主机根（仅当配置了 local-file.root 时） */
    private boolean isHostOutput(String normalized) {
        if (!hostFsEnabled()) {
            return false;
        }
        return normalized.equals(SessionStoragePath.OUTPUTS_DIR)
                || normalized.startsWith(SessionStoragePath.OUTPUTS_DIR + "/");
    }

    /** 将 outputs 分区的相对树路径解析到主机根下的绝对路径（剥离 "outputs/" 前缀） */
    private Path hostOutputResolve(String normalized) {
        String rel = normalized.equals(SessionStoragePath.OUTPUTS_DIR)
                ? ""
                : normalized.substring(SessionStoragePath.OUTPUTS_DIR.length() + 1);
        return localFileRootPath().resolve(rel).normalize();
    }

    /** 主机根文件访问 URL（由 HostFileController 提供，受 local-file 白名单校验） */
    private String hostFileUrl(String relativeTreePath) {
        return "/api/host/files/" + relativeTreePath;
    }

    /** 本地文件访问 URL（与 LocalDiskSandboxFs.resolveFileAccess / SandboxFileController 映射一致） */
    private String localFileUrl(String objectKey) {
        return "/api/sandbox/files/" + objectKey;
    }

    @Override
    public SessionFileTreeResponseVO listDirectory(Long sessionId, String path) {
        SessionFileTreeResponseVO resp = new SessionFileTreeResponseVO();
        String normalized = normalizeRelativePath(path);

        // 1. 根路径返回三个固定顶级目录（与 Yuxi 一致，即使为空也展示）
        if (normalized.isEmpty()) {
            resp.setEntries(rootDirectoryEntries(sessionId));
            resp.setStats(computeStats(sessionId));
            return resp;
        }

        // 2. 校验路径必须落在允许的分区下
        ensureAllowedPartition(normalized);

        // 3. 列举直接子条目；outputs 分区在配置 local-file.root 后改挂主机根
        List<MinioUtil.MinioDirEntry> rawEntries;
        if (isHostOutput(normalized)) {
            rawEntries = hostOutputListDirectoryEntries(normalized);
        } else {
            String prefix = SessionStoragePath.sessionRoot(sessionId) + normalized + "/";
            rawEntries = safeList(prefix);
            // 兼容旧路径：uploads/ 同时并入 inputs/
            if (normalized.equals(SessionStoragePath.INPUTS_DIR)) {
                rawEntries.addAll(legacyUploadsEntries(sessionId));
            }
        }

        // 4. 构建 attachments 索引 map（按 objectKey 索引）
        Map<String, SessionAttachmentVO> index = buildAttachmentIndex(sessionId);

        // 5. 转换 + enrich + 排序（目录优先，名称字典序），过滤 .keep 占位
        List<SessionFileEntryVO> entries = new ArrayList<>();
        for (MinioUtil.MinioDirEntry raw : rawEntries) {
            if (".keep".equals(raw.name)) {
                continue;
            }
            entries.add(toEntry(SessionStoragePath.sessionRoot(sessionId), normalized, raw, index));
        }
        entries.sort(Comparator
                .comparing((SessionFileEntryVO e) -> !Boolean.TRUE.equals(e.getDirectory()))
                .thenComparing(e -> e.getName() == null ? "" : e.getName(), String.CASE_INSENSITIVE_ORDER));
        disambiguateDuplicateDisplayNames(entries);
        resp.setEntries(entries);
        resp.setStats(computeStats(sessionId));
        return resp;
    }

    @Override
    public SessionFileContentVO readContent(Long sessionId, String path) {
        String normalized = normalizeRelativePath(path);
        ensureAllowedPartition(normalized);
        String objectKey = SessionStoragePath.sessionRoot(sessionId) + normalized;

        SessionFileContentVO vo = new SessionFileContentVO();
        vo.setPath(normalized);
        vo.setObjectKey(objectKey);

        if (isHostOutput(normalized)) {
            return readContentHost(normalized, vo);
        }

        if (isLocal()) {
            return readContentLocal(normalized, objectKey, vo);
        }

        StatObjectResponse stat;
        try {
            stat = minioUtil.statObject(objectKey);
        } catch (Exception e) {
            vo.setSupported(false);
            vo.setPreviewType("unsupported");
            vo.setMessage("文件不存在或已被删除");
            return vo;
        }
        vo.setSize(stat.size());
        String mime = resolveMime(stat.contentType(), normalized);
        vo.setMimeType(mime);

        // 1. 文本/Markdown/JSON/CSV/HTML：直接读内容
        String previewType = detectPreviewType(mime, normalized);
        if ("text".equals(previewType) || "markdown".equals(previewType)) {
            if (stat.size() > MAX_TEXT_PREVIEW_BYTES) {
                vo.setSupported(true);
                vo.setPreviewType(previewType);
                vo.setMessage("文件过大，建议下载后查看");
                vo.setPreviewUrl(minioUtil.getPresignedUrl(objectKey, mime));
                return vo;
            }
            try (InputStream in = minioUtil.download(objectKey)) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                vo.setSupported(true);
                vo.setPreviewType(previewType);
                vo.setContent(content);
                return vo;
            } catch (Exception e) {
                vo.setSupported(false);
                vo.setPreviewType("unsupported");
                vo.setMessage("读取内容失败：" + e.getMessage());
                return vo;
            }
        }

        // 2. 图片/PDF/视频：返回预签名 URL
        if ("image".equals(previewType) || "pdf".equals(previewType) || "video".equals(previewType)) {
            vo.setSupported(true);
            vo.setPreviewType(previewType);
            vo.setPreviewUrl(minioUtil.getPresignedUrl(objectKey, mime));
            return vo;
        }

        // 3. 其他二进制：返回下载 URL
        vo.setSupported(true);
        vo.setPreviewType("download");
        vo.setPreviewUrl(minioUtil.getPresignedUrl(objectKey, mime));
        return vo;
    }

    /** 本地磁盘后端：读取文件内容/预览（形态与 MinIO 分支一致） */
    private SessionFileContentVO readContentLocal(String normalized, String objectKey, SessionFileContentVO vo) {
        Path target = localRootPath().resolve(objectKey);
        if (!Files.isRegularFile(target)) {
            vo.setSupported(false);
            vo.setPreviewType("unsupported");
            vo.setMessage("文件不存在或已被删除");
            return vo;
        }
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            size = 0;
        }
        vo.setSize(size);
        String mime = guessMimeFromName(normalized);
        vo.setMimeType(mime);

        String previewType = detectPreviewType(mime, normalized);
        if ("text".equals(previewType) || "markdown".equals(previewType)) {
            if (size > MAX_TEXT_PREVIEW_BYTES) {
                vo.setSupported(true);
                vo.setPreviewType(previewType);
                vo.setMessage("文件过大，建议下载后查看");
                vo.setPreviewUrl(localFileUrl(objectKey));
                return vo;
            }
            try {
                String content = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
                vo.setSupported(true);
                vo.setPreviewType(previewType);
                vo.setContent(content);
                return vo;
            } catch (IOException e) {
                vo.setSupported(false);
                vo.setPreviewType("unsupported");
                vo.setMessage("读取内容失败：" + e.getMessage());
                return vo;
            }
        }
        if ("image".equals(previewType) || "pdf".equals(previewType) || "video".equals(previewType)) {
            vo.setSupported(true);
            vo.setPreviewType(previewType);
            vo.setPreviewUrl(localFileUrl(objectKey));
            return vo;
        }
        vo.setSupported(true);
        vo.setPreviewType("download");
        vo.setPreviewUrl(localFileUrl(objectKey));
        return vo;
    }

    /** 主机根 outputs 分区：按相对树路径列举直接子条目（形态与 localListDirectoryEntries 一致） */
    private List<MinioUtil.MinioDirEntry> hostOutputListDirectoryEntries(String normalized) {
        Path dir = hostOutputResolve(normalized);
        List<MinioUtil.MinioDirEntry> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (var stream = Files.list(dir)) {
            List<Path> children = stream.toList();
            for (Path p : children) {
                String name = p.getFileName().toString();
                MinioUtil.MinioDirEntry e = new MinioUtil.MinioDirEntry();
                e.name = name;
                boolean isDir = Files.isDirectory(p);
                e.directory = isDir;
                String childKey = normalized.isEmpty() ? name : normalized + "/" + name;
                e.objectName = isDir ? stripTrailingSlashLocal(childKey) : childKey;
                try {
                    e.size = isDir ? 0L : Files.size(p);
                } catch (IOException ex) {
                    e.size = 0L;
                }
                try {
                    e.lastModified = Files.getLastModifiedTime(p).toString();
                } catch (IOException ex) {
                    e.lastModified = null;
                }
                result.add(e);
            }
        } catch (IOException e) {
            log.warn("[SessionFile] 主机根列举失败: dir={}, error={}", dir, e.getMessage());
        }
        return result;
    }

    /** 主机根后端：读取文件内容/预览（形态与 readContentLocal 一致） */
    private SessionFileContentVO readContentHost(String normalized, SessionFileContentVO vo) {
        Path target = hostOutputResolve(normalized);
        if (!Files.isRegularFile(target)) {
            vo.setSupported(false);
            vo.setPreviewType("unsupported");
            vo.setMessage("文件不存在或已被删除");
            return vo;
        }
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            size = 0;
        }
        vo.setSize(size);
        String mime = guessMimeFromName(normalized);
        vo.setMimeType(mime);
        String previewType = detectPreviewType(mime, normalized);
        if ("text".equals(previewType) || "markdown".equals(previewType)) {
            if (size > MAX_TEXT_PREVIEW_BYTES) {
                vo.setSupported(true);
                vo.setPreviewType(previewType);
                vo.setMessage("文件过大，建议下载后查看");
                vo.setPreviewUrl(hostFileUrl(normalized));
                return vo;
            }
            try {
                String content = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
                vo.setSupported(true);
                vo.setPreviewType(previewType);
                vo.setContent(content);
                return vo;
            } catch (IOException e) {
                vo.setSupported(false);
                vo.setPreviewType("unsupported");
                vo.setMessage("读取内容失败：" + e.getMessage());
                return vo;
            }
        }
        if ("image".equals(previewType) || "pdf".equals(previewType) || "video".equals(previewType)) {
            vo.setSupported(true);
            vo.setPreviewType(previewType);
            vo.setPreviewUrl(hostFileUrl(normalized));
            return vo;
        }
        vo.setSupported(true);
        vo.setPreviewType("download");
        vo.setPreviewUrl(hostFileUrl(normalized));
        return vo;
    }

    @Override
    public String getDownloadUrl(Long sessionId, String path) {
        String normalized = normalizeRelativePath(path);
        ensureAllowedPartition(normalized);
        if (isHostOutput(normalized)) {
            return hostFileUrl(normalized) + "?attachment=1";
        }
        String objectKey = SessionStoragePath.sessionRoot(sessionId) + normalized;
        String fileName = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1)
                : normalized;
        if (isLocal()) {
            return localFileUrl(objectKey) + "?attachment=1";
        }
        String mime = guessMimeFromName(normalized);
        return minioUtil.getPresignedDownloadUrl(objectKey, fileName, mime);
    }

    @Override
    public void deleteFile(Long sessionId, String path) {
        String normalized = normalizeRelativePath(path);
        ensureAllowedPartition(normalized);
        // 主机根文件不在会话中提供删除（避免误删跨会话共享的主机文件）
        if (isHostOutput(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "主机文件不可在会话中删除");
        }
        // 禁止删除顶级分区目录
        if (isTopLevelPartition(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "不允许删除顶级目录");
        }
        String objectKey = SessionStoragePath.sessionRoot(sessionId) + normalized;
        if (isLocal()) {
            try {
                Files.deleteIfExists(localRootPath().resolve(objectKey));
            } catch (IOException e) {
                log.warn("[SessionFile] 本地删除失败: objectKey={}, error={}", objectKey, e.getMessage());
            }
        } else {
            minioUtil.delete(objectKey);
        }
        // 同步移除 attachments 索引中匹配 objectKey 的记录
        try {
            chatSessionService.removeSessionAttachmentByObjectKey(sessionId, objectKey);
        } catch (Exception e) {
            log.warn("[SessionFile] 同步移除附件索引失败: sessionId={}, objectKey={}", sessionId, objectKey, e);
        }
    }

    // ==================== 内部工具 ====================

    private List<SessionFileEntryVO> rootDirectoryEntries(Long sessionId) {
        List<SessionFileEntryVO> entries = new ArrayList<>();
        entries.add(dirEntry(SessionStoragePath.INPUTS_DIR, "用户上传"));
        entries.add(dirEntry(SessionStoragePath.OUTPUTS_DIR, "AI 产出"));
        entries.add(dirEntry(SessionStoragePath.WORKSPACE_DIR, "Agent 工作区"));
        // 兼容历史 uploads/ 目录：若 MinIO 中存在则额外展示
        if (hasAnyObject(SessionStoragePath.sessionRoot(sessionId) + "uploads/")) {
            entries.add(dirEntry("uploads", "历史上传（兼容）"));
        }
        return entries;
    }

    private SessionFileEntryVO dirEntry(String name, String displayName) {
        SessionFileEntryVO e = new SessionFileEntryVO();
        e.setName(displayName);
        e.setPath(name);
        e.setDirectory(true);
        return e;
    }

    private List<MinioUtil.MinioDirEntry> safeList(String prefix) {
        if (isLocal()) {
            return new ArrayList<>(localListDirectoryEntries(prefix));
        }
        try {
            return new ArrayList<>(minioUtil.listDirectoryEntries(prefix));
        } catch (Exception e) {
            log.warn("[SessionFile] 列举目录失败: prefix={}, error={}", prefix, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 兼容历史 sessions/{id}/uploads/ 下的文件，并入 inputs/ 视图 */
    private List<MinioUtil.MinioDirEntry> legacyUploadsEntries(Long sessionId) {
        String prefix = SessionStoragePath.sessionRoot(sessionId) + "uploads/";
        if (isLocal()) {
            return new ArrayList<>(localListDirectoryEntries(prefix));
        }
        try {
            return new ArrayList<>(minioUtil.listDirectoryEntries(prefix));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private boolean hasAnyObject(String prefix) {
        if (isLocal()) {
            return !localListDirectoryEntries(prefix).isEmpty();
        }
        try {
            return !minioUtil.listDirectoryEntries(prefix).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 本地磁盘后端：按 objectKey 前缀列举直接子条目（形态与 MinIO 分支的 MinioDirEntry 一致） */
    private List<MinioUtil.MinioDirEntry> localListDirectoryEntries(String prefix) {
        Path dir = localRootPath().resolve(prefix);
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        List<MinioUtil.MinioDirEntry> result = new ArrayList<>();
        try {
            List<Path> children = Files.list(dir).toList();
            for (Path p : children) {
                String name = p.getFileName().toString();
                MinioUtil.MinioDirEntry e = new MinioUtil.MinioDirEntry();
                e.name = name;
                boolean isDir = Files.isDirectory(p);
                e.directory = isDir;
                String childKey = prefix + name; // prefix 以 "/" 结尾
                e.objectName = isDir ? stripTrailingSlashLocal(childKey) : childKey;
                try {
                    e.size = isDir ? 0L : Files.size(p);
                } catch (IOException ex) {
                    e.size = 0L;
                }
                try {
                    e.lastModified = Files.getLastModifiedTime(p).toString();
                } catch (IOException ex) {
                    e.lastModified = null;
                }
                result.add(e);
            }
        } catch (IOException e) {
            log.warn("[SessionFile] 本地列举目录失败: prefix={}, error={}", prefix, e.getMessage());
            return new ArrayList<>();
        }
        return result;
    }

    private static String stripTrailingSlashLocal(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private SessionFileEntryVO toEntry(String root, String parentPath, MinioUtil.MinioDirEntry raw,
                                       Map<String, SessionAttachmentVO> index) {
        SessionFileEntryVO e = new SessionFileEntryVO();
        e.setPath(parentPath.isEmpty() ? raw.name : parentPath + "/" + raw.name);
        e.setDirectory(raw.directory);
        if (raw.directory) {
            e.setName(raw.name);
            return e;
        }
        e.setObjectKey(raw.objectName);
        e.setSize(raw.size);
        e.setModifiedAt(raw.lastModified);
        // 索引 enrich：source / fileName / mimeType / previewUrl
        SessionAttachmentVO att = index.get(raw.objectName);
        String displayName = resolveDisplayName(raw.name, att);
        e.setName(displayName);
        if (att != null) {
            e.setSource(att.getSource());
            if (att.getFileName() != null) {
                e.setFileName(att.getFileName());
            } else {
                e.setFileName(displayName);
            }
            if (att.getMimeType() != null) {
                e.setMimeType(att.getMimeType());
            } else {
                e.setMimeType(guessMimeFromName(raw.name));
            }
            if (att.getPreviewUrl() != null) {
                e.setPreviewUrl(att.getPreviewUrl());
            }
        } else {
            e.setSource("unknown");
            e.setFileName(displayName);
            e.setMimeType(guessMimeFromName(raw.name));
        }
        return e;
    }

    /**
     * 同目录下若多个文件展示名相同，为后续重复项追加 (1)、(2)… 后缀（首项保持原名）。
     */
    private static void disambiguateDuplicateDisplayNames(List<SessionFileEntryVO> entries) {
        Map<String, List<SessionFileEntryVO>> groups = new LinkedHashMap<>();
        for (SessionFileEntryVO e : entries) {
            if (Boolean.TRUE.equals(e.getDirectory())) {
                continue;
            }
            String display = e.getFileName() != null && !e.getFileName().isBlank()
                    ? e.getFileName() : e.getName();
            if (display == null || display.isBlank()) {
                continue;
            }
            groups.computeIfAbsent(display.toLowerCase(), k -> new ArrayList<>()).add(e);
        }
        for (List<SessionFileEntryVO> group : groups.values()) {
            if (group.size() <= 1) {
                continue;
            }
            for (int i = 1; i < group.size(); i++) {
                SessionFileEntryVO e = group.get(i);
                String original = e.getFileName() != null && !e.getFileName().isBlank()
                        ? e.getFileName() : e.getName();
                String unique = appendDuplicateSuffix(original, i);
                e.setName(unique);
                e.setFileName(unique);
            }
        }
    }

    /** Windows 风格：report.pdf → report (1).pdf */
    private static String appendDuplicateSuffix(String fileName, int index) {
        if (fileName == null || fileName.isBlank()) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            String stem = fileName.substring(0, dot);
            String ext = fileName.substring(dot);
            return stem + " (" + index + ")" + ext;
        }
        return fileName + " (" + index + ")";
    }

    /**
     * 优先使用 attachments 索引中的原名；否则从 Yuxi 风格 objectKey（{id}_{原名}）还原显示名。
     */
    private static String resolveDisplayName(String objectBaseName, SessionAttachmentVO att) {
        if (att != null && att.getFileName() != null && !att.getFileName().isBlank()) {
            return att.getFileName();
        }
        if (objectBaseName != null && objectBaseName.length() > 33 && objectBaseName.charAt(32) == '_') {
            String prefix = objectBaseName.substring(0, 32);
            if (prefix.matches("[0-9a-fA-F]{32}")) {
                return objectBaseName.substring(33);
            }
        }
        return objectBaseName;
    }

    private Map<String, SessionAttachmentVO> buildAttachmentIndex(Long sessionId) {
        List<SessionAttachmentVO> attachments = chatSessionService.getSessionAttachments(sessionId);
        Map<String, SessionAttachmentVO> map = new HashMap<>();
        if (attachments == null) {
            return map;
        }
        for (SessionAttachmentVO att : attachments) {
            if (att.getObjectKey() != null) {
                map.put(att.getObjectKey(), att);
            }
        }
        return map;
    }

    private SessionFileStatsVO computeStats(Long sessionId) {
        List<SessionAttachmentVO> attachments = chatSessionService.getSessionAttachments(sessionId);
        SessionFileStatsVO stats = new SessionFileStatsVO();
        if (attachments == null) {
            return stats;
        }
        int user = 0;
        int ai = 0;
        for (SessionAttachmentVO att : attachments) {
            String src = att.getSource();
            if (SessionAttachmentSource.USER_UPLOAD.getCode().equals(src)) {
                user++;
            } else if (src != null && src.startsWith("ai_")) {
                ai++;
            }
        }
        stats.setTotal(attachments.size());
        stats.setUserUpload(user);
        stats.setAiGenerated(ai);
        return stats;
    }

    private String normalizeRelativePath(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim().replace("\\", "/");
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.contains("..")) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "非法路径");
        }
        return p;
    }

    private void ensureAllowedPartition(String normalized) {
        if (normalized.isEmpty()) {
            return;
        }
        if (normalized.startsWith(SessionStoragePath.INPUTS_DIR + "/") || normalized.equals(SessionStoragePath.INPUTS_DIR)
                || normalized.startsWith(SessionStoragePath.OUTPUTS_DIR + "/") || normalized.equals(SessionStoragePath.OUTPUTS_DIR)
                || normalized.startsWith(SessionStoragePath.WORKSPACE_DIR + "/") || normalized.equals(SessionStoragePath.WORKSPACE_DIR)
                || normalized.startsWith("uploads/") || normalized.equals("uploads")) {
            return;
        }
        throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "路径不在允许的会话分区下");
    }

    private boolean isTopLevelPartition(String normalized) {
        return normalized.equals(SessionStoragePath.INPUTS_DIR)
                || normalized.equals(SessionStoragePath.OUTPUTS_DIR)
                || normalized.equals(SessionStoragePath.WORKSPACE_DIR)
                || normalized.equals("uploads");
    }

    private String detectPreviewType(String mime, String name) {
        // MinIO 常返回 application/octet-stream，需按扩展名推断真实 MIME
        mime = resolveMime(mime, name);
        if (mime.startsWith("image/")) {
            return "image";
        }
        if (mime.startsWith("video/")) {
            return "video";
        }
        if ("application/pdf".equals(mime)) {
            return "pdf";
        }
        if ("text/markdown".equals(mime) || name.toLowerCase().endsWith(".md")) {
            return "markdown";
        }
        if (TEXT_MIMES.contains(mime)) {
            return "text";
        }
        // 按扩展名兜底判定：MinIO 常返回 application/octet-stream，纯靠 MIME 判不出
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase();
            if (TEXT_EXTENSIONS.contains(ext)) {
                return "text";
            }
        }
        return "unsupported";
    }

    /** 优先使用有效 MIME；octet-stream 或空值时按文件名推断 */
    private String resolveMime(String mime, String name) {
        if (mime != null && !"application/octet-stream".equals(mime)) {
            return mime;
        }
        return guessMimeFromName(name);
    }

    private String guessMimeFromName(String name) {
        if (name == null) {
            return "application/octet-stream";
        }
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".txt") || lower.endsWith(".log")) return "text/plain";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return "application/octet-stream";
    }
}
