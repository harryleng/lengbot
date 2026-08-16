package com.lengbot.util;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM 消息上下文准备：估算字符数、裁剪工具轮次、规范化空内容。
 * <p>SubAgent 与主 Chat 工具循环共用，避免工具结果撑爆模型输入上限。</p>
 */
@Slf4j
public final class ChatMessageContextUtil {

    /** 主 Chat 默认工具上下文字符上限 */
    public static final int DEFAULT_MAX_TOOL_CONTEXT_CHARS = 60_000;
    /** DashScope 输入上限 202745，留安全余量 */
    public static final int DASHSCOPE_SAFE_INPUT_CHARS = 180_000;
    /** 单条工具结果最大字符数 */
    public static final int MAX_SINGLE_TOOL_RESULT_CHARS = 50_000;
    /** 保留最近 N 轮完整工具调用 */
    public static final int DEFAULT_TOOL_ROUNDS_TO_KEEP = 2;

    private static final String EMPTY_TASK_PLACEHOLDER = "（无任务描述）";
    private static final String EMPTY_SYSTEM_PLACEHOLDER = "You are a helpful assistant.";
    private static final String TOOL_CALL_PLACEHOLDER = " ";
    private static final String EMPTY_TOOL_RESULT = "{}";

    private ChatMessageContextUtil() {
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 将工具入参 Map 序列化为 JSON 字符串（供参数压缩启发式使用） */
    private static String serializeInput(Map<String, Object> input) {
        if (input == null) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(input);
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }

    /** 将压缩后的 JSON 字符串解析回入参 Map（解析失败则保留为单 key content 文本） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseInput(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("content", json);
        }
    }

    // ==================== 消息文本与角色辅助方法 ====================

    /** 从 Msg 中提取纯文本 */
    private static String getMsgText(Msg msg) {
        if (msg == null || msg.getContent() == null) return "";
        return msg.getContent().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    /** 判断 Msg 是否包含工具调用 */
    private static boolean hasMsgToolCalls(Msg msg) {
        if (msg == null || msg.getContent() == null) return false;
        return msg.getContent().stream().anyMatch(b -> b instanceof ToolUseBlock);
    }

    /** 获取 Msg 中的 ToolUseBlock 列表 */
    private static List<ToolUseBlock> getMsgToolCalls(Msg msg) {
        if (msg == null || msg.getContent() == null) return List.of();
        return msg.getContent().stream()
                .filter(b -> b instanceof ToolUseBlock)
                .map(b -> (ToolUseBlock) b)
                .toList();
    }

    /** 获取 Msg 中的 ToolResultBlock 列表 */
    private static List<ToolResultBlock> getMsgToolResults(Msg msg) {
        if (msg == null || msg.getContent() == null) return List.of();
        return msg.getContent().stream()
                .filter(b -> b instanceof ToolResultBlock)
                .map(b -> (ToolResultBlock) b)
                .toList();
    }

    /** 从 ToolResultBlock 提取文本数据 */
    private static String getToolResultData(ToolResultBlock tr) {
        if (tr.getOutput() != null) {
            return tr.getOutput().stream()
                    .filter(b -> b instanceof TextBlock)
                    .map(b -> ((TextBlock) b).getText())
                    .collect(Collectors.joining());
        }
        return "";
    }

    /**
     * 估算单条消息占用的字符数（含工具调用参数和工具结果数据）
     *
     * @param msg AgentScope Msg
     * @return 字符数
     */
    public static int estimateMessageChars(Msg msg) {
        if (msg == null) {
            return 0;
        }
        if (msg.getRole() == MsgRole.TOOL) {
            int total = 0;
            for (ToolResultBlock tr : getMsgToolResults(msg)) {
                String data = getToolResultData(tr);
                if (data != null) {
                    total += data.length();
                }
            }
            return total;
        }
        if (msg.getRole() == MsgRole.ASSISTANT) {
            int total = 0;
            String text = getMsgText(msg);
            if (text != null) {
                total += text.length();
            }
            // write_file 等大参数在 toolCalls.input 中，必须计入（对标 Yuxi）
            List<ToolUseBlock> toolCalls = getMsgToolCalls(msg);
            if (!toolCalls.isEmpty()) {
                for (ToolUseBlock tc : toolCalls) {
                    if (tc.getInput() != null) {
                        total += tc.getInput().toString().length();
                    }
                    if (tc.getName() != null) {
                        total += tc.getName().length();
                    }
                }
            }
            return total;
        }
        String text = getMsgText(msg);
        return text != null ? text.length() : 0;
    }

    /**
     * 估算消息列表总字符数
     *
     * @param messages 消息列表
     * @return 总字符数
     */
    public static int estimateTotalChars(List<Msg> messages) {
        int total = 0;
        for (Msg msg : messages) {
            total += estimateMessageChars(msg);
        }
        return total;
    }

    /**
     * 规范化消息内容，避免 DashScope 等提供商因空 content 报 InvalidParameter
     *
     * @param messages 消息列表（原地替换）
     */
    public static void normalizeMessagesForLlm(List<Msg> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            MsgRole role = msg.getRole();
            if (role == MsgRole.SYSTEM) {
                String text = getMsgText(msg);
                if (text == null || text.isBlank()) {
                    messages.set(i, Msgs.system(EMPTY_SYSTEM_PLACEHOLDER));
                }
            } else if (role == MsgRole.USER) {
                String text = getMsgText(msg);
                if (text == null || text.isBlank()) {
                    messages.set(i, Msgs.user(EMPTY_TASK_PLACEHOLDER));
                }
            } else if (role == MsgRole.ASSISTANT) {
                String text = getMsgText(msg);
                List<ToolUseBlock> toolCalls = getMsgToolCalls(msg);
                boolean needBlankFix = (text == null || text.isBlank()) && !toolCalls.isEmpty();
                List<ToolUseBlock> compacted = compactLargeToolCallArgs(msg);
                if (needBlankFix || compacted != null) {
                    List<ContentBlock> blocks = new ArrayList<>();
                    blocks.add(TextBlock.builder().text(needBlankFix ? TOOL_CALL_PLACEHOLDER : text).build());
                    blocks.addAll(compacted != null ? compacted : toolCalls);
                    messages.set(i, Msg.builderForRole(MsgRole.ASSISTANT).content(blocks).build());
                }
            } else if (role == MsgRole.TOOL) {
                List<ToolResultBlock> results = getMsgToolResults(msg);
                List<ToolResultBlock> normalized = new ArrayList<>();
                for (ToolResultBlock tr : results) {
                    normalized.add(ToolResultBlock.of(
                            tr.getId(), tr.getName(),
                            TextBlock.builder().text(ensureNonEmptyToolResult(getToolResultData(tr))).build()));
                }
                messages.set(i, Msg.builderForRole(MsgRole.TOOL).content(new ArrayList<>(normalized)).build());
            }
        }
    }

    /**
     * 工具结果不得为空，否则部分模型 API 会拒绝
     *
     * @param result 工具返回
     * @return 非空字符串
     */
    public static String ensureNonEmptyToolResult(String result) {
        if (result == null || result.isBlank()) {
            return EMPTY_TOOL_RESULT;
        }
        return result;
    }

    /**
     * 截断单条工具结果
     *
     * @param result 工具返回
     * @param maxLen 最大长度
     * @return 截断后的结果
     */
    public static String capToolResult(String result, int maxLen) {
        String safe = ensureNonEmptyToolResult(result);
        if (safe.length() <= maxLen) {
            return safe;
        }
        return safe.substring(0, maxLen) + "\n...(工具结果已截断，共 " + safe.length() + " 字符)";
    }

    /**
     * 使用默认阈值裁剪工具调用上下文
     *
     * @param messages 消息列表（原地修改）
     */
    public static void trimToolCallContext(List<Msg> messages) {
        trimToolCallContext(messages, DEFAULT_MAX_TOOL_CONTEXT_CHARS, DEFAULT_TOOL_ROUNDS_TO_KEEP);
    }

    /**
     * 工具调用上下文裁剪：超过阈值时压缩早期工具轮次为摘要 SystemMessage
     *
     * @param messages      消息列表（原地修改）
     * @param maxChars      字符上限
     * @param roundsToKeep  保留最近完整工具轮次数
     */
    public static void trimToolCallContext(List<Msg> messages, int maxChars, int roundsToKeep) {
        int totalChars = estimateTotalChars(messages);
        if (totalChars <= maxChars) {
            return;
        }

        List<int[]> rounds = new ArrayList<>();
        for (int i = 0; i < messages.size() - 1; i++) {
            Msg cur = messages.get(i);
            if (cur.getRole() == MsgRole.ASSISTANT && hasMsgToolCalls(cur)) {
                Msg next = messages.get(i + 1);
                if (next.getRole() == MsgRole.TOOL) {
                    rounds.add(new int[]{i, i + 1});
                    i++;
                }
            }
        }

        if (rounds.size() <= roundsToKeep) {
            // 轮次不多但总字符仍超限：截断每条 ToolResult 的 responseData
            shrinkToolResponsePayloads(messages, maxChars);
            return;
        }

        int compressUpTo = rounds.size() - roundsToKeep;
        int removeStart = rounds.get(0)[0];
        int removeEnd = rounds.get(compressUpTo - 1)[1];

        int toolCount = 0;
        for (int r = 0; r < compressUpTo; r++) {
            Msg am = messages.get(rounds.get(r)[0]);
            toolCount += getMsgToolCalls(am).size();
        }

        String summary = "[已省略第 1-" + compressUpTo + " 轮工具调用详情，共执行 "
                + toolCount + " 个工具，上下文已压缩]";
        List<Msg> trimmed = new ArrayList<>(messages);
        for (int i = removeEnd; i >= removeStart; i--) {
            trimmed.remove(i);
        }
        trimmed.add(removeStart, Msgs.system(summary));
        messages.clear();
        messages.addAll(trimmed);

        log.info("[ContextTrim] 压缩了 {} 轮工具调用（{} 个工具），消息字符 {} → ~{}",
                compressUpTo, toolCount, totalChars, estimateTotalChars(messages));

        if (estimateTotalChars(messages) > maxChars) {
            shrinkToolResponsePayloads(messages, maxChars);
        }
    }

    /**
     * 压缩助手消息中 write/append 类大参数（对标 Yuxi L1）。
     *
     * @param msg 助手消息
     * @return 压缩后的 toolCalls；无需压缩时返回 null
     */
    public static List<ToolUseBlock> compactLargeToolCallArgs(Msg msg) {
        if (msg == null || !hasMsgToolCalls(msg)) {
            return null;
        }
        List<ToolUseBlock> original = getMsgToolCalls(msg);
        List<ToolUseBlock> compacted = new ArrayList<>(original.size());
        boolean changed = false;
        for (ToolUseBlock tc : original) {
            String name = tc.getName();
            // getInput() 是 Map，序列化为 JSON 字符串供 compactWriteStyleArgs 启发式解析
            String args = serializeInput(tc.getInput());
            String nextArgs = compactWriteStyleArgs(name, args);
            if (nextArgs != null && !nextArgs.equals(args)) {
                changed = true;
                compacted.add(ToolUseBlock.builder()
                        .id(tc.getId())
                        .name(name)
                        .input(parseInput(nextArgs))
                        .build());
            } else {
                compacted.add(tc);
            }
        }
        return changed ? compacted : null;
    }

    /**
     * 将含超长字符串的写文件参数替换为短摘要。
     * <p>不依赖 Spring Bean：用轻量启发式识别 sandbox_write_file / sandbox_append_file。</p>
     */
    static String compactWriteStyleArgs(String toolName, String args) {
        if (toolName == null || args == null || args.isBlank()) {
            return args;
        }
        if (!"sandbox_write_file".equals(toolName) && !"sandbox_append_file".equals(toolName)) {
            return args;
        }
        // 合法 JSON：压缩超长字符串字段
        if (args.length() <= ToolArgsSanitizer.HISTORY_ARG_MAX_LENGTH * 2
                && !args.contains("\"content\"")) {
            return args;
        }
        try {
            // 避免 agent→tool 循环依赖 ObjectMapper 业务逻辑：简单截断 content 值
            int key = args.indexOf("\"content\"");
            if (key < 0) {
                if (args.length() > ToolArgsSanitizer.HISTORY_ARG_MAX_LENGTH) {
                    return args.substring(0, 20) + "...(argument truncated for context view)";
                }
                return args;
            }
            int colon = args.indexOf(':', key);
            int quoteStart = args.indexOf('"', colon + 1);
            if (quoteStart < 0) {
                return args.substring(0, Math.min(20, args.length()))
                        + "...(argument truncated for context view)";
            }
            int valueStart = quoteStart + 1;
            // 找 content 字符串结束（考虑转义）；找不到则整段截断
            int valueEnd = -1;
            boolean escaped = false;
            for (int i = valueStart; i < args.length(); i++) {
                char c = args.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    valueEnd = i;
                    break;
                }
            }
            String prefix = args.substring(0, valueStart);
            String suffix = valueEnd >= 0 ? args.substring(valueEnd) : "\"}";
            String preview = args.substring(valueStart, Math.min(valueStart + 20,
                    valueEnd >= 0 ? valueEnd : args.length()));
            return prefix + preview + "...(argument truncated for context view)" + suffix;
        } catch (Exception e) {
            return args.substring(0, Math.min(20, args.length()))
                    + "...(argument truncated for context view)";
        }
    }

    private static void shrinkToolResponsePayloads(List<Msg> messages, int maxChars) {
        int total = estimateTotalChars(messages);
        if (total <= maxChars) {
            return;
        }
        // 从最早的 ToolResult 开始截断，直到低于上限
        for (int i = 0; i < messages.size() && estimateTotalChars(messages) > maxChars; i++) {
            Msg msg = messages.get(i);
            if (msg.getRole() != MsgRole.TOOL) {
                continue;
            }
            List<ToolResultBlock> results = getMsgToolResults(msg);
            List<ToolResultBlock> shrunk = new ArrayList<>();
            for (ToolResultBlock tr : results) {
                int budget = Math.max(512, maxChars / Math.max(1, results.size()));
                shrunk.add(ToolResultBlock.of(
                        tr.getId(), tr.getName(),
                        TextBlock.builder().text(capToolResult(getToolResultData(tr), budget)).build()));
            }
            messages.set(i, Msg.builderForRole(MsgRole.TOOL).content(new ArrayList<>(shrunk)).build());
        }
        log.info("[ContextTrim] 截断工具结果后字符数: {}", estimateTotalChars(messages));
    }
}
