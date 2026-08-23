package com.lengbot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.lengbot.handler.JsonbTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日工作日志表
 *
 * @author lw
 * @since 2026-08-23
 */
@Data
@TableName("daily_log")
@Schema(description = "每日工作日志表")
public class DailyLog {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField("user_id")
    @Schema(description = "用户ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @TableField("workspace_id")
    @Schema(description = "工作区ID，空表示用户全局")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long workspaceId;

    @TableField("log_date")
    @Schema(description = "日志日期（按天）")
    private LocalDate logDate;

    @TableField("summary")
    @Schema(description = "当日要点摘要")
    private String summary;

    @TableField(value = "raw_entries", typeHandler = JsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    @Schema(description = "原始记录列表：[{time, type, content}]")
    private String rawEntries;

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
