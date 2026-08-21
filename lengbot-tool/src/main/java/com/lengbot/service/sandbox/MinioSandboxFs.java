package com.lengbot.service.sandbox;

import com.lengbot.util.MinioUtil;
import com.lengbot.util.SandboxPathValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 沙盒虚拟文件系统 — MinIO 对象存储实现（默认后端）
 * <p>文件对象键布局与 {@link SandboxPath#toMinioPath()} 一致：</p>
 * <pre>
 * skills/{slug}/xxx              只读
 * sessions/{sessionId}/inputs/   用户上传（只读引用）
 * sessions/{sessionId}/workspace/ 工作区（读写）
 * sessions/{sessionId}/outputs/   AI 交付物（读写）
 * </pre>
 *
 * @author lw
 * @since 2026-06-24
 */
@Slf4j
@RequiredArgsConstructor
public class MinioSandboxFs implements SandboxFs {

    private final MinioUtil minioUtil;

    @Override
    public String readFile(SandboxPath path) {
        String minioPath = path.toMinioPath();
        SandboxPathValidator.checkReadable(minioPath);
        byte[] bytes = minioUtil.downloadBytes(minioPath);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public byte[] readBytes(SandboxPath path) {
        String minioPath = path.toMinioPath();
        SandboxPathValidator.checkReadable(minioPath);
        return minioUtil.downloadBytes(minioPath);
    }

    @Override
    public void writeFile(SandboxPath path, String content) {
        if (path.type() == SandboxPath.PathType.SKILL) {
            throw new UnsupportedOperationException("Skill 目录为只读，不可写入");
        }
        String minioPath = path.toMinioPath();
        SandboxPathValidator.checkWritable(minioPath);
        minioUtil.uploadString(content, minioPath, "application/octet-stream");
    }

    @Override
    public void appendFile(SandboxPath path, String content) {
        if (path.type() == SandboxPath.PathType.SKILL) {
            throw new UnsupportedOperationException("Skill 目录为只读，不可追加");
        }
        String minioPath = path.toMinioPath();
        SandboxPathValidator.checkWritable(minioPath);
        // MinIO 对象不可变，无原生追加语义：存在则读-拼-写，不存在则直接创建。
        // 与旧实现的区别：只有「确认不存在」才按空文件追加；存在但读失败会抛异常，而不是静默覆盖。
        String existing = "";
        if (fileExists(path)) {
            existing = readFile(path);
        }
        minioUtil.uploadString(existing + content, minioPath, "application/octet-stream");
    }

    @Override
    public List<String> listFiles(SandboxPath path) {
        String minioPath = path.toMinioPath();
        SandboxPathValidator.checkReadable(minioPath);
        return minioUtil.listObjects(minioPath);
    }

    @Override
    public void deleteFile(SandboxPath path) {
        if (path.type() == SandboxPath.PathType.SKILL) {
            throw new UnsupportedOperationException("Skill 目录为只读，不可删除");
        }
        String minioPath = path.toMinioPath();
        SandboxPathValidator.checkWritable(minioPath);
        minioUtil.delete(minioPath);
    }

    @Override
    public boolean fileExists(SandboxPath path) {
        String minioPath = path.toMinioPath();
        SandboxPathValidator.checkReadable(minioPath);
        return minioUtil.exists(minioPath);
    }

    @Override
    public SandboxFileAccess resolveFileAccess(SandboxPath path, String contentType) {
        String minioPath = path.toMinioPath();
        SandboxPathValidator.checkReadable(minioPath);
        String name = minioPath.contains("/")
                ? minioPath.substring(minioPath.lastIndexOf('/') + 1)
                : minioPath;
        String url = minioUtil.getPresignedUrl(minioPath, contentType);
        String downloadUrl = minioUtil.getPresignedDownloadUrl(minioPath, name, contentType);
        long size = 0;
        try {
            size = minioUtil.statObject(minioPath).size();
        } catch (Exception e) {
            log.debug("[SandboxFs:MinIO] 获取对象大小失败: path={}, error={}", minioPath, e.getMessage());
        }
        return new SandboxFileAccess(name, url, downloadUrl, size, contentType);
    }
}
