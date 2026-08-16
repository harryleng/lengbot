package com.lengbot.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跨会话历史搜索结果项
 * <p>每条记录表示一次「在某个会话中命中关键词的消息」，
 * 携带会话基础信息（标题、agent、最后消息时间）+ 命中消息片段，前端据此渲染并跳转</p>
 *
 * @author finch
 * @since 2026-07-18
 */
@Data
@Schema(description = "跨会话历史搜索结果项")
public class ConversationSearchResultVO {

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "命中消息 ID")
    private Long messageId;

    @Schema(description = "命中消息角色（user/assistant）")
    private String messageRole;

    @Schema(description = "命中消息内容片段（最多 200 字符）")
    private String snippet;

    @Schema(description = "命中消息时间")
    private LocalDateTime messageCreateTime;

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "所属会话 ID")
    private Long sessionId;

    @Schema(description = "所属会话标题")
    private String sessionTitle;

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "所属 Agent ID")
    private Long agentId;

    @Schema(description = "所属会话是否置顶")
    private Boolean pinned;

    @Schema(description = "所属会话最后消息时间")
    private LocalDateTime sessionLastMessageAt;
}
