package com.lengbot.entity;

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
 * 会话上下文摘要缓存
 *
 * <p>用于避免长会话每轮同步调用 LLM 生成上下文摘要而阻塞首字延迟。
 * 摘要结果落库后按 {@code upto_message_id} 记录覆盖位置，下一轮直接复用，
 * 只把该位置之后的新消息接在摘要后面；累积新消息再次超阈值时才异步刷新。</p>
 *
 * @author lw
 * @since 2026-10-21
 */
@Data
@TableName("chat_session_summary")
@Schema(description = "会话上下文摘要缓存表")
public class ChatSessionSummary {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField("session_id")
    @Schema(description = "会话ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    @TableField("upto_message_id")
    @Schema(description = "摘要覆盖到的最后一条消息ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long uptoMessageId;

    @TableField("covered_count")
    @Schema(description = "摘要覆盖的消息条数")
    private Integer coveredCount;

    @TableField("covered_chars")
    @Schema(description = "摘要覆盖的原始字符数")
    private Integer coveredChars;

    @TableField("summary")
    @Schema(description = "摘要正文")
    private String summary;

    @TableField("model")
    @Schema(description = "生成摘要所用的模型标识")
    private String model;

    @TableField("create_time")
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField("update_time")
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
