package com.lengbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.entity.Knowledge;
import com.lengbot.entity.Message;
import com.lengbot.entity.Skill;
import com.lengbot.entity.SubAgent;
import com.lengbot.entity.Tool;
import com.lengbot.enums.MessageRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为 Trace 回显构建 mention 快照：优先 message.metadata，缺失时从正文 token 解析并查库补全名称。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MentionTraceSnapshotService {

    private static final Pattern MENTION_TOKEN_PATTERN =
            Pattern.compile("@(knowledge|subagent|skill|tool):(\\d+)");

    private final ObjectMapper objectMapper;
    private final ToolService toolService;
    private final SubAgentService subAgentService;
    private final KnowledgeService knowledgeService;
    private final SkillService skillService;

    /**
     * 为 Trace 中的 user 消息解析 mention 快照。
     *
     * @param dbMessage  匹配到的会话消息（可为 null）
     * @param traceContent Trace 中展示的 user 正文
     * @return mention 快照列表
     */
    public List<Map<String, Object>> resolveForTraceUser(Message dbMessage, String traceContent) {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        Set<String> seenTokens = new LinkedHashSet<>();

        if (dbMessage != null && dbMessage.getRole() == MessageRole.USER) {
            for (Map<String, Object> snap : parseMentionsFromMetadata(dbMessage.getMetadata())) {
                String token = tokenOf(snap);
                if (token != null && seenTokens.add(token)) {
                    snapshots.add(snap);
                }
            }
        }

        String content = traceContent;
        if ((content == null || content.isBlank()) && dbMessage != null) {
            content = dbMessage.getContent();
        }
        for (Map<String, Object> snap : resolveFromContent(content)) {
            String token = tokenOf(snap);
            if (token != null && seenTokens.add(token)) {
                snapshots.add(snap);
            }
        }
        return snapshots;
    }

    /**
     * 按正文在会话 user 消息中查找最佳匹配。
     */
    public Message matchDbUserByContent(List<Message> sessionMessages, String traceContent) {
        if (sessionMessages == null || sessionMessages.isEmpty()) {
            return null;
        }
        String traceNorm = stripTraceUserDecorations(traceContent);
        if (traceNorm.isBlank()) {
            return null;
        }

        Message exact = null;
        Message bestSuffix = null;
        int bestSuffixLen = 0;
        Message bestContains = null;
        int bestContainsLen = 0;

        for (Message msg : sessionMessages) {
            if (msg == null || msg.getRole() != MessageRole.USER) {
                continue;
            }
            String dbNorm = msg.getContent() != null ? msg.getContent().trim() : "";
            if (dbNorm.isEmpty()) {
                continue;
            }
            if (traceNorm.equals(dbNorm)) {
                exact = msg;
                break;
            }
            if (traceNorm.endsWith(dbNorm) && dbNorm.length() > bestSuffixLen) {
                bestSuffix = msg;
                bestSuffixLen = dbNorm.length();
            }
            if (dbNorm.equals(traceNorm) || (traceNorm.length() >= 20 && dbNorm.contains(traceNorm))) {
                if (traceNorm.length() > bestContainsLen) {
                    bestContains = msg;
                    bestContainsLen = traceNorm.length();
                }
            }
        }
        if (exact != null) {
            return exact;
        }
        if (bestSuffix != null) {
            return bestSuffix;
        }
        return bestContains;
    }

    /** 去掉 Trace 中 user 消息相对 DB 正文多出的前缀（@ 提示、引用回复等） */
    public static String stripTraceUserDecorations(Object content) {
        if (content == null) {
            return "";
        }
        String s = content.toString().trim();
        if (s.startsWith("[用户 @ 指定")) {
            int idx = s.indexOf("]\n\n");
            if (idx >= 0) {
                s = s.substring(idx + 3).trim();
            }
        }
        if (s.startsWith("[引用消息：")) {
            int idx = s.indexOf("]\n");
            if (idx >= 0) {
                s = s.substring(idx + 2).trim();
            }
        }
        return s;
    }

    private List<Map<String, Object>> resolveFromContent(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> snapshots = new ArrayList<>();
        Matcher matcher = MENTION_TOKEN_PATTERN.matcher(content);
        while (matcher.find()) {
            String type = matcher.group(1);
            String resourceId = matcher.group(2);
            String token = matcher.group(0);
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("type", type);
            snap.put("resourceId", resourceId);
            snap.put("token", token);
            snap.put("name", resolveDisplayName(type, resourceId));
            snapshots.add(snap);
        }
        return snapshots;
    }

    private String resolveDisplayName(String type, String resourceId) {
        Long id = parseId(resourceId);
        if (id == null) {
            return type;
        }
        try {
            return switch (type) {
                case "tool" -> displayName(toolService.getById(id), Tool::getDisplayName, Tool::getName, "tool");
                case "subagent" -> displayName(subAgentService.getById(id), SubAgent::getDisplayName, SubAgent::getName, "subagent");
                case "knowledge" -> displayName(knowledgeService.getById(id), Knowledge::getName, Knowledge::getName, "knowledge");
                case "skill" -> displayName(skillService.getById(id), Skill::getName, Skill::getName, "skill");
                default -> type;
            };
        } catch (Exception e) {
            log.debug("[TraceMention] 解析资源名称失败: type={}, id={}, err={}", type, resourceId, e.getMessage());
            return type;
        }
    }

    private static Long parseId(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(resourceId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <T> String displayName(T entity,
                                          java.util.function.Function<T, String> displayFn,
                                          java.util.function.Function<T, String> nameFn,
                                          String fallback) {
        if (entity == null) {
            return fallback;
        }
        String display = displayFn.apply(entity);
        if (display != null && !display.isBlank()) {
            return display;
        }
        String name = nameFn.apply(entity);
        return name != null && !name.isBlank() ? name : fallback;
    }

    private List<Map<String, Object>> parseMentionsFromMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return List.of();
        }
        try {
            String json = metadata.trim();
            if (json.startsWith("\"") && json.endsWith("\"")) {
                json = objectMapper.readValue(json, String.class);
            }
            Map<String, Object> meta = objectMapper.readValue(json, new TypeReference<>() {});
            Object mentions = meta.get("mentions");
            if (!(mentions instanceof List<?> list)) {
                return List.of();
            }
            List<Map<String, Object>> snapshots = new ArrayList<>(list.size());
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> snap = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        snap.put(entry.getKey().toString(), entry.getValue());
                    }
                }
                if (!snap.containsKey("name") || snap.get("name") == null || snap.get("name").toString().isBlank()) {
                    String type = snap.get("type") != null ? snap.get("type").toString() : null;
                    String resourceId = snap.get("resourceId") != null ? snap.get("resourceId").toString() : null;
                    if (type != null && resourceId != null) {
                        snap.put("name", resolveDisplayName(type, resourceId));
                    }
                }
                snapshots.add(snap);
            }
            return snapshots;
        } catch (Exception e) {
            log.debug("[TraceMention] metadata 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static String tokenOf(Map<String, Object> snap) {
        if (snap == null) {
            return null;
        }
        Object token = snap.get("token");
        if (token != null && !token.toString().isBlank()) {
            return token.toString();
        }
        Object type = snap.get("type");
        Object resourceId = snap.get("resourceId");
        if (type != null && resourceId != null) {
            return "@" + type + ":" + resourceId;
        }
        return null;
    }
}
