package com.lengbot.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.tool.annotation.SystemTool;
import com.lengbot.tool.annotation.ToolParamMeta;
import com.lengbot.util.LocalFilePathValidator;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 内置工具 — 受控本地文件访问（增删改查）
 * <p>允许 Agent 直接操作本机白名单目录（{@code lengbot.local-file.root}，
 * 默认 {@code D:/lengbot/workspace}）下的文件，用于本地开发调试。
 * 提供 列目录 / 读 / 写(覆盖) / 追加 / 删除 能力，全部路径强制限制在白名单根目录内，
 * 禁止 ".." 遍历与绝对路径越界。</p>
 * <p>{@code lengbot.local-file.root} 留空则该工具禁用（调用即返回未配置提示）。</p>
 *
 * @author lw
 * @since 2026-08-21
 */
@Slf4j
@Component("localFileTool")
@RequiredArgsConstructor
@SystemTool(displayName = "本地文件访问", icon = "FolderOpenOutlined",
        description = "受控增删改查本机白名单目录（lengbot.local-file.root）下的文件，仅供本地开发调试",
        tags = {"file", "host", "read", "write", "delete"})
public class LocalFileTool {

    private final ObjectMapper objectMapper;

    @Value("${lengbot.local-file.root:}")
    private String rootRaw;

    /** 单次读取上限（UTF-8 文本），超过则拒绝，避免大文件撑爆上下文 */
    private static final long MAX_READ_BYTES = 4L * 1024 * 1024;

    /** 单次写入上限（UTF-8 文本），超过则拒绝，防止误写超大文件 */
    private static final long MAX_WRITE_BYTES = 10L * 1024 * 1024;

    @Tool(name = "local_read_file",
          description = "读取本机白名单目录（lengbot.local-file.root，默认 D:/lengbot/workspace）下的文本文件。" +
                  "传入相对于该根目录的路径，如 README.md 或 src/main/java/App.java。" +
                  "只能访问白名单内的文件；仅支持 UTF-8 文本，最大 4MB。")
    @SystemTool(displayName = "读取本地文件")
    public String readFile(
            @ToolParam(name = "path", description = "相对于白名单根目录的文件路径，如 README.md 或 data/notes.txt")
            @ToolParamMeta(example = "README.md") String path,
            ToolCallParam toolContext) {
        if (path == null || path.isBlank()) {
            return errorJson("路径不能为空");
        }
        if (isDisabled()) {
            return errorJson("本地文件访问未启用（lengbot.local-file.root 未配置）");
        }
        try {
            Path root = rootPath();
            Path target = LocalFilePathValidator.resolve(path.trim(), root);
            if (!Files.isRegularFile(target)) {
                return errorJson("文件不存在或非普通文件: " + path);
            }
            long size = Files.size(target);
            if (size > MAX_READ_BYTES) {
                return errorJson("文件超过读取上限(4MB): " + path + " (" + size + " bytes)");
            }
            String content = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path.trim());
            out.put("content", content);
            out.put("size", content.length());
            return toJson(out);
        } catch (SecurityException | IOException e) {
            return errorJson("读取文件失败: " + e.getMessage());
        }
    }

    @Tool(name = "local_list_dir",
          description = "列出本机白名单目录（lengbot.local-file.root，默认 D:/lengbot/workspace）下的文件。" +
                  "传入相对于根目录的子路径如 data，不传则列出根目录。只能访问白名单内的目录。")
    @SystemTool(displayName = "列出本地目录")
    public String listDir(
            @ToolParam(name = "dirPath", description = "相对于白名单根目录的目录路径，如 data；不传则列出根目录")
            @ToolParamMeta(example = "data") String dirPath,
            ToolCallParam toolContext) {
        if (isDisabled()) {
            return errorJson("本地文件访问未启用（lengbot.local-file.root 未配置）");
        }
        String path = (dirPath == null || dirPath.isBlank()) ? "" : dirPath.trim();
        try {
            Path root = rootPath();
            Path target = LocalFilePathValidator.resolve(path, root);
            if (!Files.isDirectory(target)) {
                return errorJson("不是目录或不存在: " + path);
            }
            List<String> files = new ArrayList<>();
            try (Stream<Path> entries = Files.list(target)) {
                entries.forEach(p -> {
                    String name = p.getFileName().toString();
                    files.add(Files.isDirectory(p) ? name + "/" : name);
                });
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dirPath", path.isEmpty() ? "(根目录)" : path);
            out.put("root", root.toString());
            out.put("files", files);
            out.put("total", files.size());
            return toJson(out);
        } catch (SecurityException | IOException e) {
            return errorJson("列出目录失败: " + e.getMessage());
        }
    }

    @Tool(name = "local_write_file",
          description = "写入（覆盖）本机白名单目录下的文本文件，仅用于**用户显式点名的本机文件**（如“读取/保存 D:/.../foo.txt”）。" +
                  "传入相对于根目录的路径和内容；父目录不存在会自动创建。" +
                  "只能写白名单内的文件；仅 UTF-8，最大 10MB。" +
                  "注意：你自己生成的产出（报告/HTML/代码等）不要用本工具保存，应改用 sandbox_write_file(outputs/...) + present_artifacts，否则不会出现在用户的会话文件树里。")
    @SystemTool(displayName = "写入本地文件")
    public String writeFile(
            @ToolParam(name = "path", description = "相对于白名单根目录的文件路径，如 notes/draft.md 或 output.txt")
            @ToolParamMeta(example = "notes/draft.md") String path,
            @ToolParam(name = "content", description = "要写入的文件内容（覆盖原有内容）")
            @ToolParamMeta(example = "# 标题\\n内容……") String content,
            ToolCallParam toolContext) {
        if (path == null || path.isBlank()) {
            return errorJson("路径不能为空");
        }
        if (content == null) {
            content = "";
        }
        if (isDisabled()) {
            return errorJson("本地文件访问未启用（lengbot.local-file.root 未配置）");
        }
        try {
            Path root = rootPath();
            Path target = LocalFilePathValidator.resolve(path.trim(), root);
            if (Files.isDirectory(target)) {
                return errorJson("目标是目录，不能写入: " + path);
            }
            Files.createDirectories(target.getParent());
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_WRITE_BYTES) {
                return errorJson("内容超过写入上限(10MB): " + path);
            }
            Files.write(target, data, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path.trim());
            out.put("size", data.length);
            out.put("success", true);
            out.put("mode", "overwrite");
            return toJson(out);
        } catch (SecurityException | IOException e) {
            return errorJson("写入文件失败: " + e.getMessage());
        }
    }

    @Tool(name = "local_append_file",
          description = "向本机白名单目录下的文本文件追加内容（不存在则创建），仅用于**用户显式点名的本机文件**。" +
                  "用于分次写入同一文件。只能写白名单内的文件；仅 UTF-8，最大 10MB。" +
                  "注意：你自己生成的产出不要用本工具追加，应改用 sandbox_write_file(outputs/...) + present_artifacts。")
    @SystemTool(displayName = "追加本地文件")
    public String appendFile(
            @ToolParam(name = "path", description = "相对于白名单根目录的文件路径，须与先前写入使用同一路径")
            @ToolParamMeta(example = "notes/draft.md") String path,
            @ToolParam(name = "content", description = "要追加的内容")
            @ToolParamMeta(example = "\\n\\n## 第二节") String content,
            ToolCallParam toolContext) {
        if (path == null || path.isBlank()) {
            return errorJson("路径不能为空");
        }
        if (content == null) {
            content = "";
        }
        if (isDisabled()) {
            return errorJson("本地文件访问未启用（lengbot.local-file.root 未配置）");
        }
        try {
            Path root = rootPath();
            Path target = LocalFilePathValidator.resolve(path.trim(), root);
            if (Files.isDirectory(target)) {
                return errorJson("目标是目录，不能追加: " + path);
            }
            Files.createDirectories(target.getParent());
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_WRITE_BYTES) {
                return errorJson("内容超过写入上限(10MB): " + path);
            }
            Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path.trim());
            out.put("size", data.length);
            out.put("success", true);
            out.put("mode", "append");
            return toJson(out);
        } catch (SecurityException | IOException e) {
            return errorJson("追加文件失败: " + e.getMessage());
        }
    }

    @Tool(name = "local_delete_file",
          description = "删除白名单目录下的文件（仅普通文件，不能删目录）。传入相对于根目录的路径。用于「删」。")
    @SystemTool(displayName = "删除本地文件")
    public String deleteFile(
            @ToolParam(name = "path", description = "相对于白名单根目录的文件路径，如 notes/draft.md")
            @ToolParamMeta(example = "notes/draft.md") String path,
            ToolCallParam toolContext) {
        if (path == null || path.isBlank()) {
            return errorJson("路径不能为空");
        }
        if (isDisabled()) {
            return errorJson("本地文件访问未启用（lengbot.local-file.root 未配置）");
        }
        try {
            Path root = rootPath();
            Path target = LocalFilePathValidator.resolve(path.trim(), root);
            if (!Files.exists(target)) {
                return errorJson("文件不存在: " + path);
            }
            if (Files.isDirectory(target)) {
                return errorJson("目标是目录，不允许删除（仅支持文件）: " + path);
            }
            Files.delete(target);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path.trim());
            out.put("success", true);
            return toJson(out);
        } catch (SecurityException | IOException e) {
            return errorJson("删除文件失败: " + e.getMessage());
        }
    }

    private boolean isDisabled() {
        return rootRaw == null || rootRaw.isBlank();
    }

    private Path rootPath() {
        return Path.of(rootRaw).toAbsolutePath().normalize();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"序列化失败\"}";
        }
    }

    private String errorJson(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("success", false);
        error.put("error", message);
        try {
            return objectMapper.writeValueAsString(error);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"序列化失败\"}";
        }
    }
}
