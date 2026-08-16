package com.lengbot.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.service.sandbox.SandboxFs;
import com.lengbot.service.sandbox.SandboxPath;
import com.lengbot.tool.ToolEventEmitter;
import com.lengbot.tool.annotation.SystemTool;
import com.lengbot.tool.annotation.ToolParamMeta;
import com.lengbot.util.OcrUtil;
import com.lengbot.util.SessionStoragePath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 内置工具 — OCR 解析文件
 * <p>将沙盒中的 PDF / 图片文件解析为文本（复用知识库的 RapidOCR 引擎 {@link OcrUtil}），
 * 结果写入当前会话工作区 ocr/ 目录，仅返回结果文件路径与短预览，不直接返回完整文本。</p>
 *
 * @author finch
 * @since 2026-07-04
 */
@Slf4j
@Component("ocrParseFileTool")
@RequiredArgsConstructor
@SystemTool(displayName = "OCR 解析文件", icon = "ScanOutlined", description = "将沙盒中的 PDF/图片文件 OCR 解析为文本并保存为文件", tags = {"文件", "OCR"},
        outputExample = "{\"source_path\":\"invoice.pdf\",\"parsed_path\":\"ocr/invoice.md\",\"char_count\":1280,\"preview\":\"识别文本前1200字...\",\"truncated\":true}",
        outputSchema = "{\"type\":\"object\",\"properties\":{\"source_path\":{\"type\":\"string\",\"description\":\"源文件路径\"},\"parsed_path\":{\"type\":\"string\",\"description\":\"解析结果 Markdown 文件路径（工作区相对路径）\"},\"char_count\":{\"type\":\"integer\",\"description\":\"识别文本字符数\"},\"preview\":{\"type\":\"string\",\"description\":\"识别文本预览（最多1200字）\"},\"truncated\":{\"type\":\"boolean\",\"description\":\"预览是否被截断\"}}}")
public class OcrParseFileTool {

    /** 预览最大字符数 */
    private static final int PREVIEW_LIMIT = 1200;

    /** 支持 OCR 的图片扩展名 */
    private static final Set<String> IMAGE_EXTS = Set.of("jpg", "jpeg", "png", "bmp", "tiff", "tif");

    private final SandboxFs sandboxFs;
    private final OcrUtil ocrUtil;
    private final ObjectMapper objectMapper;

    @Tool(name = "ocr_parse_file",
          description = "将沙盒中的 PDF 或图片文件 OCR 解析为文本。" +
                  "使用场景：用户上传了 PDF/图片附件需要提取文字，或工作区中已有此类文件需转为可读文本。" +
                  "path 传文件名或相对路径即可（如 期音端午通知.pdf、data/scan.png）：" +
                  "系统自动在当前会话中定位——优先工作区，未找到则在用户上传目录按文件名匹配；" +
                  "也可显式指定 outputs/、inputs/、workspace/ 前缀。支持 pdf/jpg/jpeg/png/bmp/tiff。" +
                  "结果会写入工作区 ocr/ 目录，工具只返回结果文件路径和短预览，完整文本请用 sandbox_read_file 读取结果文件。")
    public String parseFile(
            @ToolParam(name = "path", description = "待解析的文件名或沙盒相对路径。直接传用户上传的文件名即可（如 report.pdf），系统自动在当前会话定位")
            @ToolParamMeta(example = "invoice.pdf") String path,
            ToolCallParam toolContext) {
        log.info("[Tool:ocr_parse_file] path={}", path);
        if (path == null || path.isBlank()) {
            return errorJson("路径不能为空");
        }

        String normalized = normalize(path.trim());
        String ext = extension(normalized);
        boolean isPdf = "pdf".equals(ext);
        boolean isImage = IMAGE_EXTS.contains(ext);
        if (!isPdf && !isImage) {
            return errorJson("不支持的文件类型: " + (ext.isEmpty() ? "(无扩展名)" : ext) + "，仅支持 pdf/jpg/jpeg/png/bmp/tiff");
        }

        try {
            String sessionId = extractSessionId(toolContext);

            // 1. 读取源文件字节
            ToolEventEmitter.emit("正在读取文件「" + normalized + "」...");
            byte[] bytes = sandboxFs.readBytes(resolveSource(normalized, sessionId));

            // 2. OCR 识别（复用知识库 RapidOCR 引擎）
            ToolEventEmitter.emit("正在 OCR 识别...");
            String text;
            try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
                text = isPdf
                        ? ocrUtil.recognizePdf(in, (current, total) ->
                                ToolEventEmitter.emit("正在识别第 " + current + "/" + total + " 页..."))
                        : ocrUtil.recognizeImage(in);
            }
            if (text == null) {
                text = "";
            }

            // 3. 结果写入工作区 ocr/{stem}.md
            String outputRelative = "ocr/" + safeStem(normalized) + ".md";
            sandboxFs.writeFile(SandboxPath.workspace(sessionId, outputRelative), text);
            ToolEventEmitter.emit("识别完成，共 " + text.length() + " 字，已保存至 " + outputRelative);

            // 4. 组装结果（仅返回路径和预览，避免完整文本占用上下文）
            boolean truncated = text.length() > PREVIEW_LIMIT;
            String preview = truncated ? text.substring(0, PREVIEW_LIMIT).stripTrailing() : text;

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("source_path", normalized);
            output.put("parsed_path", outputRelative);
            output.put("char_count", text.length());
            output.put("preview", preview);
            output.put("truncated", truncated);
            return toJson(output);
        } catch (Exception e) {
            log.error("[Tool:ocr_parse_file] 解析失败, path={}", normalized, e);
            return errorJson("OCR 解析失败: " + e.getMessage());
        }
    }

    /**
     * 解析源文件路径（智能定位当前会话下的文件）：
     * <p>1) skills/ 只读区不支持；2) 显式前缀 outputs/、inputs/、workspace/ 按对应区解析；
     * 3) 裸相对路径优先在 workspace/ 精确匹配，未命中则回退到 inputs/（用户上传区）
     * 按文件名匹配——上传文件带 {attachmentId}_ 前缀，故用后缀匹配。</p>
     */
    private SandboxPath resolveSource(String normalized, String sessionId) {
        if (normalized.startsWith("skills/")) {
            throw new IllegalArgumentException("不支持解析 skills/ 只读目录下的文件");
        }
        if (normalized.startsWith("outputs/")) {
            return SandboxPath.output(sessionId, normalized.substring("outputs/".length()));
        }
        if (normalized.startsWith("inputs/")) {
            return SandboxPath.input(sessionId, normalized.substring("inputs/".length()));
        }
        if (normalized.startsWith("workspace/")) {
            return SandboxPath.workspace(sessionId, normalized.substring("workspace/".length()));
        }
        // 裸相对路径：先 workspace 精确匹配，未命中回退 inputs 按文件名定位
        SandboxPath workspacePath = SandboxPath.workspace(sessionId, normalized);
        if (sandboxFs.fileExists(workspacePath)) {
            return workspacePath;
        }
        SandboxPath inputPath = findInInputs(sessionId, normalized);
        return inputPath != null ? inputPath : workspacePath;
    }

    /**
     * 在会话 inputs/ 目录按文件名定位上传文件（上传对象名形如 {attachmentId}_{文件名}）。
     *
     * @return 命中的 INPUT 路径，未命中返回 null
     */
    private SandboxPath findInInputs(String sessionId, String normalized) {
        String fileName = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1)
                : normalized;
        String inputsPrefix = "sessions/" + sessionId + "/" + SessionStoragePath.INPUTS_DIR + "/";
        for (String objectName : sandboxFs.listFiles(SandboxPath.input(sessionId, ""))) {
            String base = objectName.substring(objectName.lastIndexOf('/') + 1);
            if (base.equals(fileName) || base.endsWith("_" + fileName)) {
                return SandboxPath.input(sessionId, objectName.substring(inputsPrefix.length()));
            }
        }
        return null;
    }

    /**
     * 归一化路径：统一分隔符、去除前导斜杠
     */
    private String normalize(String path) {
        String normalized = path.replace("\\", "/");
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * 提取小写扩展名，无扩展名返回空串
     */
    private String extension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase();
    }

    /**
     * 生成安全的输出文件名主干（去扩展名 + 过滤非法字符）
     */
    private String safeStem(String path) {
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String safe = stem.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]+", "_").replaceAll("^[._-]+|[._-]+$", "");
        return safe.isBlank() ? "ocr_result" : safe;
    }

    /**
     * 从 ToolCallParam 提取 sessionId
     */
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
