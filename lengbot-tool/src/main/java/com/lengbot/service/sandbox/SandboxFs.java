package com.lengbot.service.sandbox;

import java.util.List;

/**
 * 沙盒虚拟文件系统接口
 * <p>统一 Skill 只读区 + 工作区读写区的文件操作。路径解析与权限校验对 AI 透明，
 * 底层存储可切换：MinIO 对象存储（{@link MinioSandboxFs}）或本地磁盘（{@link LocalDiskSandboxFs}）。</p>
 *
 * <p>实现约定：</p>
 * <ul>
 *   <li>读操作：{@code SandboxPathValidator.checkReadable}（仅 skills/ 与 sessions/）</li>
 *   <li>写操作：{@code SandboxPathValidator.checkWritable}（仅 sessions/），且 SKILL 类型显式拒绝</li>
 *   <li>所有路径统一以 {@link SandboxPath#toMinioPath()} 的相对形式（如 sessions/123/workspace/x.md）传输</li>
 * </ul>
 *
 * @author lw
 * @since 2026-06-24
 */
public interface SandboxFs {

    /**
     * 读取文件文本（UTF-8）
     *
     * @param path 沙盒路径
     * @return 文件内容
     */
    String readFile(SandboxPath path);

    /**
     * 读取文件原始字节（用于 PDF/图片等二进制文件）
     *
     * @param path 沙盒路径
     * @return 文件字节
     */
    byte[] readBytes(SandboxPath path);

    /**
     * 写入文件（覆盖，仅工作区）。Skill 目录为只读，写入抛 {@link UnsupportedOperationException}。
     *
     * @param path    沙盒路径
     * @param content 文件内容
     */
    void writeFile(SandboxPath path, String content);

    /**
     * 向已有文件追加内容（不存在则创建，仅工作区）。
     * <p>实现需保证追加的原子性/并发安全：本地磁盘用 {@code APPEND} 打开；MinIO 无原生追加语义，
     * 采用「存在则读-拼-写、不存在则直接创建」策略，读失败时抛异常而不是静默覆盖。</p>
     *
     * @param path    沙盒路径
     * @param content 追加内容
     */
    void appendFile(SandboxPath path, String content);

    /**
     * 列出目录下的直接文件/子目录名（相对沙盒根的完整路径，与实现存储的前缀格式一致）
     *
     * @param path 沙盒路径（目录）
     * @return 文件相对路径列表
     */
    List<String> listFiles(SandboxPath path);

    /**
     * 删除文件（仅工作区）。Skill 目录为只读，删除抛 {@link UnsupportedOperationException}。
     *
     * @param path 沙盒路径
     */
    void deleteFile(SandboxPath path);

    /**
     * 检查文件是否存在
     *
     * @param path 沙盒路径
     * @return 是否存在
     */
    boolean fileExists(SandboxPath path);

    /**
     * 解析交付文件的访问信息（内联预览 URL / 下载 URL / 大小）。
     * <p>MinIO 实现返回预签名 URL；本地磁盘实现返回 {@code /api/sandbox/files/...} 下载接口 URL。</p>
     *
     * @param path        沙盒路径（通常为 OUTPUT 类型）
     * @param contentType 期望的 MIME 类型，为 null 时由实现推断
     * @return 访问信息
     */
    SandboxFileAccess resolveFileAccess(SandboxPath path, String contentType);
}
