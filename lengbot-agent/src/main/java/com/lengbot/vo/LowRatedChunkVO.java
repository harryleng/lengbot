package com.lengbot.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 低分分块 VO（点踩较多的分块，需关注）
 *
 * @author lw
 * @since 2026-07-20
 */
@Data
@Schema(description = "低分分块")
public class LowRatedChunkVO {

    @Schema(description = "分块ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long chunkId;

    @Schema(description = "文档ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long documentId;

    @Schema(description = "文档名称")
    private String documentName;

    @Schema(description = "分块内容预览")
    private String contentPreview;

    @Schema(description = "点赞数")
    private long likeCount;

    @Schema(description = "点踩数")
    private long dislikeCount;

    @Schema(description = "点踩率（0-1，dislike/(like+dislike)）")
    private double dislikeRate;

    @Schema(description = "引用次数（出现该分块的消息数）")
    private long referenceCount;

    @Schema(description = "最近一次引用时间")
    private LocalDateTime lastReferencedAt;
}
