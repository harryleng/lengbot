package com.lengbot.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.constant.ToolResultPrefixes;
import com.lengbot.service.sandbox.SandboxFileAccess;
import com.lengbot.service.sandbox.SandboxFs;
import com.lengbot.service.sandbox.SandboxPath;
import com.lengbot.tool.ToolEventEmitter;
import com.lengbot.tool.annotation.SystemTool;
import com.lengbot.tool.annotation.ToolParamMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置工具 — 文件交付
 * <p>将工作区中生成的文件交付给用户，返回下载链接（MinIO 后端=预签名 URL，
 * 本地磁盘后端=/api/sandbox/files/... 下载接口）。前端可据此渲染文件卡片（图片预览 / 文档下载）。</p>
 *
 * @author lw
 * @since 2026-06-25
 */
@Slf4j
@Component("presentArtifactsTool")
@RequiredArgsConstructor
@SystemTool(displayName = "文件交付", icon = "FileDoneOutlined", description = "将工作区中生成的文件交付给用户，支持图片预览和文档下载",
        tags = {"file", "交付"},
        outputExample = "{\"success\":true,\"artifacts\":[{\"name\":\"report.pdf\",\"path\":\"output/report.pdf\",\"url\":\"https://...\",\"size\":102400,\"contentType\":\"application/pdf\"}]}",
        outputSchema = "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"artifacts\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},\"url\":{\"type\":\"string\"},\"size\":{\"type\":\"integer\"},\"contentType\":{\"type\":\"string\"}}}}}}")
public class PresentArtifactsTool {

    private final SandboxFs sandboxFs;
    private final ObjectMapper objectMapper;

    @Tool(name = "present_artifacts",
          description = "将 outputs/ 目录下生成的文件交付给用户。传入文件路径列表（必须以 outputs/ 开头，如 outputs/files/report.pdf），" +
                  "系统会验证文件存在并生成下载链接，前端将展示为文件卡片（支持图片预览、文档下载）。" +
                  "仅支持 outputs/ 目录下文件，不支持 Skill 只读文件和工作区临时文件。")
    public String presentArtifacts(
            @ToolParam(name = "filepaths", description = "文件路径列表，使用相对路径如 [\"output/report.pdf\", \"data/chart.png\"]")
            @ToolParamMeta(example = "[\"output/report.pdf\"]") List<String> filepaths,
            ToolCallParam toolContext) {
        log.info("[Tool:present_artifacts] 交付文件: paths={}", filepaths);

        if (filepaths == null || filepaths.isEmpty()) {
            return ToolResultPrefixes.failureJson("文件路径列表不能为空");
        }

        String sessionId = extractSessionId(toolContext);
        List<Map<String, Object>> artifacts = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        ToolEventEmitter.emit("正在准备文件交付...");

        for (String filepath : filepaths) {
            if (filepath == null || filepath.isBlank()) {
                errors.add("空路径");
                continue;
            }
            try {
                String normalized = filepath.trim().replace("\\", "/");
                if (normalized.startsWith("/")) {
                    normalized = normalized.substring(1);
                }

                // 1. 禁止 Skill 路径
                if (normalized.startsWith("skills/")) {
                    errors.add(normalized + "（Skill 文件不可交付）");
                    continue;
                }

                // 2. 仅允许 outputs/ 下的文件作为交付物（对齐 Yuxi present_artifacts）
                if (!normalized.startsWith("outputs/")) {
                    errors.add(normalized + "（仅 outputs/ 目录下的文件可交付，请先用 sandbox_write_file 写入到 outputs/ 下）");
                    continue;
                }

                // 3. 构建 outputs 路径并验证存在
                String outputsRelative = normalized.substring("outputs/".length());
                SandboxPath sandboxPath = SandboxPath.output(sessionId, outputsRelative);
                if (!sandboxFs.fileExists(sandboxPath)) {
                    errors.add(normalized + "（文件不存在）");
                    continue;
                }

                // 4. 推断 content type 与文件名
                String contentType = inferContentType(normalized);
                String name = normalized.contains("/")
                        ? normalized.substring(normalized.lastIndexOf('/') + 1)
                        : normalized;

                // 5. 解析访问信息（URL/下载URL/大小，由 SandboxFs 后端实现提供）
                SandboxFileAccess access = sandboxFs.resolveFileAccess(sandboxPath, contentType);

                Map<String, Object> artifact = new LinkedHashMap<>();
                artifact.put("name", name);
                artifact.put("path", normalized);
                artifact.put("url", access.url());
                artifact.put("downloadUrl", access.downloadUrl());
                artifact.put("size", access.size());
                artifact.put("contentType", contentType);
                artifacts.add(artifact);

                log.info("[Tool:present_artifacts] 文件就绪: path={}, size={}", normalized, access.size());
            } catch (Exception e) {
                log.warn("[Tool:present_artifacts] 处理文件失败: path={}, error={}", filepath, e.getMessage());
                errors.add(filepath + "（" + e.getMessage() + "）");
            }
        }

        // 构建结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", !artifacts.isEmpty());
        result.put("artifacts", artifacts);
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        result.put("total", artifacts.size());

        ToolEventEmitter.emit("文件交付完成，共 " + artifacts.size() + " 个文件");

        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return ToolResultPrefixes.failureJson("序列化失败: " + e.getMessage());
        }
    }

    private String extractSessionId(ToolCallParam toolContext) {
        if (toolContext != null && toolContext.getRuntimeContext() != null) {
            Object sid = toolContext.getRuntimeContext().get("sessionId");
            if (sid != null) {
                return String.valueOf(sid);
            }
        }
        return "default";
    }

    private String inferContentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        // OOXML 与 OLE2 复合文档的 MIME 不同，需分开判断
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
