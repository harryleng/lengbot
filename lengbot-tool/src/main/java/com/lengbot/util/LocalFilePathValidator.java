package com.lengbot.util;

import java.nio.file.Path;

/**
 * 本地文件访问路径安全校验：限制 Agent 只能访问白名单根目录内的文件，
 * 防止通过 ".." 遍历或绝对路径越界读取本机其它位置。
 *
 * @author lw
 * @since 2026-08-21
 */
public final class LocalFilePathValidator {

    private LocalFilePathValidator() {
    }

    /**
     * 将用户传入的相对路径解析为白名单根目录内的绝对路径。
     *
     * @param path 用户传入的路径（相对白名单根目录；可为空表示根目录本身）
     * @param root 白名单根目录（应为绝对、规范化路径）
     * @return 解析后的安全绝对路径
     * @throws SecurityException 路径包含遍历片段（..）或解析结果越出白名单根目录
     */
    public static Path resolve(String path, Path root) {
        String normalized = (path == null ? "" : path).replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        // 按 / 分段检查，拒绝包含 ".." 的段（纵深防御路径遍历）
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new SecurityException("本地文件路径遍历不允许: " + path);
            }
        }
        Path target = root.resolve(normalized).normalize();
        if (!target.startsWith(root)) {
            throw new SecurityException("本地文件路径越界（不在白名单内）: " + path);
        }
        return target;
    }
}
