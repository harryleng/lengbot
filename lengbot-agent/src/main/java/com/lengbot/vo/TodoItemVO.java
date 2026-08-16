package com.lengbot.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 待办项 VO（前端只读展示用）
 *
 * @author finch
 * @since 2026-07-17
 */
@Data
@Schema(description = "待办项")
public class TodoItemVO {

    @Schema(description = "待办稳定ID（LLM 提供）")
    private String id;

    @Schema(description = "待办内容")
    private String content;

    @Schema(description = "状态：pending/in_progress/completed/cancelled")
    private String status;
}
