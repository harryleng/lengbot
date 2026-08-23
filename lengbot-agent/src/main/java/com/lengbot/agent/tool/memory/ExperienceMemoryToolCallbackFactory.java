package com.lengbot.agent.tool.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.service.WorkspaceMemoryService;
import com.lengbot.service.chat.ChatContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 经验沉淀工具回调工厂。
 *
 * <p>提供 {@code save_experience} 工具，供 agent 在任务过程中把"踩坑经验"或"成功案例"
 * 沉淀到工作区记忆（{@code project_memory}）。落库后经
 * {@code MessageMiddleware.appendWorkspaceMemoryPrompt} 在后续对话里按语义检索自动注入，
 * 形成"踩坑/成功经验 → 记忆 → 下次相似场景作为背景参考"的闭环。</p>
 *
 * <p>设计对齐 {@link UserMemoryToolCallbackFactory}：同为 {@code @Component}，
 * 工具回调在 {@code ToolPrepMiddleware} 注入，上下文经 AgentScope RuntimeContext 透传。</p>
 *
 * @author lw
 * @since 2026-08-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExperienceMemoryToolCallbackFactory {

    public static final String SAVE_TOOL_NAME = "save_experience";

    private static final int MAX_CONTENT_CHARS = 1000;

    private final WorkspaceMemoryService workspaceMemoryService;
    private final ObjectMapper objectMapper;

    /**
     * 构建经验沉淀工具回调列表
     *
     * @return 工具回调列表
     */
    public List<ToolBase> buildCallbacks() {
        return List.of(new SaveExperienceCallback());
    }

    private class SaveExperienceCallback extends BaseMemoryCallback {

        SaveExperienceCallback() {
            super(ToolBase.builder()
                    .name(SAVE_TOOL_NAME)
                    .description("""
                            沉淀经验到工作区记忆。在以下情况主动调用：
                            (1) 踩坑经验(kind=lesson)：你在任务中遇到并解决了棘手的报错/坑/绕路，未来同类场景应避免重蹈；
                            (2) 成功案例(kind=case)：你找到了高效、可复用的做法，未来同类任务可照此执行。
                            仅保存有复用价值的经验，不要记录一次性细节或敏感信息（密码/密钥/Token）。
                            """)
                    .inputSchema(parseSchema("""
                            {
                              "type": "object",
                              "properties": {
                                "kind": {"type": "string", "enum": ["lesson", "case"], "description": "lesson=踩坑经验，case=成功案例。"},
                                "context": {"type": "string", "description": "触发场景/问题背景，用于未来相似场景匹配。"},
                                "content": {"type": "string", "description": "经验教训或成功做法的核心内容（踩坑：坑是什么、怎么解的；案例：有效做法是什么）。"},
                                "keywords": {"type": "array", "items": {"type": "string"}, "description": "可选检索关键词。"},
                                "confidence": {"type": "number", "minimum": 0, "maximum": 1, "description": "经验置信度，默认1。"}
                              },
                              "required": ["kind", "context", "content"]
                            }
                            """))
                    );
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            try {
                String toolInput = extractArgsJson(param);
                Map<String, Object> args = parseArgs(toolInput);
                Long userId = resolveUserId(param);
                if (userId == null) {
                    return result(failure("缺少用户上下文，无法沉淀经验"));
                }
                String kindRaw = str(args.get("kind"));
                String kind = normalizeKind(kindRaw);
                String context = str(args.get("context"));
                String content = str(args.get("content"));
                if (content == null || content.isBlank()) {
                    return result(failure("缺少 content 参数"));
                }
                String fullContent = composeContent(context, content);
                Long id = workspaceMemoryService.saveWorkspaceMemory(
                        userId,
                        null,
                        resolveSessionId(param),
                        resolveUserMessageId(param),
                        kind,
                        fullContent,
                        stringList(args.get("keywords")),
                        decimal(args.get("confidence")));
                return result(objectMapper.writeValueAsString(
                        Map.of("success", true, "id", String.valueOf(id), "kind", kind)));
            } catch (Exception e) {
                log.warn("[Experience] save_experience 调用失败: {}", e.getMessage());
                return result(failure(e.getMessage()));
            }
        }

        /** 校验 kind，非法或缺失时按 lesson 兜底（content 仍按用户填写保存） */
        private String normalizeKind(String kindRaw) {
            if (kindRaw != null) {
                String k = kindRaw.trim().toLowerCase();
                if ("case".equals(k) || "lesson".equals(k)) {
                    return k;
                }
            }
            return "lesson";
        }

        /** 拼装最终 content：带场景前缀，便于检索与阅读，并截断保护 */
        private String composeContent(String context, String content) {
            String ctx = context == null ? "" : context.trim();
            String body = content.trim();
            StringBuilder sb = new StringBuilder();
            if (!ctx.isBlank()) {
                sb.append("【场景】").append(ctx).append('\n');
            }
            sb.append(body);
            String result = sb.toString();
            if (result.length() > MAX_CONTENT_CHARS) {
                result = result.substring(0, MAX_CONTENT_CHARS);
            }
            return result;
        }
    }

    // ====================== 通用辅助（对齐 UserMemoryToolCallbackFactory） ======================

    private abstract class BaseMemoryCallback extends ToolBase {

        BaseMemoryCallback(ToolBase.Builder builder) {
            super(builder);
        }

        /** 从 ToolCallParam 提取工具入参 JSON 字符串 */
        @SuppressWarnings("unchecked")
        protected String extractArgsJson(ToolCallParam param) {
            if (param == null) return "{}";
            try {
                Object args = param.getInput();
                if (args == null) return "{}";
                if (args instanceof String s) return s;
                return objectMapper.writeValueAsString(args);
            } catch (Exception e) {
                return "{}";
            }
        }

        protected Mono<ToolResultBlock> result(String text) {
            return Mono.just(ToolResultBlock.of(null, getName(),
                    TextBlock.builder().text(text).build()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSchema(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> parseArgs(String toolInput) throws Exception {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(toolInput, new TypeReference<>() {});
    }

    private String failure(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("success", false, "error", message != null ? message : "unknown"));
        } catch (Exception e) {
            return "{\"success\":false}";
        }
    }

    @SuppressWarnings("unchecked")
    private Long resolveUserId(ToolCallParam param) {
        Object value = contextValue(param, "userId");
        if (value == null) {
            ChatContext chatContext = resolveChatContext(param);
            return chatContext != null ? chatContext.getUserId() : null;
        }
        return longVal(value);
    }

    @SuppressWarnings("unchecked")
    private Long resolveSessionId(ToolCallParam param) {
        Object value = contextValue(param, "sessionId");
        return longVal(value);
    }

    private Long resolveUserMessageId(ToolCallParam param) {
        ChatContext chatContext = resolveChatContext(param);
        return chatContext != null ? chatContext.getUserMessageId() : null;
    }

    @SuppressWarnings("unchecked")
    private ChatContext resolveChatContext(ToolCallParam param) {
        Object value = contextValue(param, "chatContext");
        return value instanceof ChatContext chatContext ? chatContext : null;
    }

    @SuppressWarnings("unchecked")
    private Object contextValue(ToolCallParam param, String key) {
        if (param == null) return null;
        try {
            // 与 UserMemoryToolCallbackFactory / WriteTodosTool 一致：从 RuntimeContext 取上下文
            io.agentscope.core.agent.RuntimeContext rc = param.getRuntimeContext();
            if (rc != null) {
                return rc.get(key);
            }
        } catch (Exception ignored) {
            // RuntimeContext 可能未注入
        }
        return null;
    }

    private String str(Object value) {
        return value != null ? value.toString() : null;
    }

    private Long longVal(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            if (item != null && !item.toString().isBlank()) {
                result.add(item.toString());
            }
        }
        return result;
    }
}
