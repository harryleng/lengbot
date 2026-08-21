package com.lengbot.service.sandbox;

import com.lengbot.util.SessionStoragePath;

/**
 * 沙盒路径：统一路径表示 + 类型安全
 *
 * @param type         路径类型
 * @param relativePath 相对路径（不含类型前缀）
 * @author lw
 * @since 2026-06-24
 */
public record SandboxPath(PathType type, String relativePath) {

    public enum PathType {
        /** skills/{slug}/xxx（只读） */
        SKILL,
        /** sessions/{sessionId}/inputs/xxx（用户上传，工具只读引用） */
        INPUT,
        /** sessions/{sessionId}/workspace/xxx（读写） */
        WORKSPACE,
        /** sessions/{sessionId}/outputs/xxx（读写，AI 交付物） */
        OUTPUT
    }

    /**
     * 构建 Skill 路径
     *
     * @param slug         Skill 标识
     * @param relativePath 相对路径（如 SKILL.md、scripts/xxx.js）
     */
    public static SandboxPath skill(String slug, String relativePath) {
        return new SandboxPath(PathType.SKILL, slug + "/" + relativePath);
    }

    /**
     * 构建工作区路径（MinIO 落在 sessions/{sessionId}/workspace/{relativePath}）。
     *
     * @param sessionId    会话 ID
     * @param relativePath 相对路径（如 output.txt、data/result.json）
     */
    public static SandboxPath workspace(String sessionId, String relativePath) {
        return new SandboxPath(PathType.WORKSPACE, sessionId + "/" + SessionStoragePath.WORKSPACE_DIR + "/" + relativePath);
    }

    /**
     * 构建用户上传路径（MinIO 落在 sessions/{sessionId}/inputs/{relativePath}）。
     *
     * @param sessionId    会话 ID
     * @param relativePath 相对 inputs/ 的路径（如 {attachmentId}_报告.pdf）
     */
    public static SandboxPath input(String sessionId, String relativePath) {
        return new SandboxPath(PathType.INPUT, sessionId + "/" + SessionStoragePath.INPUTS_DIR + "/" + relativePath);
    }

    /**
     * 构建 AI 产出路径（MinIO 落在 sessions/{sessionId}/outputs/{relativePath}）。
     *
     * @param sessionId    会话 ID
     * @param relativePath 相对 outputs/ 的路径（如 files/report.pdf）
     */
    public static SandboxPath output(String sessionId, String relativePath) {
        return new SandboxPath(PathType.OUTPUT, sessionId + "/" + SessionStoragePath.OUTPUTS_DIR + "/" + relativePath);
    }

    /**
     * 转换为 MinIO 完整路径
     */
    public String toMinioPath() {
        return switch (type) {
            case SKILL -> "skills/" + relativePath;
            case INPUT, WORKSPACE, OUTPUT -> "sessions/" + relativePath;
        };
    }
}
