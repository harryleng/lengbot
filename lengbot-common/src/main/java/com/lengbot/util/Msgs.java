package com.lengbot.util;

import io.agentscope.core.message.*;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AgentScope 消息工具类
 * <p>提供 Spring AI 风格的消息创建和转换便捷方法，减少项目中各处重复的转换代码</p>
 *
 * @author finch
 * @since 2026-08-01
 */
public final class Msgs {

    private Msgs() {}

    // ==================== 消息创建快捷方法 ====================

    /** 创建系统消息 */
    public static Msg system(String text) {
        return Msg.builderForRole(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /** 创建用户消息（纯文本） */
    public static Msg user(String text) {
        return Msg.builderForRole(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /** 创建用户消息（多内容块，如图片+文本） */
    public static Msg user(List<ContentBlock> blocks) {
        return Msg.builderForRole(MsgRole.USER)
                .content(blocks)
                .build();
    }

    /** 创建助手消息（纯文本） */
    public static Msg assistant(String text) {
        return Msg.builderForRole(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /** 创建助手消息（多内容块） */
    public static Msg assistant(List<ContentBlock> blocks) {
        return Msg.builderForRole(MsgRole.ASSISTANT)
                .content(blocks)
                .build();
    }

    /** 创建工具结果消息 */
    public static Msg toolResult(ToolResultBlock result) {
        return Msg.builderForRole(MsgRole.TOOL)
                .content(result)
                .build();
    }

    /** 创建工具结果消息（文本内容） */
    public static Msg toolResult(String toolCallId, String text) {
        return Msg.builderForRole(MsgRole.TOOL)
                .content(ToolResultBlock.of(toolCallId, null, TextBlock.builder().text(text).build()))
                .build();
    }

    // ==================== 从 ChatResponse 提取文本 ====================

    /**
     * 从 ChatResponse 中提取纯文本内容
     * <p>会合并所有 TextBlock 的文本，忽略非文本内容块</p>
     */
    public static String extractText(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return "";
        }
        return response.getContent().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
    }

    /**
     * 从 ChatResponse 中提取工具调用块
     */
    public static List<ToolUseBlock> extractToolUses(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return List.of();
        }
        return response.getContent().stream()
                .filter(block -> block instanceof ToolUseBlock)
                .map(block -> (ToolUseBlock) block)
                .toList();
    }

    /**
     * 判断 ChatResponse 中是否包含工具调用
     */
    public static boolean hasToolCalls(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return false;
        }
        return response.getContent().stream().anyMatch(block -> block instanceof ToolUseBlock);
    }

    // ==================== ContentBlock 创建快捷方法 ====================

    /** 创建文本内容块 */
    public static TextBlock textBlock(String text) {
        return TextBlock.builder().text(text).build();
    }

    /** 创建图片内容块（Base64） */
    public static ImageBlock imageBlock(String base64Data, String mimeType) {
        return ImageBlock.builder()
                .source(Base64Source.builder()
                        .data(base64Data)
                        .mediaType(mimeType)
                        .build())
                .build();
    }

    /** 创建工具结果内容块 */
    public static ToolResultBlock toolResultBlock(String toolCallId, String text) {
        return ToolResultBlock.of(toolCallId, null, TextBlock.builder().text(text).build());
    }

    /** 创建错误工具结果 */
    public static ToolResultBlock toolErrorBlock(String toolCallId, String errorMessage) {
        return ToolResultBlock.error(errorMessage).withIdAndName(toolCallId, null);
    }
}
