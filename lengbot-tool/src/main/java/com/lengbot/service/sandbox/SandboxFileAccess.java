package com.lengbot.service.sandbox;

/**
 * 沙盒交付文件的访问信息
 *
 * @param name        文件名（不含目录）
 * @param url         内联预览 URL（inline）
 * @param downloadUrl 下载 URL（attachment）
 * @param size        文件大小（字节）
 * @param contentType MIME 类型
 * @author lw
 * @since 2026-08-21
 */
public record SandboxFileAccess(String name, String url, String downloadUrl, long size, String contentType) {
}
