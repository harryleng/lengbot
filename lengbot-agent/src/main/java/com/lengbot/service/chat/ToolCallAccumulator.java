package com.lengbot.service.chat;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 流式生成 tool_call.arguments 期间的累积器
 * <p>用于在 args（尤其是 sandbox_write_file 的大段 content）流式累积时，
 * 节流推送「正在生成 xxx · 已输出 N 字」进度，避免前端长时间只见裸 spinner。</p>
 * <p>MimoChatClient（SSE delta）与 ChatServiceImpl（SpringAI Flux chunk）共用。</p>
 */
@Data
@NoArgsConstructor
public class ToolCallAccumulator {

    /** 容量维度节流：args 增长至少 N 字符才推送 */
    public static final int THROTTLE_CHARS = 200;
    /** 时间维度节流：距上次推送至少 N 毫秒才推送 */
    public static final long THROTTLE_MS = 500L;

    private static final Pattern PATH_PATTERN = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");

    /** 累积器主键（toolCallId 或 stream index） */
    private String toolCallKey;
    /** 工具名（首个带 name 的 chunk 写入） */
    private String toolName;
    /** 从部分 JSON args 中正则提取的 path，只提取一次 */
    private String path;
    /** 当前累积 args 总长度 */
    private int argsLen;
    /** 上次推送时的 args 长度 */
    private int lastPushedLen;
    /** 上次推送时间戳；0 表示尚未推送 */
    private long lastPushedAt;
    /** 创建时间，用于首次推送的时间节流基准 */
    private long createdAt = System.currentTimeMillis();
    /** args 文本缓冲（兼容 delta 增量与 cumulative 全量两种协议） */
    private final StringBuilder argsBuffer = new StringBuilder();

    /**
     * 是否为文件写入类工具（需要推送生成进度）
     *
     * @param name 工具名
     * @return true 表示 write/append
     */
    public static boolean isFileWritingTool(String name) {
        return "sandbox_write_file".equals(name) || "sandbox_append_file".equals(name);
    }

    /**
     * 接收一段 arguments 片段并更新缓冲长度
     * <p>兼容两种上游形态：OpenAI 风格的 delta 追加，以及部分适配器给出的 cumulative 全量。</p>
     *
     * @param fragment 本 chunk 的 arguments 文本
     */
    public void acceptArgsFragment(String fragment) {
        if (fragment == null || fragment.isEmpty()) {
            return;
        }
        String cur = argsBuffer.toString();
        if (cur.isEmpty()) {
            argsBuffer.append(fragment);
        } else if (fragment.startsWith(cur) || (fragment.length() > cur.length() && cur.length() < 64
                && fragment.charAt(0) == '{')) {
            // cumulative：新片段覆盖旧内容
            argsBuffer.setLength(0);
            argsBuffer.append(fragment);
        } else if (cur.startsWith(fragment) && fragment.length() < cur.length()) {
            // 更短的重复快照，忽略
            return;
        } else {
            // delta：追加
            argsBuffer.append(fragment);
        }
        argsLen = argsBuffer.length();
    }

    /**
     * 从累积 args 前缀中提取 path（JSON 可能尚不完整，用正则）
     */
    public void tryExtractPath() {
        if (path != null && !path.isBlank()) {
            return;
        }
        Matcher m = PATH_PATTERN.matcher(argsBuffer);
        if (m.find()) {
            path = m.group(1);
        }
    }

    /**
     * 是否满足节流条件（容量 ≥200 或时间 ≥500ms）
     *
     * @return true 表示可以推送一条进度
     */
    public boolean shouldPush() {
        long now = System.currentTimeMillis();
        long since = lastPushedAt > 0 ? now - lastPushedAt : now - createdAt;
        return (argsLen - lastPushedLen >= THROTTLE_CHARS) || since >= THROTTLE_MS;
    }

    /** 标记已推送，更新节流基准 */
    public void markPushed() {
        lastPushedLen = argsLen;
        lastPushedAt = System.currentTimeMillis();
    }

    /**
     * 取 path 的 basename（文件名）
     *
     * @return 文件名；path 为空时返回 null
     */
    public String basename() {
        if (path == null || path.isBlank()) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
