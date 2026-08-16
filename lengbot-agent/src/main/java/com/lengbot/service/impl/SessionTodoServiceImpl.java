package com.lengbot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.entity.Message;
import com.lengbot.service.MessageService;
import com.lengbot.service.SessionTodoService;
import com.lengbot.vo.TodoItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话待办服务实现：从消息工具事件恢复 todos 快照。
 *
 * @author finch
 * @since 2026-07-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionTodoServiceImpl implements SessionTodoService {

    private static final String WRITE_TODOS_TOOL = "write_todos";
    /** assistant 角色值，避免字面量散落 */
    private static final String ROLE_ASSISTANT = "assistant";

    private final MessageService messageService;
    private final ObjectMapper objectMapper;

    @Override
    public List<TodoItemVO> listByRequest(Long sessionId, String parentRequestId) {
        if (sessionId == null || parentRequestId == null || parentRequestId.isBlank()) {
            return List.of();
        }
        // 1. 按 parentRequestId 取本轮助手消息，迭代取最后一次 write_todos 成功结果
        List<Message> messages = messageService.listAssistantByRequestId(sessionId, parentRequestId);
        return extractLatestTodos(messages);
    }

    @Override
    public List<TodoItemVO> listLatestBySession(Long sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        // 1. 按会话拉全部消息，过滤出助手消息，取最后一次 write_todos 成功结果
        List<Message> all = messageService.listBySessionId(sessionId);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<Message> assistantMessages = new ArrayList<>();
        for (Message message : all) {
            if (ROLE_ASSISTANT.equals(message.getRole())) {
                assistantMessages.add(message);
            }
        }
        return extractLatestTodos(assistantMessages);
    }

    /**
     * 从助手消息列表提取最后一次成功的 write_todos todos 快照。
     * <p>消息已按时间正序，迭代覆盖即可；解析失败的单条事件跳过。</p>
     */
    private List<TodoItemVO> extractLatestTodos(List<Message> messages) {
        List<TodoItemVO> latest = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return latest;
        }
        for (Message message : messages) {
            String metadata = message.getMetadata();
            if (metadata == null || metadata.isBlank()) {
                continue;
            }
            try {
                JsonNode toolEvents = objectMapper.readTree(metadata).path("toolEvents");
                if (!toolEvents.isArray()) {
                    continue;
                }
                // 2. 同一条消息内可能出现多次 write_todos（先初稿再细化），取最后一次成功的快照
                for (JsonNode event : toolEvents) {
                    if (!"tool_result".equals(event.path("type").asText())) {
                        continue;
                    }
                    if (!WRITE_TODOS_TOOL.equals(event.path("toolName").asText())) {
                        continue;
                    }
                    JsonNode result = readTree(event.path("result").asText(null));
                    if (!result.path("success").asBoolean(false)) {
                        continue;
                    }
                    JsonNode todosNode = result.path("todos");
                    if (!todosNode.isArray()) {
                        continue;
                    }
                    latest = new ArrayList<>();
                    for (JsonNode item : todosNode) {
                        TodoItemVO vo = new TodoItemVO();
                        vo.setId(item.path("id").asText(null));
                        vo.setContent(item.path("content").asText(null));
                        vo.setStatus(item.path("status").asText("pending"));
                        latest.add(vo);
                    }
                }
            } catch (Exception e) {
                log.debug("[SessionTodo] 跳过无法解析的消息元数据: {}", e.getMessage());
            }
        }
        return latest;
    }

    private JsonNode readTree(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return objectMapper.nullNode();
        }
    }
}
