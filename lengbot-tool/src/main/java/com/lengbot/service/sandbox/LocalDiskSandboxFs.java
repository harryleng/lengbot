package com.lengbot.service.sandbox;

import com.lengbot.util.SandboxPathValidator;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 沙盒虚拟文件系统 — 本地磁盘实现（backend=local 时启用）
 * <p>磁盘布局与 MinIO 对象键布局完全一致（{@link SandboxPath#toMinioPath()} 直接映射为相对路径）：</p>
 * <pre>
 * {lengbot.sandbox.local-root}/
 * ├── skills/{slug}/xxx                只读（从本地挂载的 Skill 目录读取）
 * └── sessions/{sessionId}/
 *     ├── inputs/                      用户上传（只读引用）
 *     ├── workspace/                   工作区（读写）
 *     └── outputs/                     AI 交付物（读写，下载走 /api/sandbox/files/...）
 * </pre>
 *
 * <p>实现借鉴 AgentScope {@code JsonFileAgentStateStore} 的已验证模式：</p>
 * <ul>
 *   <li>原子写：临时文件 + {@code Files.move(ATOMIC_MOVE, REPLACE_EXISTING)}，杜绝半截文件</li>
 *   <li>UTF-8 替换编码器：非法字符替换为 U+FFFD 而非抛异常，保证写入永不失败</li>
 *   <li>{@code APPEND} 打开实现原子追加，避免读-拼-写竞态</li>
 * </ul>
 *
 * @author lw
 * @since 2026-08-21
 */
@Slf4j
public class LocalDiskSandboxFs implements SandboxFs {

    /** 单次读入内存上限（对齐 MinIO 版 {@code MinioUtil.MAX_BYTES_FOR_IN_MEMORY}） */
    private static final long MAX_BYTES_FOR_IN_MEMORY = 10 * 1024 * 1024L;

    /** 替换字符：U+FFFD（UTF-8 三字节 EF BF BD） */
    private static final byte[] REPLACEMENT = new byte[]{(byte) 0xEF, (byte) 0xBF, (byte) 0xBD};

    private final Path rootDirectory;
    /** 外部访问 base URL（默认空串=同源相对路径），用于拼接本地下载接口 URL */
    private final String publicBaseUrl;

    public LocalDiskSandboxFs(Path rootDirectory) {
        this(rootDirectory, "");
    }

    public LocalDiskSandboxFs(Path rootDirectory, String publicBaseUrl) {
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl == null ? "" : stripTrailingSlash(publicBaseUrl);
        try {
            Files.createDirectories(this.rootDirectory);
        } catch (IOException e) {
            throw new RuntimeException("创建沙盒根目录失败: " + this.rootDirectory, e);
        }
    }

    @Override
    public String readFile(SandboxPath path) {
        return new String(readBytes(path), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] readBytes(SandboxPath path) {
        String rel = path.toMinioPath();
        SandboxPathValidator.checkReadable(rel);
        Path target = resolveChecked(rel);
        try {
            if (!Files.isRegularFile(target)) {
                throw new java.nio.file.NoSuchFileException(target.toString());
            }
            long size = Files.size(target);
            if (size > MAX_BYTES_FOR_IN_MEMORY) {
                throw new IllegalStateException("文件超过内存读取上限(10MB): " + rel + " (" + size + " bytes)");
            }
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new RuntimeException("本地沙盒读取失败: " + rel, e);
        }
    }

    @Override
    public void writeFile(SandboxPath path, String content) {
        if (path.type() == SandboxPath.PathType.SKILL) {
            throw new UnsupportedOperationException("Skill 目录为只读，不可写入");
        }
        String rel = path.toMinioPath();
        SandboxPathValidator.checkWritable(rel);
        Path target = resolveChecked(rel);
        try {
            Files.createDirectories(target.getParent());
            atomicWriteString(target, content);
        } catch (IOException e) {
            throw new RuntimeException("本地沙盒写入失败: " + rel, e);
        }
    }

    @Override
    public void appendFile(SandboxPath path, String content) {
        if (path.type() == SandboxPath.PathType.SKILL) {
            throw new UnsupportedOperationException("Skill 目录为只读，不可追加");
        }
        String rel = path.toMinioPath();
        SandboxPathValidator.checkWritable(rel);
        Path target = resolveChecked(rel);
        try {
            Files.createDirectories(target.getParent());
            // O_APPEND 单次 write 原子：不存在则创建，存在则追加，并发下不交错
            try (BufferedWriter writer = newUtf8ReplacingWriter(
                    target, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(content);
            }
        } catch (IOException e) {
            throw new RuntimeException("本地沙盒追加失败: " + rel, e);
        }
    }

    @Override
    public List<String> listFiles(SandboxPath path) {
        String rel = path.toMinioPath();
        SandboxPathValidator.checkReadable(rel);
        Path target = resolveChecked(rel);
        if (!Files.isDirectory(target)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        // 非递归列举：只返回直接子条目（目录以 / 结尾），避免海量递归结果
        try (Stream<Path> entries = Files.list(target)) {
            entries.forEach(p -> {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    result.add(name + "/");
                } else {
                    result.add(name);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("本地沙盒列举失败: " + rel, e);
        }
        return result;
    }

    @Override
    public void deleteFile(SandboxPath path) {
        if (path.type() == SandboxPath.PathType.SKILL) {
            throw new UnsupportedOperationException("Skill 目录为只读，不可删除");
        }
        String rel = path.toMinioPath();
        SandboxPathValidator.checkWritable(rel);
        Path target = resolveChecked(rel);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new RuntimeException("本地沙盒删除失败: " + rel, e);
        }
    }

    @Override
    public boolean fileExists(SandboxPath path) {
        String rel = path.toMinioPath();
        SandboxPathValidator.checkReadable(rel);
        Path target = resolveChecked(rel);
        return Files.isRegularFile(target);
    }

    @Override
    public SandboxFileAccess resolveFileAccess(SandboxPath path, String contentType) {
        String rel = path.toMinioPath();
        SandboxPathValidator.checkReadable(rel);
        Path target = resolveChecked(rel);
        String name = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
        long size = 0;
        try {
            if (Files.isRegularFile(target)) {
                size = Files.size(target);
            }
        } catch (IOException e) {
            log.debug("[SandboxFs:Local] 获取文件大小失败: path={}, error={}", rel, e.getMessage());
        }
        String base = publicBaseUrl + "/api/sandbox/files/";
        String url = base + rel;
        String downloadUrl = base + rel + "?attachment=1";
        return new SandboxFileAccess(name, url, downloadUrl, size, contentType);
    }

    /** 解析并校验目标路径：normalize 后必须仍位于沙盒根目录内（纵深防御） */
    private Path resolveChecked(String rel) {
        Path root = rootDirectory.normalize();
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            throw new SecurityException("沙盒路径越界: " + rel);
        }
        return target;
    }

    /** 原子写：tmp 文件 + ATOMIC_MOVE，防半截文件 */
    private static void atomicWriteString(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter writer = newUtf8ReplacingWriter(
                tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writer.write(content);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** UTF-8 替换编码器：非法字符替换为 U+FFFD，而非抛异常 */
    private static BufferedWriter newUtf8ReplacingWriter(Path file, StandardOpenOption... options) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(file, options), utf8ReplacingEncoder()));
    }

    private static CharsetEncoder utf8ReplacingEncoder() {
        return StandardCharsets.UTF_8
                .newEncoder()
                .replaceWith(REPLACEMENT)
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    private static String stripTrailingSlash(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
