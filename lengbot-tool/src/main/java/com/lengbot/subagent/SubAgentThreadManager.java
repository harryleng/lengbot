package com.lengbot.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.util.RedisUtil;
import io.agentscope.core.message.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SubAgent 线程管理器
 * <p>负责子代理线程 ID 的确定性生成和消息历史的 Redis 持久化，支持续跑机制。</p>
 *
 * @author lw
 * @since 2026-06-25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubAgentThreadManager {

    private static final String MSG_KEY_PREFIX = "subagent:msg:";
    private static final long TTL_SECONDS = 24 * 3600; // 24h
    private static final int MAX_MESSAGES = 100;

    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;

    /**
     * 生成确定性子线程 ID（与 Yuxi 的 make_child_thread_id 对齐）
     *
     * @param parentThreadId 父 Agent 线程 ID
     * @param agentName      子代理名称
     * @param toolCallId     工具调用 ID（requestId）
     * @return 确定性线程 ID
     */
    public static String makeChildThreadId(String parentThreadId, String agentName, String toolCallId) {
        String input = (parentThreadId != null ? parentThreadId : "")
                + ":" + (agentName != null ? agentName : "")
                + ":" + (toolCallId != null ? toolCallId : "");
        String digest = DigestUtils.md5DigestAsHex(input.getBytes(StandardCharsets.UTF_8));
        return "subagent_" + digest;
    }

    /**
     * 加载已有消息历史（续跑时使用）
     *
     * @param threadId 线程 ID
     * @return 消息列表，不存在时返回空列表
     */
    public List<Msg> loadMessages(String threadId) {
        if (threadId == null) {
            return new ArrayList<>();
        }
        String json = redisUtil.get(MSG_KEY_PREFIX + threadId);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, new TypeReference<>() {});
            return deserializeMessages(raw);
        } catch (Exception e) {
            log.warn("[SubAgentThread] 消息反序列化失败: threadId={}, error={}", threadId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 保存消息历史
     *
     * @param threadId 线程 ID
     * @param messages 消息列表
     */
    public void saveMessages(String threadId, List<Msg> messages) {
        if (threadId == null || messages == null) {
            return;
        }
        try {
            // 截断过长的消息列表，保留 SystemMessage + 最近消息
            List<Msg> toSave = truncateMessages(messages);
            List<Map<String, Object>> serialized = serializeMessages(toSave);
            String json = objectMapper.writeValueAsString(serialized);
            redisUtil.set(MSG_KEY_PREFIX + threadId, json, TTL_SECONDS);
        } catch (Exception e) {
            log.warn("[SubAgentThread] 消息保存失败: threadId={}, error={}", threadId, e.getMessage());
        }
    }

    /**
     * 检查线程是否存在
     */
    public boolean threadExists(String threadId) {
        if (threadId == null) {
            return false;
        }
        return redisUtil.exists(MSG_KEY_PREFIX + threadId);
    }

    /** 返回可直接提供给前端详情面板的子线程消息快照。 */
    public List<Map<String, Object>> getMessageSnapshot(String threadId) {
        return serializeMessages(loadMessages(threadId));
    }

    /**
     * 截断消息列表：保留首条 SystemMessage + 最近的 MAX_MESSAGES-1 条
     */
    private List<Msg> truncateMessages(List<Msg> messages) {
        if (messages.size() <= MAX_MESSAGES) {
            return messages;
        }
        List<Msg> result = new ArrayList<>();
        // 保留首条 SystemMessage
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage) {
            result.add(messages.get(0));
        }
        // 保留最近的消息
        int start = messages.size() - (MAX_MESSAGES - result.size());
        for (int i = Math.max(start, result.size()); i < messages.size(); i++) {
            result.add(messages.get(i));
        }
        return result;
    }

    /**
     * 序列化消息列表为可存储的 Map 列表（AgentScope Msg → JSON Map）
     */
    private List<Map<String, Object>> serializeMessages(List<Msg> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Msg msg : messages) {
            Map<String, Object> entry = new HashMap<>();
            if (msg instanceof SystemMessage sm) {
                entry.put("type", "system");
                entry.put("content", extractText(sm));
            } else if (msg instanceof UserMessage um) {
                entry.put("type", "user");
                entry.put("content", extractText(um));
            } else if (msg instanceof AssistantMessage am) {
                entry.put("type", "assistant");
                entry.put("content", extractText(am));
                // 提取工具调用块（ToolUseBlock）
                List<ToolUseBlock> toolUses = extractToolUses(am);
                if (!toolUses.isEmpty()) {
                    List<Map<String, Object>> tcList = new ArrayList<>();
                    for (ToolUseBlock tu : toolUses) {
                        Map<String, Object> tcMap = new HashMap<>();
                        tcMap.put("id", tu.getId());
                        tcMap.put("name", tu.getName());
                        tcMap.put("arguments", tu.getContent() != null ? tu.getContent()
                                : serialize(tu.getInput()));
                        tcList.add(tcMap);
                    }
                    entry.put("toolCalls", tcList);
                }
            } else if (msg instanceof ToolResultMessage trm) {
                entry.put("type", "tool_response");
                List<Map<String, Object>> respList = new ArrayList<>();
                for (ContentBlock block : trm.getContent()) {
                    if (block instanceof ToolResultBlock tr) {
                        Map<String, Object> respMap = new HashMap<>();
                        respMap.put("id", tr.getId());
                        respMap.put("name", tr.getName());
                        respMap.put("responseData", extractToolResultText(tr));
                        respList.add(respMap);
                    }
                }
                entry.put("responses", respList);
            }
            result.add(entry);
        }
        return result;
    }

    /**
     * 反序列化 Map 列表为 AgentScope Msg 列表
     */
    private List<Msg> deserializeMessages(List<Map<String, Object>> raw) {
        List<Msg> messages = new ArrayList<>();
        for (Map<String, Object> entry : raw) {
            String type = (String) entry.get("type");
            if (type == null) continue;
            switch (type) {
                case "system" -> messages.add(
                        Msg.builderForRole(MsgRole.SYSTEM)
                                .content(TextBlock.builder().text((String) entry.get("content")).build())
                                .build());
                case "user" -> messages.add(
                        Msg.builderForRole(MsgRole.USER)
                                .content(TextBlock.builder().text((String) entry.get("content")).build())
                                .build());
                case "assistant" -> {
                    String content = (String) entry.get("content");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tcList = (List<Map<String, Object>>) entry.get("toolCalls");
                    AssistantMessage.Builder builder = AssistantMessage.builder();
                    if (content != null && !content.isBlank()) {
                        builder.content(TextBlock.builder().text(content).build());
                    }
                    if (tcList != null) {
                        for (Map<String, Object> tc : tcList) {
                            ToolUseBlock toolUse = ToolUseBlock.builder()
                                    .id((String) tc.get("id"))
                                    .name((String) tc.get("name"))
                                    .content((String) tc.get("arguments"))
                                    .build();
                            builder.content(toolUse);
                        }
                    }
                    messages.add(builder.build());
                }
                case "tool_response" -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> respList = (List<Map<String, Object>>) entry.get("responses");
                    if (respList != null) {
                        ToolResultMessage.Builder builder = ToolResultMessage.builder();
                        for (Map<String, Object> resp : respList) {
                            ToolResultBlock tr = ToolResultBlock.of(
                                    (String) resp.get("id"),
                                    (String) resp.get("name"),
                                    TextBlock.builder().text((String) resp.get("responseData")).build());
                            builder.content(tr);
                        }
                        messages.add(builder.build());
                    }
                }
            }
        }
        return messages;
    }

    /** 将对象序列化为 JSON 字符串（失败时返回空串） */
    private String serialize(Object obj) {
        if (obj == null) return "";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "";
        }
    }

    /** 从消息中提取纯文本（合并所有 TextBlock） */
    private String extractText(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return "";
        }
        return msg.getContent().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
    }

    /** 从助手消息中提取工具调用块 */
    private List<ToolUseBlock> extractToolUses(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return List.of();
        }
        return msg.getContent().stream()
                .filter(block -> block instanceof ToolUseBlock)
                .map(block -> (ToolUseBlock) block)
                .toList();
    }

    /** 从 ToolResultBlock 中提取结果文本 */
    private String extractToolResultText(ToolResultBlock tr) {
        if (tr == null || tr.getOutput() == null) {
            return "";
        }
        return tr.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
    }
}
