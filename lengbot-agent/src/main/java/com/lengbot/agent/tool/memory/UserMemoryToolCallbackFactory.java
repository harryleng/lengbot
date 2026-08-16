package com.lengbot.agent.tool.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.vo.UserMemoryVO;
import com.lengbot.vo.UserPreferenceVO;
import com.lengbot.entity.UserMemory;
import com.lengbot.enums.UserMemoryStatus;
import com.lengbot.service.UserMemoryService;
import com.lengbot.service.UserPreferenceService;
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
 * 用户长期记忆工具回调工厂
 *
 * @author finch
 * @since 2026-07-09
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserMemoryToolCallbackFactory {

    public static final String SAVE_TOOL_NAME = "memory_save";
    public static final String SEARCH_TOOL_NAME = "memory_search";
    public static final String DELETE_TOOL_NAME = "memory_delete";

    private final UserMemoryService userMemoryService;
    private final UserPreferenceService userPreferenceService;
    private final ObjectMapper objectMapper;

    /**
     * 构建长期记忆工具回调列表
     *
     * @return 工具回调列表
     */
    public List<ToolBase> buildCallbacks() {
        return List.of(new SaveMemoryCallback(), new SearchMemoryCallback(), new DeleteMemoryCallback());
    }

    private class SaveMemoryCallback extends BaseMemoryCallback {

        SaveMemoryCallback() {
            super(ToolBase.builder()
                    .name(SAVE_TOOL_NAME)
                    .description("""
                            保存用户长期记忆。仅当用户明确要求记住稳定偏好、个人背景、项目事实或长期指令时调用。
                            禁止保存密码、密钥、API Key、Token、隐私联系方式或临时性事实。
                            """)
                    .inputSchema(parseSchema("""
                            {
                              "type": "object",
                              "properties": {
                                "content": {"type": "string", "description": "要保存的简洁记忆内容。"},
                                "memoryType": {"type": "string", "enum": ["preference", "profile", "project_fact", "instruction"], "description": "记忆类型。"},
                                "keywords": {"type": "array", "items": {"type": "string"}, "description": "可选检索关键词。"},
                                "confidence": {"type": "number", "minimum": 0, "maximum": 1, "description": "记忆置信度。"}
                              },
                              "required": ["content"]
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
                    return result(failure("缺少用户上下文，无法保存长期记忆"));
                }
                String content = str(args.get("content"));
                if (content == null || content.isBlank()) {
                    return result(failure("缺少 content 参数"));
                }
                UserMemoryVO saved = userMemoryService.saveFromTool(
                        userId,
                        resolveMemoryAgentId(userId, param),
                        resolveSessionId(param),
                        resolveUserMessageId(param),
                        str(args.get("memoryType")),
                        content,
                        stringList(args.get("keywords")),
                        decimal(args.get("confidence")));
                return result(objectMapper.writeValueAsString(Map.of("success", true, "memory", saved)));
            } catch (Exception e) {
                return result(failure(e.getMessage()));
            }
        }
    }

    private class SearchMemoryCallback extends BaseMemoryCallback {

        SearchMemoryCallback() {
            super(ToolBase.builder()
                    .name(SEARCH_TOOL_NAME)
                    .description("查询当前用户已启用的长期记忆，用于获取相关偏好、背景或长期指令。")
                    .inputSchema(parseSchema("""
                            {
                              "type": "object",
                              "properties": {
                                "query": {"type": "string", "description": "检索问题或关键词。"},
                                "limit": {"type": "integer", "minimum": 1, "maximum": 10, "description": "最多返回记忆条数。"}
                              },
                              "required": ["query"]
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
                    return result(failure("缺少用户上下文，无法查询长期记忆"));
                }
                String query = str(args.get("query"));
                int limit = intVal(args.get("limit"), 5, 1, 10);
                List<UserMemoryVO> memories = userMemoryService.searchForPrompt(userId, resolveMemoryAgentId(userId, param), query, limit)
                        .stream()
                        .map(UserMemoryVO::from)
                        .toList();
                return result(objectMapper.writeValueAsString(Map.of("success", true, "memories", memories)));
            } catch (Exception e) {
                return result(failure(e.getMessage()));
            }
        }
    }

    private class DeleteMemoryCallback extends BaseMemoryCallback {

        DeleteMemoryCallback() {
            super(ToolBase.builder()
                    .name(DELETE_TOOL_NAME)
                    .description("当用户要求忘记某条长期记忆时，停用该长期记忆。")
                    .inputSchema(parseSchema("""
                            {
                              "type": "object",
                              "properties": {
                                "memoryId": {"type": "string", "description": "要停用的记忆ID。"}
                              },
                              "required": ["memoryId"]
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
                Long memoryId = longVal(args.get("memoryId"));
                if (userId == null || memoryId == null) {
                    return result(failure("缺少用户上下文或 memoryId 参数"));
                }
                UserMemory memory = userMemoryService.getById(memoryId);
                if (memory == null || !userId.equals(memory.getUserId())) {
                    return result(failure("未找到可停用的长期记忆"));
                }
                memory.setStatus(UserMemoryStatus.DISABLED);
                userMemoryService.updateById(memory);
                return result(objectMapper.writeValueAsString(Map.of("success", true, "memoryId", String.valueOf(memoryId))));
            } catch (Exception e) {
                return result(failure(e.getMessage()));
            }
        }
    }

    private abstract class BaseMemoryCallback extends ToolBase {

        BaseMemoryCallback(ToolBase.Builder builder) {
            super(builder);
        }

        /**
         * 从 ToolCallParam 提取工具入参 JSON 字符串
         */
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
    private Long resolveAgentId(ToolCallParam param) {
        Object value = contextValue(param, "agentId");
        return longVal(value);
    }

    private Long resolveMemoryAgentId(Long userId, ToolCallParam param) {
        try {
            UserPreferenceVO preferences = userPreferenceService.getPreferences(userId);
            return "agent".equalsIgnoreCase(preferences.getLongMemoryScope()) ? resolveAgentId(param) : null;
        } catch (Exception e) {
            return null;
        }
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
            // AgentScope 2.0.1：工具上下文统一经由 RuntimeContext 透传（callAsync 时由调用方 set 到 ToolCallParam）
            // 知识库工具（QueryKnowledgeTool）与 WriteTodosTool 均从 getRuntimeContext().get(key) 读取，
            // 故此处对齐同一通道，避免 instanceof Map 永远为 false 导致取不到上下文。
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

    private int intVal(Object value, int defaultValue, int min, int max) {
        int parsed = defaultValue;
        if (value instanceof Number n) {
            parsed = n.intValue();
        } else if (value != null && !value.toString().isBlank()) {
            try {
                parsed = Integer.parseInt(value.toString());
            } catch (Exception ignored) {
            }
        }
        return Math.max(min, Math.min(max, parsed));
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
