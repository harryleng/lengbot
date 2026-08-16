package com.lengbot.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.service.sandbox.SandboxFs;
import com.lengbot.service.sandbox.SandboxPath;
import com.lengbot.tool.annotation.SystemTool;
import com.lengbot.tool.annotation.ToolParamMeta;
import com.lengbot.util.MinioUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置工具 — 沙盒文件操作
 * <p>提供 Skill 只读访问和工作区读写能力。</p>
 * <p>工作区路径自动注入当前会话 ID，AI 只需传相对路径。</p>
 * <p>写入内容不设长度上限（对标 Yuxi：write 仅做路径/权限校验，正文原样透传）；
 * 需要续写时可用 {@code sandbox_append_file} 向同一路径追加。</p>
 *
 * @author finch
 * @since 2026-06-24
 */
@Slf4j
@Component("sandboxFileTool")
@RequiredArgsConstructor
@SystemTool(displayName = "沙盒文件操作", icon = "FolderOutlined", description = "在沙盒中读写文件，支持 Skill 只读访问和工作区读写",
        tags = {"file", "sandbox"})
public class SandboxFileTool {

    private final SandboxFs sandboxFs;
    private final ObjectMapper objectMapper;
    private final MinioUtil minioUtil;

    @Tool(name = "sandbox_read_file",
          description = "读取沙盒中的文件内容。两种路径模式：" +
                  "1) Skill 文件（只读）: skills/{skillSlug}/filename，如 skills/my-skill/SKILL.md。" +
                  "2) 工作区文件（读写）: 直接传相对路径如 output.txt 或 data/result.json，系统自动归属到当前会话工作区。")
    @SystemTool(displayName = "读取沙盒文件", tags = {"file", "sandbox", "read"})
    public String readFile(
            @ToolParam(name = "path", description = "文件路径。Skill 文件以 skills/ 开头（如 skills/my-skill/SKILL.md）；工作区文件传相对路径（如 output.txt）")
            @ToolParamMeta(example = "skills/my-skill/SKILL.md") String path,
            ToolCallParam toolContext) {
        log.info("[Tool:sandbox_read_file] path={}", path);
        if (path == null || path.isBlank()) {
            return errorJson("路径不能为空");
        }
        try {
            SandboxPath sandboxPath = resolvePath(path.trim(), toolContext);
            String content = sandboxFs.readFile(sandboxPath);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("path", path.trim());
            output.put("content", content);
            output.put("size", content.length());
            return toJson(output);
        } catch (Exception e) {
            return errorJson("读取文件失败: " + e.getMessage());
        }
    }

    @Tool(name = "sandbox_list_files",
          description = "列出沙盒目录中的文件。两种路径模式：" +
                  "1) Skill 目录（只读）: skills/{skillSlug}，如 skills/my-skill。" +
                  "2) 工作区目录（读写）: 直接传相对路径如 output 或 data，系统自动归属到当前会话工作区。" +
                  "不传 path 则列出当前会话工作区根目录。")
    @SystemTool(displayName = "列出沙盒文件", tags = {"file", "sandbox", "list"})
    public String listFiles(
            @ToolParam(name = "dirPath", description = "目录路径。Skill 目录以 skills/ 开头（如 skills/my-skill）；工作区目录传相对路径（如 data）。不传则列出工作区根目录")
            @ToolParamMeta(example = "skills/my-skill") String dirPath,
            ToolCallParam toolContext) {
        log.info("[Tool:sandbox_list_files] dirPath={}", dirPath);
        String path = (dirPath == null || dirPath.isBlank()) ? "" : dirPath.trim();
        try {
            SandboxPath sandboxPath = resolvePath(path, toolContext);
            List<String> files = sandboxFs.listFiles(sandboxPath);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("dirPath", path.isEmpty() ? "(工作区根目录)" : path);
            output.put("files", files);
            output.put("total", files.size());
            return toJson(output);
        } catch (Exception e) {
            return errorJson("列出文件失败: " + e.getMessage());
        }
    }

    @Tool(name = "sandbox_write_file",
          description = "写入（覆盖）文件到当前会话。路径含义：" +
                  "1) 工作区临时/中间文件：相对路径如 notes/draft.md；" +
                  "2) 交付给用户的最终产物：以 outputs/ 开头，如 outputs/reports/report.md，可再配合 present_artifacts。" +
                  "禁止写入 skills/。")
    @SystemTool(displayName = "写入沙盒文件", tags = {"file", "sandbox", "write"})
    public String writeFile(
            @ToolParam(name = "path", description = "相对路径，必须最先传入。工作区如 notes/draft.md；交付文件如 outputs/reports/report.md。不要传 skills/")
            @ToolParamMeta(example = "outputs/reports/report.md") String path,
            @ToolParam(name = "content", description = "文件内容（覆盖写入），在 path 之后传入")
            @ToolParamMeta(example = "# 标题\\n\\n摘要……") String content,
            ToolCallParam toolContext) {
        return doWrite(path, content, false, toolContext);
    }

    @Tool(name = "sandbox_append_file",
          description = "向已有文件追加内容（不存在则创建）。用于分次写入：" +
                  "先 sandbox_write_file 写开头，再多次 sandbox_append_file 追加后续章节。" +
                  "路径规则与 sandbox_write_file 相同。")
    @SystemTool(displayName = "追加沙盒文件", tags = {"file", "sandbox", "write", "append"})
    public String appendFile(
            @ToolParam(name = "path", description = "相对路径，必须最先传入，须与先前 write 使用同一路径")
            @ToolParamMeta(example = "outputs/reports/report.md") String path,
            @ToolParam(name = "content", description = "追加内容，在 path 之后传入")
            @ToolParamMeta(example = "\\n\\n## 第二节\\n……") String content,
            ToolCallParam toolContext) {
        return doWrite(path, content, true, toolContext);
    }

    private String doWrite(String path, String content, boolean append, ToolCallParam toolContext) {
        log.info("[Tool:sandbox_{}] path={}, contentLen={}",
                append ? "append_file" : "write_file", path, content != null ? content.length() : 0);
        if (path == null || path.isBlank()) {
            return errorJson("路径不能为空");
        }
        if (content == null) {
            content = "";
        }
        try {
            SandboxPath sandboxPath = resolvePath(path.trim(), toolContext);
            // 实际写入字节数：append 时为旧内容+新内容，overwrite 时为新内容
            long writeBytes;
            if (append) {
                String existing = "";
                try {
                    existing = sandboxFs.readFile(sandboxPath);
                } catch (Exception ignored) {
                    // 文件不存在则当作空文件追加
                }
                String merged = existing + content;
                sandboxFs.writeFile(sandboxPath, merged);
                writeBytes = merged.getBytes().length;
            } else {
                sandboxFs.writeFile(sandboxPath, content);
                writeBytes = content.getBytes().length;
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("path", path.trim());
            output.put("size", writeBytes);
            output.put("success", true);
            output.put("mode", append ? "append" : "overwrite");
            // outputs/ 路径：交付物，附预签名 URL 让前端直接渲染文件卡片
            String normalized = path.trim().replace("\\", "/");
            if (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            if (normalized.startsWith("outputs/")) {
                String contentType = inferContentType(normalized);
                String name = normalized.contains("/")
                        ? normalized.substring(normalized.lastIndexOf('/') + 1)
                        : normalized;
                String minioPath = sandboxPath.toMinioPath();
                try {
                    output.put("name", name);
                    output.put("contentType", contentType);
                    output.put("url", minioUtil.getPresignedUrl(minioPath, contentType));
                    output.put("downloadUrl", minioUtil.getPresignedDownloadUrl(minioPath, name, contentType));
                } catch (Exception ex) {
                    // 预签名失败不影响写入结果，只记日志
                    log.warn("[Tool:sandbox_{}] 生成预签名URL失败: path={}, error={}",
                            append ? "append_file" : "write_file", path, ex.getMessage());
                }
            }
            return toJson(output);
        } catch (Exception e) {
            return errorJson((append ? "追加" : "写入") + "文件失败: " + e.getMessage());
        }
    }

    private String inferContentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "application/msword";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "application/vnd.ms-excel";
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        return "application/octet-stream";
    }

    /**
     * 解析路径：skills/ 开头走 Skill 路径，outputs/ 开头走 AI 产出区，其余自动归属到当前会话工作区
     */
    private SandboxPath resolvePath(String path, ToolCallParam toolContext) {
        String normalized = path.replace("\\", "/");
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        // Skill 路径：skills/{slug}/...（只读）
        if (normalized.startsWith("skills/")) {
            String relative = normalized.substring("skills/".length());
            if (relative.isBlank()) {
                throw new IllegalArgumentException("Skill 路径不完整，需指定文件或目录");
            }
            return new SandboxPath(SandboxPath.PathType.SKILL, relative);
        }
        String sessionId = extractSessionId(toolContext);
        // AI 产出区：outputs/...（读写，用于交付物）
        if (normalized.startsWith("outputs/")) {
            String relative = normalized.substring("outputs/".length());
            return SandboxPath.output(sessionId, relative);
        }
        // 工作区路径：自动注入 sessionId
        return SandboxPath.workspace(sessionId, normalized);
    }

    private String extractSessionId(ToolCallParam toolContext) {
        if (toolContext != null) {
            Object sid = toolContext.getRuntimeContext().get("sessionId");
            if (sid != null) {
                return String.valueOf(sid);
            }
        }
        return "default";
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"序列化失败\"}";
        }
    }

    private String errorJson(String message) {
        return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
    }
}
