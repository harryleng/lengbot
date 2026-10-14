package com.lengbot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.lengbot.handler.JsonbTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.LocalDateTime;

/**
 * 工作流每节点检查点
 * <p>每次节点执行完成后写一行，记录当时运行态快照；进程崩溃/重启后按 run_id 取最新行前向续跑。</p>
 *
 * @author lw
 * @since 2026-10-14
 */
@Data
@TableName("workflow_checkpoint")
@Schema(description = "工作流每节点检查点")
public class WorkflowCheckpoint {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField("run_id")
    @Schema(description = "运行ID（与 workflow_test_run / 挂起 runId 同源）")
    private String runId;

    @TableField("agent_id")
    @Schema(description = "Agent ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long agentId;

    @TableField("session_id")
    @Schema(description = "会话 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    @TableField("step_index")
    @Schema(description = "已完成节点序号（从 1 递增）")
    private Integer stepIndex;

    @TableField("current_node_id")
    @Schema(description = "本检查点代表已执行完成的节点")
    private String currentNodeId;

    @TableField("next_node_id")
    @Schema(description = "应续跑的下一节点（为空表示工作流已结束）")
    private String nextNodeId;

    @TableField("status")
    @Schema(description = "状态：RUNNING | SUSPENDED | COMPLETED")
    private String status;

    @TableField("user_input")
    @Schema(description = "用户原始输入")
    private String userInput;

    @TableField(value = "variables", typeHandler = JsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    @Schema(description = "变量快照 JSON")
    private String variables;

    @TableField(value = "node_outputs", typeHandler = JsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    @Schema(description = "各节点输出快照 JSON")
    private String nodeOutputs;

    @TableField(value = "scoped_variables", typeHandler = JsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    @Schema(description = "命名空间变量池快照 JSON")
    private String scopedVariables;

    @TableField(value = "sys_variables", typeHandler = JsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    @Schema(description = "系统变量桶快照 JSON")
    private String sysVariables;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
