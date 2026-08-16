package com.lengbot.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** SubAgent 任务运行事件，按 ID 游标增量读取。 */
@Data
@TableName("subagent_task_event")
@Schema(description = "SubAgent任务运行事件")
public class SubAgentTaskEvent {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField("task_id")
    @Schema(description = "任务ID")
    private String taskId;

    @TableField("batch_id")
    @Schema(description = "批次ID")
    private String batchId;

    @TableField("event_type")
    @Schema(description = "事件类型")
    private String eventType;

    @TableField("payload")
    @Schema(description = "事件JSON载荷")
    private String payload;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
