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

/**
 * SubAgent 委派批次表。
 *
 * @author lw
 * @since 2026-07-09
 */
@Data
@TableName("subagent_task_batch")
@Schema(description = "SubAgent委派批次")
public class SubAgentTaskBatch {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField("batch_id")
    @Schema(description = "批次ID")
    private String batchId;

    @TableField("parent_request_id")
    @Schema(description = "父Agent请求ID")
    private String parentRequestId;

    @TableField("parent_thread_id")
    @Schema(description = "父Agent线程ID")
    private String parentThreadId;

    @TableField("parent_session_id")
    @Schema(description = "父Agent会话ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentSessionId;

    @TableField("mode")
    @Schema(description = "委派模式：sync/parallel")
    private String mode;

    @TableField("aggregation")
    @Schema(description = "聚合模式")
    private String aggregation;

    @TableField("status")
    @Schema(description = "批次状态：pending/running/completed/failed/cancelled")
    private String status;

    @TableField("total_count")
    @Schema(description = "任务总数")
    private Integer totalCount;

    @TableField("completed_count")
    @Schema(description = "完成数")
    private Integer completedCount;

    @TableField("failed_count")
    @Schema(description = "失败数")
    private Integer failedCount;

    @TableField("cancelled_count")
    @Schema(description = "取消数")
    private Integer cancelledCount;

    @TableField("cancel_requested")
    @Schema(description = "是否请求取消：0否 1是")
    private Integer cancelRequested;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
