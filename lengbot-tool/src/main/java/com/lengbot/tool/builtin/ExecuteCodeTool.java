package com.lengbot.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.common.BizException;
import com.lengbot.dto.CodeArtifactDTO;
import com.lengbot.dto.CodeExecResultDTO;
import com.lengbot.service.sandbox.SandboxFileAccess;
import com.lengbot.service.sandbox.SandboxFs;
import com.lengbot.service.sandbox.SandboxPath;
import com.lengbot.service.sandbox.SandboxService;
import com.lengbot.tool.ToolEventEmitter;
import com.lengbot.tool.annotation.SystemTool;
import com.lengbot.tool.annotation.ToolParamMeta;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置工具 — 代码执行
 * <p>在安全沙盒中执行代码片段，支持 Java、JavaScript 和 Python。</p>
 *
 * @author lw
 * @since 2026-06-24
 */
@Slf4j
@Component("executeCodeTool")
@RequiredArgsConstructor
@SystemTool(displayName = "代码执行", icon = "CodeOutlined", description = "在安全沙盒中执行代码片段并返回结果",
        tags = {"code", "execution", "sandbox"},
        outputExample = "{\"success\":true,\"output\":\"\",\"returnValue\":\"2026-06-24T10:30:00\",\"error\":null,\"elapsedMs\":42,\"language\":\"java\"}",
        outputSchema = "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\",\"description\":\"是否成功\"},\"output\":{\"type\":\"string\",\"description\":\"stdout输出\"},\"returnValue\":{\"type\":\"string\",\"description\":\"返回值\"},\"error\":{\"type\":\"string\",\"description\":\"错误信息\"},\"elapsedMs\":{\"type\":\"number\",\"description\":\"执行耗时(ms)\"},\"language\":{\"type\":\"string\",\"description\":\"使用的语言\"}}}")
public class ExecuteCodeTool {

    private final SandboxService sandboxService;
    private final SandboxFs sandboxFs;
    private final ObjectMapper objectMapper;

    @Tool(name = "execute_code",
          description = "在安全沙盒中执行代码片段并返回结果。支持 Java（默认）、JavaScript 和 Python。"
                  + "\n【Java】直接写方法体（无需 import、class、main）。"
                  + "已预导入 java.time.*、java.util.*、java.math.*、java.text.*、java.util.stream.*、java.util.regex.*。"
                  + "如需返回值，用 return 语句。示例：return java.time.LocalDateTime.now().toString();"
                  + "\n【JavaScript】必须定义 function main(){} 作为入口（var 声明，ES5 语法，不支持 const/let/箭头函数）。"
                  + "示例：function main(){ var now = new Date(); return now.toISOString(); }"
                  + "\n【Python】必须定义 def main(): 作为入口。"
                  + "示例：def main(): return 'hello'"
                  + "\n【Python 产物】脚本可通过第三方库（如 python-pptx）生成文件（pptx/xlsx/pdf 等），"
                  + "执行成功后这些产物会自动以二进制写入当前会话 outputs/ 工作区，并在返回 artifacts 字段给出下载链接，"
                  + "可用 present_artifacts 直接交付给用户。注意用户代码仍禁止直接 open 写盘/网络/进程操作。"
                  + "\n超时 5 秒。")
    public String execute(
            @ToolParam(name = "code", description = "要执行的代码（Java写方法体，JS/Python写含main函数的完整代码）")
            @ToolParamMeta(example = "return java.time.LocalDateTime.now().toString()") String code,
            @ToolParam(name = "language", description = "编程语言（java/javascript/python），默认 java", required = false)
            @ToolParamMeta(example = "java") String language,
            ToolCallParam toolContext) {
        String lang = language != null ? language : "java";
        log.info("[Tool:execute_code] 语言={}, 代码长度={}", lang, code != null ? code.length() : 0);

        if (code == null || code.isBlank()) {
            return toJson(CodeExecResultDTO.builder()
                    .success(false)
                    .error("代码不能为空")
                    .language(lang)
                    .build());
        }

        try {
            ToolEventEmitter.emit("正在执行 " + lang.toUpperCase() + " 代码...");
            CodeExecResultDTO result = sandboxService.executeCode(code.trim(), language, null, null);
            if (result.isSuccess()) {
                ToolEventEmitter.emit("代码执行完成（" + result.getElapsedMs() + "ms）");
            } else {
                ToolEventEmitter.emit("代码执行失败: " + result.getError());
            }
            // Python 等语言产生的文件产物：自动落盘到当前会话 outputs/ 工作区，便于 present 交付
            List<Map<String, Object>> delivered = deliverArtifacts(result, toolContext);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", result.isSuccess());
            resp.put("output", result.getOutput());
            resp.put("returnValue", result.getReturnValue());
            resp.put("error", result.getError());
            resp.put("elapsedMs", result.getElapsedMs());
            resp.put("language", result.getLanguage());
            if (!delivered.isEmpty()) {
                resp.put("artifacts", delivered);
            }
            return toJson(resp);
        } catch (BizException e) {
            // 环境不可用（引擎未就绪 / 语言不支持）
            log.warn("[Tool:execute_code] 环境异常: {}", e.getMessage());
            ToolEventEmitter.emit("代码执行失败: " + e.getMessage());
            return toJson(CodeExecResultDTO.builder()
                    .success(false)
                    .error(e.getMessage())
                    .language(lang)
                    .build());
        } catch (Exception e) {
            log.error("[Tool:execute_code] 执行异常", e);
            ToolEventEmitter.emit("代码执行异常: " + e.getMessage());
            return toJson(CodeExecResultDTO.builder()
                    .success(false)
                    .error("执行异常: " + e.getMessage())
                    .language(lang)
                    .build());
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"序列化失败\"}";
        }
    }

    /**
     * 将执行结果中的文件产物（Base64）解码并写入当前会话 outputs/ 工作区，返回交付信息（含下载链接）。
     * <p>Agent 拿到 artifacts 后可直接用 present_artifacts 交付给用户，无需再手动调用写入工具。</p>
     */
    private List<Map<String, Object>> deliverArtifacts(CodeExecResultDTO result, ToolCallParam toolContext) {
        List<Map<String, Object>> delivered = new ArrayList<>();
        if (result.getArtifacts() == null || result.getArtifacts().isEmpty()) {
            return delivered;
        }
        String sessionId = extractSessionId(toolContext);
        for (CodeArtifactDTO artifact : result.getArtifacts()) {
            try {
                byte[] data = Base64.getDecoder().decode(artifact.getBase64());
                SandboxPath sandboxPath = SandboxPath.output(sessionId, artifact.getName());
                sandboxFs.writeBytes(sandboxPath, data);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", artifact.getName());
                info.put("path", "outputs/" + artifact.getName());
                info.put("size", data.length);
                try {
                    String contentType = inferContentType(artifact.getName());
                    SandboxFileAccess access = sandboxFs.resolveFileAccess(sandboxPath, contentType);
                    info.put("url", access.url());
                    info.put("downloadUrl", access.downloadUrl());
                    info.put("contentType", access.contentType());
                } catch (Exception ex) {
                    log.warn("[Tool:execute_code] 生成产物访问URL失败: {}, error={}", artifact.getName(), ex.getMessage());
                }
                delivered.add(info);
                ToolEventEmitter.emit("已生成产物并落盘: outputs/" + artifact.getName());
            } catch (Exception e) {
                log.warn("[Tool:execute_code] 产物落盘失败: {}", artifact.getName(), e);
            }
        }
        return delivered;
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

    private String inferContentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
