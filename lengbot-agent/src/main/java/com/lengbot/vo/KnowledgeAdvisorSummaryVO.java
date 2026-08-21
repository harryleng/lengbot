package com.lengbot.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 知识库反馈聚合概览 VO
 *
 * @author lw
 * @since 2026-07-20
 */
@Data
@Schema(description = "知识库反馈聚合概览")
public class KnowledgeAdvisorSummaryVO {

    @Schema(description = "知识库ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long knowledgeId;

    @Schema(description = "总引用次数（出现该知识库分块的消息数）")
    private long totalReferences;

    @Schema(description = "总点赞数")
    private long totalLikes;

    @Schema(description = "总点踩数")
    private long totalDislikes;

    @Schema(description = "点赞率（0-1，like/(like+dislike)）")
    private double likeRate;

    @Schema(description = "被引用过的分块数")
    private long referencedChunkCount;

    @Schema(description = "最近 N 天内未被引用的分块数（休眠分块）")
    private long sleepingChunkCount;

    @Schema(description = "统计时间窗口（天）")
    private int windowDays;
}
