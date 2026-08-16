package com.lengbot.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 休眠分块 VO（长期未被检索命中的分块）
 *
 * @author finch
 * @since 2026-07-20
 */
@Data
@Schema(description = "休眠分块")
public class SleepingChunkVO {

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

    @Schema(description = "分块创建时间")
    private LocalDateTime chunkCreateTime;

    @Schema(description = "最近一次引用时间（null 表示从未被引用）")
    private LocalDateTime lastReferencedAt;

    @Schema(description = "休眠天数（从未被引用则为自创建以来的天数）")
    private long sleepingDays;

    @Schema(description = "引用总次数")
    private long referenceCount;
}
