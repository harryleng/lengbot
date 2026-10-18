package com.lengbot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.lengbot.enums.SessionStatus;
import com.lengbot.handler.JsonbTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.LocalDateTime;

/**
 * 对话会话表
 *
 * @author lw
 * @since 2026-05-19
 */
@Data
@TableName("chat_session")
@Schema(description = "对话会话表")
public class ChatSession {

    /** 默认会话标题 */
    public static final String DEFAULT_TITLE = "新对话";

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField("agent_id")
    @Schema(description = "AgentID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long agentId;

    @TableField("agent_version_id")
    @Schema(description = "最近使用的Agent版本快照ID（agent_version.id），null=未指定")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long agentVersionId;

    @TableField("user_id")
    @Schema(description = "用户ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @TableField("title")
    @Schema(description = "会话标题")
    private String title;

    @TableField("status")
    @Schema(description = "状态")
    private SessionStatus status;

    @TableField(value = "context", typeHandler = JsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    @Schema(description = "会话上下文")
    private String context;

    @TableField(value = "attachments", typeHandler = JsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    @Schema(description = "会话附件索引 JSON 数组")
    private String attachments;

    /**
     * 消息数量。
     * <p>updateStrategy = NEVER：禁止被 updateById 全字段回写覆盖。本字段的唯一写入口是
     * {@code ChatSessionMapper.incrementStats/decrementStats} 原子 SQL；若允许 updateById 写入，
     * 任何持有陈旧实体的路径（改标题/置顶/附件变更）都会把计数覆盖回旧值（lost update）。
     * 注意：本字段只在 insert 时由 createSession 赋值 0，insertStrategy 不受影响。
     */
    @TableField(value = "message_count", updateStrategy = FieldStrategy.NEVER)
    @Schema(description = "消息数量")
    private Integer messageCount;

    /**
     * 总Token消耗。同 {@link #messageCount}，仅由原子累加 SQL 维护，禁止 updateById 覆盖。
     */
    @TableField(value = "total_tokens", updateStrategy = FieldStrategy.NEVER)
    @Schema(description = "总Token消耗")
    private Long totalTokens;

    /**
     * 最后消息时间。同 {@link #messageCount}，由 incrementStats 里的 NOW() 维护，
     * 禁止 updateById 覆盖（否则会把 last_message_at 拨回旧时间，破坏会话列表排序）。
     */
    @TableField(value = "last_message_at", updateStrategy = FieldStrategy.NEVER)
    @Schema(description = "最后消息时间")
    private LocalDateTime lastMessageAt;

    @TableField("pinned")
    @Schema(description = "是否置顶")
    private Boolean pinned;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @TableField("deleted")
    @TableLogic
    @Schema(description = "逻辑删除标记")
    private Integer deleted;
}
