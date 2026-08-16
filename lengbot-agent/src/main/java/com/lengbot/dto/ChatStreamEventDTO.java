package com.lengbot.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式对话事件DTO
 * <p>用于在SSE流中发送不同类型的消息（状态、文本、引用等）</p>
 *
 * @author finch
 * @since 2026-05-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "流式对话事件")
public class ChatStreamEventDTO {

    @Schema(description = "事件类型：status-状态, text-文本, references-引用, done-完成")
    private EventType type;

    @Schema(description = "事件内容")
    private String content;

    @Schema(description = "耗时（毫秒）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long elapsed;

    public enum EventType {
        STATUS,    // 状态信息（如：正在检索知识库...）
        TEXT,      // 文本内容
        REFERENCES,// RAG引用信息
        DONE       // 完成信号
    }

    public static ChatStreamEventDTO status(String content) {
        return new ChatStreamEventDTO(EventType.STATUS, content, null);
    }

    public static ChatStreamEventDTO text(String content) {
        return new ChatStreamEventDTO(EventType.TEXT, content, null);
    }

    public static ChatStreamEventDTO done(Long elapsed) {
        return new ChatStreamEventDTO(EventType.DONE, null, elapsed);
    }
}
