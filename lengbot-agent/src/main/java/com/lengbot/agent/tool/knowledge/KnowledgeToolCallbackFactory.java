package com.lengbot.agent.tool.knowledge;

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

/**
 * 知识库工具回调工厂
 * <p>将 4 个知识库工具（QueryKnowledgeTool / KnowledgeTools / FindInDocumentTool / SearchDocumentsTool）
 * 暴露为 AgentScope {@link ToolBase} 回调（重写 {@code callAsync(ToolCallParam)}），使引擎调用时把完整的
 * {@link ToolCallParam}（含 runtimeContext 中的 agentId / requestId）透传给工具，
 * 修复原先 {@code Toolkit.registerTool} 反射路径下 {@code ToolCallParam} 不被注入、导致
 * {@code context.getRuntimeContext()} 空指针的问题。</p>
 *
 * <p>与 {@code UserMemoryToolCallbackFactory} 同构（均直接继承 {@link ToolBase} 而非依赖 lengbot-tool 的
 * LengBotToolAdapter）；知识库工具不再经由 {@code ToolServiceImpl.scanBuiltinToolCallbacks} 的反射注册
 * （该包已在扫描中排除）。业务方法签名保持 {@code (args..., ToolCallParam context)} 不变，工厂解析
 * {@code param.getInput()} 后直接委派，并将非空 {@code param} 作为运行上下文传入。</p>
 *
 * @author lw
 * @since 2026-09-24
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeToolCallbackFactory {

    private final QueryKnowledgeTool queryKnowledgeTool;
    private final KnowledgeTools knowledgeTools;
    private final FindInDocumentTool findInDocumentTool;
    private final SearchDocumentsTool searchDocumentsTool;
    private final ObjectMapper objectMapper;

    /**
     * 构建知识库工具回调列表（ToolBase）
     */
    public List<ToolBase> buildCallbacks() {
        return List.of(
                queryKnowledgeCallback(),
                listKnowledgeBasesCallback(),
                getMindmapCallback(),
                openKbDocumentCallback(),
                findInDocumentCallback(),
                searchDocumentsCallback());
    }

    // ===================== input schema 辅助 =====================

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
            log.warn("[KnowledgeToolCallbackFactory] 解析工具入参失败: {}", e.getMessage());
        }
        return Map.of();
    }

    private static String str(Object v) {
        return v != null ? v.toString() : null;
    }

    private static Integer integer(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== 通用回调载体 =====================

    /**
     * 统一的 ToolBase 实现：重写 callAsync，把非空 ToolCallParam 透传给业务函数，并包裹异常。
     */
    private static final class KnowledgeCallback extends ToolBase {
        private final Function<ToolCallParam, String> executor;

        KnowledgeCallback(String name, String description, Map<String, Object> inputSchema,
                          Function<ToolCallParam, String> executor) {
            super(ToolBase.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(inputSchema));
            this.executor = executor;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            try {
                return Mono.just(ToolResultBlock.text(executor.apply(param)));
            } catch (Exception e) {
                log.warn("[KnowledgeToolCallbackFactory] 工具执行异常: name={}, error={}", getName(), e.getMessage());
                return Mono.just(ToolResultBlock.error("知识库工具执行异常: " + e.getMessage()));
            }
        }
    }

    // ===================== 各工具回调 =====================

    private ToolBase queryKnowledgeCallback() {
        String schema = "{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\","
                + "\"description\":\"搜索问题\"}},\"required\":[\"question\"]}";
        return new KnowledgeCallback("query_knowledge",
                "搜索当前对话智能体绑定的知识库，获取与问题相关的文档内容。"
                        + "当用户问题涉及特定领域知识、需要查找文档资料时调用此工具。"
                        + "只需传入 question，不要传入 agentId。",
                schema(schema),
                param -> {
                    Map<String, Object> args = parseArgs(param);
                    return queryKnowledgeTool.queryKnowledge(str(args.get("question")), param);
                });
    }

    private ToolBase listKnowledgeBasesCallback() {
        String schema = "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
        return new KnowledgeCallback("list_knowledge_bases",
                "列出当前智能体绑定的所有知识库。"
                        + "当用户询问有哪些知识库、知识库列表时调用此工具。",
                schema(schema),
                param -> knowledgeTools.listKnowledgeBases(param));
    }

    private ToolBase getMindmapCallback() {
        String schema = "{\"type\":\"object\",\"properties\":{\"knowledgeId\":{\"type\":\"string\","
                + "\"description\":\"知识库ID\"}},\"required\":[]}";
        return new KnowledgeCallback("get_mindmap",
                "获取指定知识库的思维导图。"
                        + "当用户想了解知识库的知识结构、目录概览时调用此工具。",
                schema(schema),
                param -> {
                    Map<String, Object> args = parseArgs(param);
                    return knowledgeTools.getMindmap(str(args.get("knowledgeId")), param);
                });
    }

    private ToolBase openKbDocumentCallback() {
        String schema = "{\"type\":\"object\",\"properties\":{\"documentId\":{\"type\":\"string\","
                + "\"description\":\"文档ID\"}},\"required\":[]}";
        return new KnowledgeCallback("open_kb_document",
                "打开知识库中的指定文档，查看文档原文内容。"
                        + "当用户想查看某个文档的详细内容时调用此工具。",
                schema(schema),
                param -> {
                    Map<String, Object> args = parseArgs(param);
                    return knowledgeTools.openKbDocument(str(args.get("documentId")), param);
                });
    }

    private ToolBase findInDocumentCallback() {
        String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"description\":\"搜索关键词或正则表达式（为空时进入原文翻页模式）\"},"
                + "\"documentId\":{\"type\":\"string\",\"description\":\"文档ID（可选，不传则搜索整个知识库的所有文档）\"},"
                + "\"knowledgeId\":{\"type\":\"string\",\"description\":\"知识库ID（不指定文档时必填）\"},"
                + "\"contextLines\":{\"type\":\"integer\",\"description\":\"上下文行数（匹配行前后各显示几行，默认2）\"},"
                + "\"offset\":{\"type\":\"integer\",\"description\":\"原文模式：从第几行开始读取（默认0）\"},"
                + "\"windowSize\":{\"type\":\"integer\",\"description\":\"原文模式：读取字符数（默认2000，最大8000）\"}"
                + "},"
                + "\"required\":[]}";
        return new KnowledgeCallback("find_in_document",
                "在知识库文档中按关键词或正则表达式搜索，返回匹配的行及上下文。"
                        + "当 query 为空时，按 offset 翻页读取文档原文（open模式）。"
                        + "典型流程：query_knowledge 返回 document_id → find_in_document(documentId, query) 定位关键词"
                        + "或 find_in_document(documentId) 顺序翻页阅读原文。",
                schema(schema),
                param -> {
                    Map<String, Object> args = parseArgs(param);
                    return findInDocumentTool.findInDocument(
                            str(args.get("query")),
                            str(args.get("documentId")),
                            str(args.get("knowledgeId")),
                            integer(args.get("contextLines")),
                            integer(args.get("offset")),
                            integer(args.get("windowSize")),
                            param);
                });
    }

    private ToolBase searchDocumentsCallback() {
        String schema = "{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\","
                + "\"description\":\"搜索关键词（匹配文件名）\"}},\"required\":[\"keyword\"]}";
        return new KnowledgeCallback("search_documents",
                "在智能体绑定的知识库中按文件名模糊搜索，返回匹配的文档列表（含文档ID）。"
                        + "当向量检索未命中但用户提及特定文件/人名/主题时使用。"
                        + "返回结果中的文档ID可用于 open_document 查看文档详情。",
                schema(schema),
                param -> {
                    Map<String, Object> args = parseArgs(param);
                    return searchDocumentsTool.searchDocuments(str(args.get("keyword")), param);
                });
    }
}
