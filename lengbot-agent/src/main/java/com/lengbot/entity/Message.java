package com.lengbot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.lengbot.enums.ContentType;
import com.lengbot.enums.MessageRole;
import com.lengbot.enums.MessageType;
import com.lengbot.handler.JsonbTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.LocalDateTime;

/**
 * 消息表
 *
 * @author lw
 * @since 2026-05-19
 */
@Data
@TableName("message")
@Schema(description = "消息表")
public class Message {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField("session_id")
    @Schema(description = "会话ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    @TableField("role")
    @Schema(description = "角色")
    private MessageRole role;

    @TableField("content")
    @Schema(description = "消息内容")
    private String content;

    @TableField("content_type")
    @Schema(description = "内容类型")
    private ContentType contentType;

    @TableField("message_type")
    @Schema(description = "消息类型")
    private MessageType messageType;

    @TableField("token_count")
    @Schema(description = "Token数量")
    private Integer tokenCount;

    @TableField(value = "metadata", typeHandler = JsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    @Schema(description = "元数据")
    private String metadata;

    /**
     * 工具事件流（tool_call / tool_result / subagent_* 等），与 metadata 解耦存储。
     * <p>历史接口直接以顶层字段下发，避免前端对 metadata 整体 deep watch 时扫描工具结果字符串</p>
     */
    @TableField(value = "tool_events", typeHandler = JsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    @Schema(description = "工具事件流（与 metadata 解耦）")
    private String toolEvents;

    @TableField("parent_id")
    @Schema(description = "父消息ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    @TableField("reply_to_message_id")
    @Schema(description = "引用回复的消息ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long replyToMessageId;

    @TableField("starred")
    @Schema(description = "是否收藏")
    private Boolean starred;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
