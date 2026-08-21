package com.lengbot.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 内置工具 - 更新当前会话待办快照。
 * <p>工具本身不维护第二份会话状态；返回结果会由对话工具事件持久化，前端据此恢复待办。</p>
 * <p>合并语义（防 AI 漏传丢项）：从 ToolCallParam.currentTodos 读历史快照，按 id upsert；
 * 未在新传入列表中提及的项默认保留；已完成/已取消项不会被清除。</p>
 *
 * @author lw
 * @since 2026-07-14
 */
@Slf4j
@Component("writeTodosTool")
@RequiredArgsConstructor
@SystemTool(displayName = "更新待办", icon = "CheckSquareOutlined",
        description = "更新当前任务的待办清单。适用于多步骤任务、并行子智能体编排和阶段进度同步。传入需要新增或更新状态的项；未提及的已有项默认保留，已完成项不会被清除。",
        tags = {"协作", "待办"},
        outputExample = "{\"success\":true,\"todos\":[{\"id\":\"research\",\"content\":\"调研资料\",\"status\":\"completed\"}]}",
        outputSchema = "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"todos\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"status\":{\"type\":\"string\",\"enum\":[\"pending\",\"in_progress\",\"completed\",\"cancelled\"]}}}}}}")
public class WriteTodosTool {

    private static final List<String> SUPPORTED_STATUSES = List.of("pending", "in_progress", "completed", "cancelled");
    /** 终态：已完成的项不应被后续漏传的调用清除 */
    private static final List<String> TERMINAL_STATUSES = List.of("completed", "cancelled");

    private final ObjectMapper objectMapper;

    /**
     * 返回经校验+合并后的完整待办快照。
     *
     * @param todos        本次需要新增或更新状态的待办列表
     * @param toolContext  工具上下文，从中读取 currentTodos 历史快照用于按 id 合并
     * @return 合并后的完整待办快照 JSON
     */
    @Tool(name = "write_todos", description = "写入当前任务的待办清单。每项包含稳定 id、内容 content 和状态 status（pending/in_progress/completed/cancelled）。传入需要新增或更新状态的项；未提及的已有项默认保留（防丢），已完成/已取消项不会被清除。无需重传整个清单。")
    public String writeTodos(
            @ToolParam(name = "todos", description = "需要新增或更新状态的待办列表。每项必须有 id、content、status；status 只能是 pending、in_progress、completed、cancelled。")
            @ToolParamMeta(example = "[{\"id\":\"research\",\"content\":\"调研资料\",\"status\":\"in_progress\"}]")
            List<Map<String, Object>> todos,
            ToolCallParam toolContext) {
        if (todos == null || todos.isEmpty()) {
            return toJson(Map.of("success", false, "error", "待办列表不能为空", "todos", List.of()));
        }

        // 1. 校验并规范化本次传入的项，保证前端无需猜测字段或状态
        Map<String, Map<String, String>> incomingById = new LinkedHashMap<>();
        for (Map<String, Object> todo : todos) {
            String id = value(todo, "id");
            String content = value(todo, "content");
            String status = value(todo, "status");
            if (id.isBlank() || content.isBlank() || !SUPPORTED_STATUSES.contains(status)) {
                return toJson(Map.of("success", false,
                        "error", "每个待办必须包含 id、content，且 status 为 pending/in_progress/completed/cancelled",
                        "todos", List.of()));
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("content", content);
            item.put("status", status);
            incomingById.put(id, item);
        }

        // 2. 从 ToolCallParam 读历史快照，按 id 合并：
        //    - 历史 completed/cancelled 项：保留，不被新传入的同 id 覆盖（防"已完成"回滚）
        //    - 历史 pending/in_progress 项：被新传入同 id 覆盖（status 变化）；未被提及则保留（防漏丢）
        //    - 新传入项：历史无则新增
        List<Map<String, String>> history = loadHistoryTodos(toolContext);
        Map<String, Map<String, String>> mergedById = new LinkedHashMap<>();
        for (Map<String, String> hist : history) {
            String id = hist.get("id");
            if (id != null && !id.isBlank()) {
                mergedById.put(id, hist);
            }
        }
        for (Map<String, String> item : incomingById.values()) {
            String id = item.get("id");
            Map<String, String> hist = mergedById.get(id);
            // 历史是终态 → 不被新传入覆盖
            if (hist != null && TERMINAL_STATUSES.contains(hist.get("status"))) {
                continue;
            }
            mergedById.put(id, item);
        }

        // 3. 结果由 ChatService 的工具事件持久化，作为会话待办的唯一历史来源
        return toJson(Map.of("success", true, "todos", new ArrayList<>(mergedById.values())));
    }

    /**
     * 从 ToolCallParam.currentTodos 读取历史快照（由 ToolPrepMiddleware 在加载工具时注入）
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> loadHistoryTodos(ToolCallParam toolContext) {
        if (toolContext == null || toolContext.getRuntimeContext() == null) {
            return List.of();
        }
        Object raw = toolContext.getRuntimeContext().get("currentTodos");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, String> typed = new LinkedHashMap<>();
                Object id = map.get("id");
                Object content = map.get("content");
                Object status = map.get("status");
                typed.put("id", id == null ? "" : String.valueOf(id));
                typed.put("content", content == null ? "" : String.valueOf(content));
                typed.put("status", status == null ? "pending" : String.valueOf(status));
                result.add(typed);
            }
        }
        return result;
    }

    private String value(Map<String, Object> source, String key) {
        Object value = source != null ? source.get(key) : null;
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String toJson(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"待办结果序列化失败\",\"todos\":[]}";
        }
    }
}
