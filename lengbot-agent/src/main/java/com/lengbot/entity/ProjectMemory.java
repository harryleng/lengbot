package com.lengbot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.lengbot.enums.UserMemoryStatus;
import com.lengbot.enums.UserMemoryType;
import com.lengbot.handler.JsonbTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工作区/项目级长期记忆表
 *
 * @author lw
 * @since 2026-08-23
 */
@Data
@TableName("project_memory")
@Schema(description = "工作区/项目级长期记忆表")
public class ProjectMemory {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField("user_id")
    @Schema(description = "用户ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @TableField("workspace_id")
    @Schema(description = "工作区ID，空表示用户全局工作区记忆")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long workspaceId;

    @TableField("session_id")
    @Schema(description = "来源会话ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    @TableField("memory_type")
    @Schema(description = "记忆类型")
    private UserMemoryType memoryType;

    @TableField("content")
    @Schema(description = "记忆内容")
    private String content;

    @TableField(value = "keywords", typeHandler = JsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    @Schema(description = "关键词列表")
    private String keywords;

    @TableField("source_message_id")
    @Schema(description = "来源消息ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceMessageId;

    @TableField("confidence")
    @Schema(description = "置信度")
    private BigDecimal confidence;

    @TableField("status")
    @Schema(description = "状态")
    private UserMemoryStatus status;

    @TableField("last_used_at")
    @Schema(description = "最后使用时间")
    private LocalDateTime lastUsedAt;

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
