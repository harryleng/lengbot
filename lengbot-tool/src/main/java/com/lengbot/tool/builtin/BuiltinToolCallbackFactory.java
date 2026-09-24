package com.lengbot.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 内置工具回调工厂
 * <p>将 3 个依赖 {@link ToolCallParam} 运行时上下文（agentId / sessionId）的内置工具
 * （{@code WriteTodosTool} / {@code PresentArtifactsTool} / {@code OcrParseFileTool}）暴露为 AgentScope
 * {@link ToolBase} 回调（重写 {@code callAsync(ToolCallParam)}），使引擎调用时把完整的 {@link ToolCallParam}
 * （含 RuntimeContext）透传给工具，修复原先 {@code Toolkit.registerTool} 反射路径下 {@link ToolCallParam}
 * 不被注入、导致 {@code context.getRuntimeContext()} 为空上下文的隐患：
 * <ul>
 *   <li>write_todos：原先 context=null → loadHistoryTodos 永远返回空，多次调用互相覆盖丢项；</li>
 *   <li>present_artifacts：原先 context=null → 拿不到 sessionId，交付失败；</li>
 *   <li>ocr_parse_file：原先 context=null → 回落 "default" 会话，解析到错误会话。</li>
 * </ul>
 * 这三个工具不再经由 {@code ToolServiceImpl.scanBuiltinToolCallbacks} 的反射注册（该扫描已跳过这三个类），
 * 而改由本工厂提供 ToolBase。业务方法签名 {@code (args..., ToolCallParam toolContext)} 保持不变，工厂解析
 * {@code param.getInput()} 后直接委派，并将非空 {@code param} 作为运行上下文传入。
 *
 * @author lw
 * @since 2026-09-24
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinToolCallbackFactory {

    private final WriteTodosTool writeTodosTool;
    private final PresentArtifactsTool presentArtifactsTool;
    private final OcrParseFileTool ocrParseFileTool;
    private final ObjectMapper objectMapper;

    public List<ToolBase> buildCallbacks() {
        return List.of(
                writeTodosCallback(),
                presentArtifactsCallback(),
                ocrParseFileCallback());
    }

    // ===================== 通用辅助 =====================

    private Map<String, Object> schema(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(ToolCallParam param) {
        if (param == null) {
            return Map.of();
        }
        try {
            Object input = param.getInput();
            if (input == null) {
                return Map.of();
            }
            if (input instanceof String s) {
                if (s.isBlank()) {
                    return Map.of();
                }
                return objectMapper.readValue(s, Map.class);
            }
            if (input instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        } catch (Exception e) {
            log.warn("[BuiltinToolCallbackFactory] 解析工具入参失败: {}", e.getMessage());
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object v) {
        if (v instanceof List<?> l) {
            return (List<Object>) l;
        }
        return List.of();
    }

    private static String str(Object v) {
        return v != null ? v.toString() : null;
    }

    // ===================== write_todos =====================

    private ToolBase writeTodosCallback() {
        return new BuiltinCallback("write_todos",
                "写入当前任务的待办清单。每项包含稳定 id、内容 content 和状态 status（pending/in_progress/completed/cancelled）。传入需要新增或更新状态的项；未提及的已有项默认保留（防丢），已完成/已取消项不会被清除。无需重传整个清单。",
                schema("{\"type\":\"object\",\"properties\":{\"todos\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"status\":{\"type\":\"string\",\"enum\":[\"pending\",\"in_progress\",\"completed\",\"cancelled\"]}}}}}}"),
                param -> {
                    Map<String, Object> args = parseArgs(param);
                    List<Map<String, Object>> todos = asList(args.get("todos")).stream()
                            .filter(o -> o instanceof Map)
                            .map(o -> (Map<String, Object>) o)
                            .collect(Collectors.toList());
                    return writeTodosTool.writeTodos(todos, param);
                });
    }

    // ===================== present_artifacts =====================

    private ToolBase presentArtifactsCallback() {
        return new BuiltinCallback("present_artifacts",
                "将 outputs/ 目录下生成的文件交付给用户。传入文件路径列表（必须以 outputs/ 开头，如 outputs/files/report.pdf），系统会验证文件存在并生成下载链接，前端将展示为文件卡片（支持图片预览、文档下载）。仅支持 outputs/ 目录下文件，不支持 Skill 只读文件和工作区临时文件。",
                schema("{\"type\":\"object\",\"properties\":{\"filepaths\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}"),
                param -> {
                    Map<String, Object> args = parseArgs(param);
                    List<String> filepaths = asList(args.get("filepaths")).stream()
                            .map(BuiltinToolCallbackFactory::str)
                            .collect(Collectors.toList());
                    return presentArtifactsTool.presentArtifacts(filepaths, param);
                });
    }

    // ===================== ocr_parse_file =====================

    private ToolBase ocrParseFileCallback() {
        return new BuiltinCallback("ocr_parse_file",
                "将沙盒中的 PDF 或图片文件 OCR 解析为文本。使用场景：用户上传了 PDF/图片附件需要提取文字，或工作区中已有此类文件需转为可读文本。path 传文件名或相对路径即可（如 期音端午通知.pdf、data/scan.png）：系统自动在当前会话中定位——优先工作区，未找到则在用户上传目录按文件名匹配；也可显式指定 outputs/、inputs/、workspace/ 前缀。支持 pdf/jpg/jpeg/png/bmp/tiff。结果会写入工作区 ocr/ 目录，工具只返回结果文件路径和短预览，完整文本请用 sandbox_read_file 读取结果文件。",
                schema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"),
                param -> {
                    Map<String, Object> args = parseArgs(param);
                    return ocrParseFileTool.parseFile(str(args.get("path")), param);
                });
    }

    // ===================== 通用回调载体 =====================

    /**
     * 统一的 ToolBase 实现：重写 callAsync，把非空 ToolCallParam 透传给业务函数，并包裹异常。
     */
    private static final class BuiltinCallback extends ToolBase {
        private final Function<ToolCallParam, String> executor;

        BuiltinCallback(String name, String description, Map<String, Object> inputSchema,
                        Function<ToolCallParam, String> executor) {
            super(ToolBase.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(inputSchema)
            );
            this.executor = executor;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            try {
                return Mono.just(ToolResultBlock.text(executor.apply(param)));
            } catch (Exception e) {
                return Mono.just(ToolResultBlock.error("内置工具执行异常: " + e.getMessage()));
            }
        }
    }
}
