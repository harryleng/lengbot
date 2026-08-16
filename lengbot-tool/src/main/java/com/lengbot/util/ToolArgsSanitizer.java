package com.lengbot.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具调用参数清理与大参数压缩。
 * <p>对标 Yuxi summary L1：历史上下文中的 write/edit 大参数改为短摘要，避免撑爆下一轮模型输入；
 * 并对模型输出截断导致的不完整 JSON 做写文件场景的修复尝试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolArgsSanitizer {

    private final ObjectMapper objectMapper;

    /** 对话场景下由 ToolCallParam 注入、不应出现在 LLM 参数中的字段 */
    private static final Set<String> CONTEXT_INJECTED_KEYS = Set.of("agentId", "requestId");

    /** 需要对大字符串参数做历史压缩的工具（对标 Yuxi write_file / edit_file） */
    private static final Set<String> LARGE_CONTENT_TOOLS = Set.of(
            "sandbox_write_file",
            "sandbox_append_file"
    );

    /** 历史上下文中单字段字符串超过该长度则压缩为短摘要 */
    public static final int HISTORY_ARG_MAX_LENGTH = 120;

    private static final String TRUNCATED_ARG_MARK = "...(argument truncated for context view)";
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "\"path\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"");

    /**
     * 从工具参数 JSON 中移除由 ToolCallParam 注入的字段（含 LLM 误传的空 agentId）
     */
    public String forChatCall(String args) {
        return stripKeys(args, CONTEXT_INJECTED_KEYS, false);
    }

    /**
     * 测试工具：agentId 已由调用方写入 ToolCallParam，从 JSON 中移除避免多余字段
     */
    public String forTestCall(String args) {
        return stripKeys(args, Set.of("agentId"), false);
    }

    /**
     * 将大文件写入类工具参数压缩为短摘要，供后续模型上下文使用（对标 Yuxi L1）。
     * <p>例如 {@code content} 超长时替换为前缀 + {@code ...(argument truncated for context view)}。</p>
     *
     * @param toolName 工具名
     * @param args     原始参数 JSON
     * @return 压缩后的参数；非目标工具或不需压缩时返回原串
     */
    public String compactForHistory(String toolName, String args) {
        if (toolName == null || !LARGE_CONTENT_TOOLS.contains(toolName)) {
            return args != null ? args : "{}";
        }
        if (args == null || args.isBlank()) {
            return "{}";
        }
        try {
            JsonNode root = objectMapper.readTree(args);
            if (!(root instanceof ObjectNode obj)) {
                return truncateRawArgs(args, HISTORY_ARG_MAX_LENGTH);
            }
            boolean changed = false;
            ObjectNode copy = obj.deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = copy.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode val = entry.getValue();
                if (val != null && val.isTextual() && val.asText().length() > HISTORY_ARG_MAX_LENGTH) {
                    String text = val.asText();
                    String preview = text.substring(0, Math.min(20, text.length())) + TRUNCATED_ARG_MARK;
                    copy.put(entry.getKey(), preview);
                    changed = true;
                }
            }
            return changed ? objectMapper.writeValueAsString(copy) : args;
        } catch (Exception e) {
            // 截断未闭合的 JSON：历史中只保留短摘要，避免把半截大 content 回灌模型
            return truncateRawArgs(args, HISTORY_ARG_MAX_LENGTH);
        }
    }

    /**
     * 尝试修复因 maxTokens 截断而未闭合的写文件参数 JSON。
     * <p>仅处理 {@code sandbox_write_file}/{@code sandbox_append_file}：抽出 path，尽量挽救 content 前缀并闭合 JSON。</p>
     *
     * @param toolName 工具名
     * @param args     可能被截断的参数
     * @return 修复后的完整 JSON；无法修复时返回 null
     */
    public String tryRepairTruncatedWriteArgs(String toolName, String args) {
        if (toolName == null || !LARGE_CONTENT_TOOLS.contains(toolName)) {
            return null;
        }
        if (args == null || args.isBlank()) {
            return null;
        }
        try {
            objectMapper.readTree(args);
            return null; // 已是合法 JSON，无需修复
        } catch (Exception ignored) {
            // continue repair
        }

        Matcher pathMatcher = PATH_PATTERN.matcher(args);
        if (!pathMatcher.find()) {
            return null;
        }
        String path = unescapeJsonString(pathMatcher.group(1));
        if (path == null || path.isBlank()) {
            return null;
        }

        String content = extractPartialContent(args);
        if (content == null) {
            content = "";
        }
        // 截断常发生在字符串中部，去掉可能残缺的尾部半个 escape；已收到的正文全部保留，不再二次截断
        content = stripIncompleteTrailingEscape(content);

        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("path", path);
            node.put("content", content);
            node.put("_repairedFromTruncation", true);
            String repaired = objectMapper.writeValueAsString(node);
            log.warn("[ToolArgs] 修复截断的 {} 参数: path={}, contentLen={}", toolName, path, content.length());
            return repaired;
        } catch (Exception e) {
            log.warn("[ToolArgs] 修复截断参数失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractPartialContent(String args) {
        int keyIdx = args.indexOf("\"content\"");
        if (keyIdx < 0) {
            return null;
        }
        int colon = args.indexOf(':', keyIdx + "\"content\"".length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < args.length() && Character.isWhitespace(args.charAt(i))) {
            i++;
        }
        if (i >= args.length() || args.charAt(i) != '"') {
            return null;
        }
        i++; // skip opening quote
        StringBuilder escaped = new StringBuilder();
        boolean inEscape = false;
        for (; i < args.length(); i++) {
            char c = args.charAt(i);
            if (inEscape) {
                escaped.append(c);
                inEscape = false;
                continue;
            }
            if (c == '\\') {
                escaped.append(c);
                inEscape = true;
                continue;
            }
            if (c == '"') {
                break; // normal end
            }
            escaped.append(c);
        }
        // 截断时常无结束引号；若停在半截 escape，丢掉末尾单独的反斜杠
        String raw = escaped.toString();
        if (inEscape && raw.endsWith("\\")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        return unescapeJsonString(raw);
    }

    private static String stripIncompleteTrailingEscape(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // 若末尾是单独的反斜杠，去掉以免后续再编码出错
        if (content.endsWith("\\") && (content.length() == 1 || content.charAt(content.length() - 2) != '\\')) {
            return content.substring(0, content.length() - 1);
        }
        return content;
    }

    private static String unescapeJsonString(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '"', '\\', '/' -> sb.append(n);
                    case 'u' -> {
                        if (i + 4 < raw.length()) {
                            try {
                                int cp = Integer.parseInt(raw.substring(i + 1, i + 5), 16);
                                sb.append((char) cp);
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append('u');
                            }
                        } else {
                            sb.append('u');
                        }
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String truncateRawArgs(String args, int maxLen) {
        if (args == null) {
            return "{}";
        }
        if (args.length() <= maxLen) {
            return args;
        }
        return args.substring(0, Math.min(20, args.length())) + TRUNCATED_ARG_MARK;
    }

    private String stripKeys(String args, Set<String> keys, boolean stripBlankOnly) {
        if (args == null || args.isBlank()) {
            return "{}";
        }
        try {
            JsonNode root = objectMapper.readTree(args);
            if (!(root instanceof ObjectNode obj)) {
                return args;
            }
            boolean changed = false;
            for (String key : keys) {
                if (!obj.has(key)) {
                    continue;
                }
                JsonNode val = obj.get(key);
                if (!stripBlankOnly || isBlankOrNull(val)) {
                    obj.remove(key);
                    changed = true;
                }
            }
            return changed ? objectMapper.writeValueAsString(obj) : args;
        } catch (Exception e) {
            return args;
        }
    }

    private boolean isBlankOrNull(JsonNode val) {
        if (val == null || val.isNull()) {
            return true;
        }
        if (val.isTextual()) {
            return val.asText().isBlank();
        }
        return false;
    }
}
