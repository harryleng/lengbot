package com.lengbot.vo;
import com.lengbot.dto.*;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Agent 版本列表（含草稿快照 ID，供对话页匹配版本选项）
 */
@Data
@Builder
@Schema(description = "Agent 版本列表")
public class AgentVersionListVO {

    @Schema(description = "草稿版本快照 ID（agent_version.id）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long draftVersionId;

    @Schema(description = "已发布版本列表")
    private List<WorkflowVersionVO> versions;
}
