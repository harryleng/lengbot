package com.lengbot.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工具事件持久化压缩：合并 SubAgent 流式 token，提取可读 replyText，对齐 contentOffset。
 */
@Slf4j
public final class ToolEventCompactUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolEventCompactUtil() {
    }

    /**
     * 压缩 toolEvents 后再写入 message metadata，减少 SubAgent token 碎片占用。
     *
     * @param events 原始事件列表
     * @return 压缩后的事件列表
     */
    public static List<Map<String, Object>> compactForPersistence(List<Map<String, Object>> events) {
        return compactForPersistence(events, null);
    }

    /**
     * 压缩 toolEvents 并根据最终正文重新对齐 contentOffset（入库前调用）。
     *
     * @param events       原始事件列表
     * @param finalContent 最终落库正文
     * @return 压缩后的事件列表
     */
    public static List<Map<String, Object>> compactForPersistence(List<Map<String, Object>> events,
                                                                    String finalContent) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> pendingToken = null;

        for (Map<String, Object> evt : events) {
            if (evt == null) {
                continue;
            }
            String type = stringVal(evt.get("type"));
            if ("subagent_token".equals(type)) {
                if (pendingToken != null && sameSubagentScope(pendingToken, evt)) {
                    appendTokenContent(pendingToken, evt);
                } else {
                    flushToken(result, pendingToken);
                    pendingToken = new LinkedHashMap<>(evt);
                }
                continue;
            }
            flushToken(result, pendingToken);
            pendingToken = null;
            if ("subagent_result".equals(type)) {
                result.add(enrichSubagentResult(new LinkedHashMap<>(evt)));
            } else {
                result.add(new LinkedHashMap<>(evt));
            }
        }
        flushToken(result, pendingToken);
        dedupeSubagentTokens(result);
        if (finalContent != null) {
            realignContentOffsets(result, finalContent);
        } else {
            stripContentPrefixAnchors(result);
        }
        return result;
    }

    /**
     * 提取工具块 offset（tool_call / subagent_call / subagent_batch_start，与前端分段规则一致）
     *
     * @param events 事件列表
     * @return 排序去重后的 offset 列表
     */
    public static List<Integer> extractToolBlockOffsets(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        Set<Integer> offsets = new HashSet<>();
        for (Map<String, Object> evt : events) {
            if (evt == null) {
                continue;
            }
            String type = stringVal(evt.get("type"));
            if (!"tool_call".equals(type) && !"subagent_call".equals(type)
                    && !"subagent_batch_start".equals(type)) {
                continue;
            }
            Object co = evt.get("contentOffset");
            if (co instanceof Number n) {
                offsets.add(n.intValue());
            }
        }
        return offsets.stream().sorted().toList();
    }

    /**
     * 从 SubAgent 委派工具返回 JSON 中提取 reply 文本
     *
     * @param resultJson 工具返回 JSON
     * @return reply 文本，无法解析时返回 null
     */
    public static String extractSubagentReplyText(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = MAPPER.readValue(resultJson, new TypeReference<>() {});
            Object reply = map.get("reply");
            if (reply != null) {
                String text = reply.toString();
                return text.isBlank() ? null : text;
            }
        } catch (Exception ignored) {
            // 非 JSON：若不像工具 JSON，直接作为展示文本
            if (!resultJson.trim().startsWith("{")) {
                return resultJson;
            }
        }
        return null;
    }

    /**
     * 解析工具块在 assistant 正文中的切分位置（句末对齐，避免「好&lt;组件&gt;的」半词截断）。
     *
     * @param content         正文全文
     * @param anchor          流式时记录的前缀锚点，可为 null
     * @param fallbackOffset  原始 contentOffset
     * @return 切分点（substring(0, n) 为组件前正文）
     */
    public static int resolveToolBlockSplitOffset(String content, String anchor, int fallbackOffset) {
        String text = content != null ? content : "";
        int candidate = resolveOffset(anchor, fallbackOffset, text);
        return alignToSemanticSplitBoundary(text, candidate);
    }

    /**
     * 将 raw 切分点回退到最后一个句末标点之后，避免组件插在半句话中间。
     */
    public static int alignToSemanticSplitBoundary(String content, int rawOffset) {
        if (content == null || content.isEmpty() || rawOffset <= 0) {
            return 0;
        }
        int end = Math.min(Math.max(0, rawOffset), content.length());
        if (end <= 0) {
            return 0;
        }
        for (int i = end; i > 0; i--) {
            char prev = content.charAt(i - 1);
            if (isStrongSentenceEnd(prev)) {
                return skipTrailingOrnaments(content, i);
            }
        }
        for (int i = end; i > 0; i--) {
            if (content.charAt(i - 1) != '\n') {
                continue;
            }
            for (int j = i - 1; j > 0; j--) {
                if (isStrongSentenceEnd(content.charAt(j - 1))) {
                    return skipTrailingOrnaments(content, j);
                }
            }
            if (isTrivialFragment(content, i, end)) {
                return i;
            }
        }
        return end;
    }

    /** 跳过句末标点后紧跟的装饰性尾随字符（emoji、空白、换行），使其归属上一句 */
    private static int skipTrailingOrnaments(String content, int pos) {
        if (pos <= 0 || pos >= content.length() || !isStrongSentenceEnd(content.charAt(pos - 1))) {
            return pos;
        }
        int i = pos;
        while (i < content.length()) {
            int cp = content.codePointAt(i);
            if (!isOrnamentCodePoint(cp)) {
                break;
            }
            i += Character.charCount(cp);
        }
        return i;
    }

    /** 装饰性尾随字符：空白/换行、emoji 主体、ZWJ、变体选择符、keycap、肤色修饰符 */
    private static boolean isOrnamentCodePoint(int cp) {
        if (Character.isWhitespace(cp)) {
            return true;
        }
        if (cp == 0x200D || cp == 0x20E3 || (cp >= 0xFE00 && cp <= 0xFE0F)) {
            return true;
        }
        if (cp >= 0x1F3FB && cp <= 0x1F3FF) {
            return true;
        }
        return (cp >= 0x1F300 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x1F000 && cp <= 0x1F02F) || (cp >= 0x2190 && cp <= 0x21FF)
                || (cp >= 0x2B00 && cp <= 0x2BFF);
    }

    private static boolean isStrongSentenceEnd(char c) {
        return c == '！' || c == '!' || c == '。' || c == '.' || c == '?' || c == '？'
                || c == '；' || c == ';';
    }

    private static boolean isTrivialFragment(String content, int from, int to) {
        if (from >= to) {
            return true;
        }
        String tail = content.substring(from, to).replace("\n", "").trim();
        return tail.length() <= 2;
    }

    private static void realignContentOffsets(List<Map<String, Object>> events, String finalContent) {
        if (events.isEmpty()) {
            return;
        }
        String content = finalContent != null ? finalContent : "";
        Map<Integer, Integer> offsetRemap = new HashMap<>();

        for (Map<String, Object> evt : events) {
            String type = stringVal(evt.get("type"));
            if (!"tool_call".equals(type) && !"subagent_call".equals(type)
                    && !"subagent_batch_start".equals(type)) {
                continue;
            }
            int oldOffset = toInt(evt.get("contentOffset"), 0);
            String anchor = stringVal(evt.get("contentPrefixAnchor"));
            int newOffset = resolveToolBlockSplitOffset(content, anchor.isEmpty() ? null : anchor, oldOffset);
            offsetRemap.put(oldOffset, newOffset);
            evt.put("contentOffset", newOffset);
            // 保留/刷新前缀锚点，供前端历史消息精确切分正文
            if (("subagent_call".equals(type) || "subagent_batch_start".equals(type))
                    && newOffset > 0 && newOffset <= content.length()) {
                evt.put("contentPrefixAnchor", content.substring(0, newOffset));
            } else {
                evt.remove("contentPrefixAnchor");
            }
        }

        for (Map<String, Object> evt : events) {
            String type = stringVal(evt.get("type"));
            if (!"subagent_call".equals(type) && !"subagent_batch_start".equals(type)) {
                evt.remove("contentPrefixAnchor");
            }
            Object co = evt.get("contentOffset");
            if (!(co instanceof Number n)) {
                continue;
            }
            Integer mapped = offsetRemap.get(n.intValue());
            if (mapped != null) {
                evt.put("contentOffset", mapped);
            } else if (n.intValue() > content.length()) {
                evt.put("contentOffset", content.length());
            }
        }
    }

    private static int resolveOffset(String anchor, int fallbackOffset, String finalContent) {
        if (anchor != null && !anchor.isEmpty()) {
            if (finalContent.startsWith(anchor)) {
                return anchor.length();
            }
            int idx = finalContent.indexOf(anchor);
            if (idx >= 0) {
                return idx + anchor.length();
            }
            String sanitizedAnchor = TextNormalizeUtil.sanitizeForDatabase(anchor);
            if (!sanitizedAnchor.equals(anchor)) {
                if (finalContent.startsWith(sanitizedAnchor)) {
                    return sanitizedAnchor.length();
                }
                idx = finalContent.indexOf(sanitizedAnchor);
                if (idx >= 0) {
                    return idx + sanitizedAnchor.length();
                }
            }
            int longestPrefix = longestMatchingPrefix(finalContent, anchor);
            if (longestPrefix > 0) {
                return longestPrefix;
            }
        }
        return Math.min(Math.max(0, fallbackOffset), finalContent.length());
    }

    private static int longestMatchingPrefix(String content, String anchor) {
        int limit = Math.min(content.length(), anchor.length());
        int matched = 0;
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) != anchor.charAt(i)) {
                break;
            }
            matched = i + 1;
        }
        return matched;
    }

    private static void stripContentPrefixAnchors(List<Map<String, Object>> events) {
        for (Map<String, Object> evt : events) {
            evt.remove("contentPrefixAnchor");
        }
    }

    /** 已有 subagent_result.replyText 时移除同 scope 的 subagent_token，避免重复存储 */
    private static void dedupeSubagentTokens(List<Map<String, Object>> events) {
        Set<String> resultScopes = new HashSet<>();
        for (Map<String, Object> evt : events) {
            if ("subagent_result".equals(stringVal(evt.get("type"))) && evt.get("replyText") != null) {
                resultScopes.add(subagentScopeKey(evt));
            }
        }
        if (resultScopes.isEmpty()) {
            return;
        }
        events.removeIf(evt -> "subagent_token".equals(stringVal(evt.get("type")))
                && resultScopes.contains(subagentScopeKey(evt)));
    }

    private static String subagentScopeKey(Map<String, Object> evt) {
        String taskId = stringVal(evt.get("task_id"));
        if (!taskId.isEmpty()) {
            return "task:" + taskId;
        }
        String batchId = stringVal(evt.get("batch_id"));
        return stringVal(evt.get("subagentName")) + "@" + stringVal(evt.get("contentOffset"))
                + "@" + batchId;
    }

    private static void flushToken(List<Map<String, Object>> result, Map<String, Object> pending) {
        if (pending == null) {
            return;
        }
        String content = stringVal(pending.get("content"));
        if (!content.isEmpty()) {
            result.add(pending);
        }
    }

    private static void appendTokenContent(Map<String, Object> target, Map<String, Object> delta) {
        String existing = stringVal(target.get("content"));
        String addition = stringVal(delta.get("content"));
        target.put("content", existing + addition);
    }

    private static boolean sameSubagentScope(Map<String, Object> a, Map<String, Object> b) {
        return Objects.equals(a.get("subagentName"), b.get("subagentName"))
                && Objects.equals(a.get("contentOffset"), b.get("contentOffset"))
                && Objects.equals(a.get("batch_id"), b.get("batch_id"))
                && Objects.equals(a.get("task_id"), b.get("task_id"));
    }

    private static Map<String, Object> enrichSubagentResult(Map<String, Object> evt) {
        if (!evt.containsKey("replyText")) {
            String reply = extractSubagentReplyText(stringVal(evt.get("result")));
            if (reply != null && !reply.isBlank()) {
                evt.put("replyText", reply);
            }
        }
        return evt;
    }

    private static int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return defaultValue;
    }

    private static String stringVal(Object v) {
        return v != null ? v.toString() : "";
    }
}
