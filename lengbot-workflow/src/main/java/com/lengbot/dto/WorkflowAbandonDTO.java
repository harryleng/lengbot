package com.lengbot.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 放弃工作流人工确认请求
 */
@Data
@Schema(description = "放弃工作流人工确认请求")
public class WorkflowAbandonDTO {

    @NotBlank(message = "runId 不能为空")
    @Schema(description = "挂起的运行 ID")
    private String runId;

    @Schema(description = "Chat 对话中挂起时对应的助手消息 ID，放弃后回写 metadata")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long messageId;
}
