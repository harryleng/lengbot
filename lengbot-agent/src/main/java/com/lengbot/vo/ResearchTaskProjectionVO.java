package com.lengbot.vo;

import com.lengbot.dto.ChatAttachmentDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一条用户请求触发的调研协作状态投影。
 * <p>该对象只聚合既有消息元数据和 SubAgent 运行记录，不维护第二份任务事实源。</p>
 */
@Data
@Schema(description = "调研任务状态投影")
public class ResearchTaskProjectionVO {

    @Schema(description = "父请求 ID")
    private String parentRequestId;

    @Schema(description = "任务聚合状态：idle/running/completed/failed")
    private String status;

    @Schema(description = "单调快照版本，取关联消息与任务的最新更新时间")
    private Long version;

    @Schema(description = "快照更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "完整待办快照")
    private List<Map<String, Object>> todos;

    @Schema(description = "本轮用户输入附件")
    private List<ChatAttachmentDTO> attachments;

    @Schema(description = "主 Agent 已登记的交付产物")
    private List<Map<String, Object>> artifacts;

    @Schema(description = "本轮子智能体运行摘要")
    private List<Map<String, Object>> subagents;

    @Schema(description = "本轮最近一次已持久化的模型用量；无记录时 available=false")
    private Map<String, Object> usage;
}
